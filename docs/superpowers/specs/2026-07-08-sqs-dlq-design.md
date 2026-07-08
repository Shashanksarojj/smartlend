# SQS + Dead Letter Queue — Design Spec
**Date:** 2026-07-08  
**Feature:** Replace RabbitMQ for loan events with AWS SQS + DLQ (LocalStack)  
**Status:** Approved, ready for implementation

---

## Goal

Swap the loan-event message broker from RabbitMQ (CloudAMQP) to AWS SQS with a dead-letter queue, running locally via LocalStack. A toggle (`AWS_SQS_ENABLED`) lets both brokers coexist so either can be demoed. User-service stays on RabbitMQ throughout.

---

## Architecture

```
AWS_SQS_ENABLED=false (default — unchanged):
  loan-service  ──RabbitMQ──▶  notification-service (@RabbitListener)
  user-service  ──RabbitMQ──▶  notification-service (@RabbitListener)

AWS_SQS_ENABLED=true:
  loan-service  ──SQS──▶  loan-events  ──▶  notification-service (@SqsListener)
                                                     ↓ after 3 failures
                                              loan-events-dlq
  user-service  ──RabbitMQ──▶  notification-service (@RabbitListener, active for user.registered only)
```

Two listeners coexist in notification-service at all times:
- `LoanEventConsumer` (`@RabbitListener`, always on) — handles `user.registered` from user-service
- `SqsConsumerService` (`@SqsListener`, `@ConditionalOnProperty`) — handles loan events when SQS is enabled

No double-processing: loan-service publishes to exactly one broker at a time based on the toggle.

---

## Publisher Changes (loan-service)

### New file: `config/SqsConfig.java`
- `@ConditionalOnProperty(name="aws.sqs.enabled", havingValue="true")`
- Reads `${aws.endpoint}`, `${aws.region}`, `${aws.access-key}`, `${aws.secret-key}` — same flat namespace as `S3Config` and `DynamoDbConfig`
- Creates `SqsClient` bean with endpoint override for LocalStack
- `ensureQueuesExist()` on startup:
  1. Create `loan-events-dlq`
  2. Create `loan-events` with redrive policy: `maxReceiveCount=3`, `deadLetterTargetArn=arn:aws:sqs:ap-south-1:000000000000:loan-events-dlq`
  - Both calls are idempotent (`CreateQueue` is a no-op if the queue already exists)

### Changes to `LoanService.java`
- `@Autowired(required=false) SqsClient sqsClient` — null when SQS disabled; avoids hard dependency
- `@Value("${aws.sqs.queue-url:}") String queueUrl`
- Extract private `publishEvent(String routingKey, Map<String, Object> payload)` helper — collapses the three duplicated `rabbitTemplate.convertAndSend(...)` calls
- Inside helper: `if (sqsClient != null)` → serialize map to JSON via `ObjectMapper` → `sqsClient.sendMessage(r -> r.queueUrl(queueUrl).messageBody(json))`; else → existing `rabbitTemplate.convertAndSend(exchange, routingKey, payload)`
- `ObjectMapper` injected (already on classpath)

### New `application.yml` keys (loan-service)
```yaml
aws:
  sqs:
    enabled: ${AWS_SQS_ENABLED:false}
    queue-name: ${AWS_SQS_QUEUE:loan-events}
    queue-url: ${AWS_SQS_QUEUE_URL:http://localhost:4566/000000000000/loan-events}
```

### New dependency (loan-service `pom.xml`)
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sqs</artifactId>
</dependency>
```
AWS SDK BOM is already imported — no version needed.

---

## Consumer Changes (notification-service)

### New dependencies (`pom.xml`)
```xml
<!-- Spring Cloud AWS BOM (dependency management) -->
<dependencyManagement>
  <dependency>
    <groupId>io.awspring.cloud</groupId>
    <artifactId>spring-cloud-aws-bom</artifactId>
    <version>3.1.1</version>
    <type>pom</type>
    <scope>import</scope>
  </dependency>
</dependencyManagement>

<!-- SQS listener support -->
<dependency>
  <groupId>io.awspring.cloud</groupId>
  <artifactId>spring-cloud-aws-starter-sqs</artifactId>
</dependency>

<!-- AWS SDK SQS module (for SqsClient in SqsConfig) -->
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>sqs</artifactId>
  <version>2.25.16</version> <!-- pin explicitly; no BOM here yet -->
