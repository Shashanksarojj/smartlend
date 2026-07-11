# AWS SES Transactional Email — Design Spec
**Date:** 2026-07-12
**Feature:** Replace SendGrid with AWS SES for transactional email in notification-service
**Status:** Approved, ready for implementation

---

## Goal

Add `SesEmailChannel` as a new `NotificationChannel` implementation that sends email via AWS SES (SDK v2). Toggle `NOTIFICATION_SES_ENABLED=true` + `NOTIFICATION_EMAIL_ENABLED=false` to switch from SendGrid to SES at runtime. Both channels coexist in code; only one fires at a time. LocalStack emulates SES locally.

---

## Architecture

```
NotificationDispatcher (unchanged)
  │
  ├─ SendGridEmailChannel  channelName="EMAIL_SENDGRID"  toggle: NOTIFICATION_EMAIL_ENABLED
  ├─ SesEmailChannel       channelName="EMAIL_SES"       toggle: NOTIFICATION_SES_ENABLED   ← new
  ├─ WhatsAppChannel
  └─ SmsChannel / PushChannel (stubs)
```

Mutual exclusivity is achieved via env vars — no dispatcher code changes. The dispatcher already calls `isEnabled()` and skips disabled channels.

---

## New Files

### `notification-service/src/main/java/com/smartlend/notification/config/SesConfig.java`

- `@ConditionalOnProperty(name = "aws.ses.enabled", havingValue = "true")` gates the bean
- Reads `${aws.endpoint}`, `${aws.region}`, `${aws.access-key}`, `${aws.secret-key}` — same flat namespace as SqsConfig, S3Config, DynamoDbConfig
- Builds `SesClient` with endpoint override when `aws.endpoint` is non-blank (LocalStack)
- `verifyFromAddress(SesClient, fromEmail)` called on startup — calls `VerifyEmailIdentityRequest`; catches and logs WARN on failure (never throws, LocalStack may reject non-sandboxed addresses gracefully)

### `notification-service/src/main/java/com/smartlend/notification/channel/email/SesEmailChannel.java`

- Implements `NotificationChannel`
- Constructor injects `SesClient` (`@Autowired(required=false)`) and reads:
  - `${aws.ses.from-email}` — sender address
  - `${aws.ses.from-name}` — sender display name
  - `${notification.channels.ses.enabled:false}` — `isEnabled()` return value
- `channelName()` returns `"EMAIL_SES"`
- `send(NotificationPayload payload)`:
  ```java
  sesClient.sendEmail(r -> r
      .destination(d -> d.toAddresses(payload.recipientEmail()))
      .message(m -> m
          .subject(c -> c.data(payload.subject()))
          .body(b -> b
              .html(c -> c.data(payload.htmlBody()))
              .text(c -> c.data(payload.body()))))
      .source(fromEmail));
  ```
  Prefers `htmlBody`; falls back to `body` for the text part. Throws on any SES error — dispatcher's per-channel catch handles it.
- If `sesClient` is null (bean not created because `aws.ses.enabled=false`), `isEnabled()` returns false and `send()` logs a WARN and returns — belt-and-suspenders guard.

---

## Changed Files

### `notification-service/pom.xml`

Add under the existing `software.amazon.awssdk:sqs` dependency:
```xml
<dependency>
  <groupId>software.amazon.awssdk</groupId>
  <artifactId>ses</artifactId>
  <version>2.25.16</version>
</dependency>
```
Version pinned explicitly (no BOM in notification-service for AWS SDK v2 — same pattern as `sqs`).

### `notification-service/src/main/resources/application.yml`

Add under the existing `aws:` block:
```yaml
aws:
  ses:
    enabled: ${AWS_SES_ENABLED:false}
    from-email: ${AWS_SES_FROM_EMAIL:noreply@smartlend.com}
    from-name: ${AWS_SES_FROM_NAME:SmartLend}
```

Add under `notification.channels`:
```yaml
notification:
  channels:
    ses:
      enabled: ${NOTIFICATION_SES_ENABLED:false}
```