</dependency>
```

> **Note:** notification-service doesn't have the AWS SDK BOM. Pin `software.amazon.awssdk:sqs` to the same version used in loan-service (check `loan-service/pom.xml` → `aws.sdk.version` property).

### New file: `config/SqsConfig.java`
- Identical to loan-service `SqsConfig` — same `SqsClient` bean, same `ensureQueuesExist()` startup check
- `@ConditionalOnProperty(name="aws.sqs.enabled", havingValue="true")`

### New file: `consumer/SqsConsumerService.java`
- `@Service @ConditionalOnProperty(name="aws.sqs.enabled", havingValue="true")`
- `@SqsListener(value = "${aws.sqs.queue-url}")` on `handleLoanEvent(Map<String, Object> event)`
- Spring Cloud AWS deserializes JSON message body via built-in Jackson converter
- Same `switch (type)` routing as `LoanEventConsumer` → delegates to `LoanNotificationHandler`
- On success: Spring Cloud AWS auto-deletes the message
- On exception: message re-enters visibility timeout; SQS redelivers up to 3×; then moves to DLQ

### `LoanEventConsumer.java` — no changes
Stays active at all times. Handles `user.registered` from user-service via RabbitMQ regardless of toggle state.

### New `application.yml` keys (notification-service)
```yaml
aws:
  region: ${AWS_REGION:ap-south-1}
  endpoint: ${AWS_ENDPOINT:http://localhost:4566}
  access-key: ${AWS_ACCESS_KEY_ID:test}
  secret-key: ${AWS_SECRET_ACCESS_KEY:test}
  sqs:
    enabled: ${AWS_SQS_ENABLED:false}
    queue-url: ${AWS_SQS_QUEUE_URL:http://localhost:4566/000000000000/loan-events}

spring:
  cloud:
    aws:
      region:
        static: ${AWS_REGION:ap-south-1}
      credentials:
        access-key: ${AWS_ACCESS_KEY_ID:test}
        secret-key: ${AWS_SECRET_ACCESS_KEY:test}
      sqs:
        endpoint: ${AWS_ENDPOINT:http://localhost:4566}
```

---

## Error Handling & DLQ

- **Visibility timeout: 30s** — well above max notification processing time (~5s for SendGrid + WhatsApp under load)
- SQS redelivers on failure up to `maxReceiveCount=3` times automatically via redrive policy
- After 3 failures, message lands in `loan-events-dlq` — inspectable via CLI, zero application code needed
- Audit failures (DynamoDB) already swallow exceptions and never propagate — this is unchanged
- `SqsConfig.ensureQueuesExist()` failures on startup should be logged as WARN, not throw — a pre-existing queue is not an error

---

## Testing

### `LoanServiceTest.java` (extend existing)
- Add `@Mock SqsClient sqsClient`
- `ReflectionTestUtils.setField(loanService, "sqsClient", sqsClient)` for SQS-enabled path
- Assert `verify(sqsClient).sendMessage(any(Consumer.class))` when SQS enabled
- Assert `verify(rabbitTemplate).convertAndSend(...)` when SQS disabled (default — existing tests unchanged)

### `SqsConsumerServiceTest.java` (new, notification-service)
- Mock `LoanNotificationHandler`
- Call `handleLoanEvent(Map.of("type", "APPROVED", ...))` directly — no SQS infra needed
- Assert correct handler method fires for each event type
- Mirror structure of existing `LoanEventConsumerTest`

---

## LocalStack CLI

```bash
# Bootstrap queues (SqsConfig does this automatically on startup)
awslocal sqs create-queue --queue-name loan-events-dlq --region ap-south-1

awslocal sqs create-queue \
  --queue-name loan-events \
  --attributes '{"RedrivePolicy":"{\"deadLetterTargetArn\":\"arn:aws:sqs:ap-south-1:000000000000:loan-events-dlq\",\"maxReceiveCount\":\"3\"}"}' \
  --region ap-south-1

# Inspect live queue
awslocal sqs get-queue-attributes \
  --queue-url http://localhost:4566/000000000000/loan-events \
  --attribute-names All --region ap-south-1

# Inspect DLQ after failures
awslocal sqs receive-message \
  --queue-url http://localhost:4566/000000000000/loan-events-dlq \
  --region ap-south-1
```

---

## Files Changed

| Service | File | Change |
|---------|------|--------|
| loan-service | `pom.xml` | Add `awssdk:sqs` dependency |
| loan-service | `config/SqsConfig.java` | **New** — SqsClient bean + queue bootstrap |
| loan-service | `service/LoanService.java` | Extract `publishEvent()` helper; add SQS branch |
| loan-service | `src/main/resources/application.yml` | Add `aws.sqs.*` keys |
| loan-service | `src/test/…/LoanServiceTest.java` | Add SQS mock + assertions |
| notification-service | `pom.xml` | Add Spring Cloud AWS BOM + sqs starter + awssdk:sqs |
| notification-service | `config/SqsConfig.java` | **New** — same SqsClient bean + queue bootstrap |
| notification-service | `consumer/SqsConsumerService.java` | **New** — @SqsListener consumer |
| notification-service | `src/main/resources/application.yml` | Add `aws.*` + `spring.cloud.aws.*` keys |
| notification-service | `src/test/…/SqsConsumerServiceTest.java` | **New** — consumer unit test |