### `docker-compose.yml`

Add to `notification-service` environment block:
```yaml
NOTIFICATION_SES_ENABLED: ${NOTIFICATION_SES_ENABLED:-false}
AWS_SES_ENABLED: ${AWS_SES_ENABLED:-false}
AWS_SES_FROM_EMAIL: ${AWS_SES_FROM_EMAIL:-noreply@smartlend.com}
AWS_SES_FROM_NAME: ${AWS_SES_FROM_NAME:-SmartLend}
AWS_ENDPOINT: http://host.docker.internal:4566
AWS_REGION: ${AWS_REGION:-ap-south-1}
AWS_ACCESS_KEY_ID: ${AWS_ACCESS_KEY_ID:-test}
AWS_SECRET_ACCESS_KEY: ${AWS_SECRET_ACCESS_KEY:-test}
```

---

## Configuration Reference

| Env var | Default | Purpose |
|---------|---------|---------|
| `AWS_SES_ENABLED` | `false` | Gates the `SesClient` bean (`@ConditionalOnProperty`) |
| `NOTIFICATION_SES_ENABLED` | `false` | Gates `isEnabled()` in `SesEmailChannel` |
| `AWS_SES_FROM_EMAIL` | `noreply@smartlend.com` | Sender address (must be verified in SES) |
| `AWS_SES_FROM_NAME` | `SmartLend` | Sender display name |
| `NOTIFICATION_EMAIL_ENABLED` | `true` | Set to `false` when SES is active to disable SendGrid |

**To switch from SendGrid → SES:**
```
AWS_SES_ENABLED=true
NOTIFICATION_SES_ENABLED=true
NOTIFICATION_EMAIL_ENABLED=false
```

---

## Error Handling

- `send()` throws any `SesException` — `NotificationDispatcher`'s per-channel try/catch logs it and continues
- `verifyFromAddress()` in `SesConfig` catches all exceptions and logs WARN — never blocks startup
- `sesClient` null guard in `SesEmailChannel` — if bean not created, `isEnabled()` returns false

---

## Testing

### New: `SesEmailChannelTest.java` (3 tests)

```java
@Test void send_htmlEmail_callsSesWithHtmlBody()
// Mock SesClient; verify sendEmail called with htmlBody content

@Test void isEnabled_false_skipsChannelWithoutCallingSes()
// Construct channel with enabled=false; assert isEnabled()==false; send() never calls sesClient

@Test void send_sesThrows_propagatesException()
// sesClient.sendEmail throws SesException; verify RuntimeException propagates
```

No changes to existing 25 tests.

**Total after feature:** 28 tests.

### LocalStack Smoke Test

```bash
# Verify sender address in LocalStack SES sandbox
awslocal ses verify-email-identity \
  --email-address noreply@smartlend.com \
  --region ap-south-1

# Confirm verified
awslocal ses list-verified-email-addresses --region ap-south-1

# Start services with SES enabled, SendGrid disabled:
# AWS_SES_ENABLED=true NOTIFICATION_SES_ENABLED=true NOTIFICATION_EMAIL_ENABLED=false

# Apply a loan → notification-service logs should show "SES email sent"
# LocalStack stores sent emails in memory — query via:
awslocal ses get-send-statistics --region ap-south-1
```

---

## Files Changed Summary

| Service | File | Change |
|---------|------|--------|
| notification-service | `pom.xml` | Add `awssdk:ses:2.25.16` |
| notification-service | `config/SesConfig.java` | **New** — `SesClient` bean + sender address verification |
| notification-service | `channel/email/SesEmailChannel.java` | **New** — `NotificationChannel` impl via SES SDK v2 |
| notification-service | `src/main/resources/application.yml` | Add `aws.ses.*` + `notification.channels.ses.enabled` |
| notification-service | `test/.../SesEmailChannelTest.java` | **New** — 3 unit tests |
| docker-compose.yml | root | Add SES env vars to notification-service block |
