package com.smartlend.loan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueUrlRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.QueueDoesNotExistException;

import java.net.URI;
import java.util.Map;

@Configuration
@Slf4j
public class SqsConfig {

    @Value("${aws.endpoint:}")
    private String endpoint;

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Value("${aws.sqs.queue-name:loan-events}")
    private String queueName;

    @Bean
    @ConditionalOnProperty(name = "aws.sqs.enabled", havingValue = "true")
    public SqsClient sqsClient() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var builder = SqsClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            log.info("SQS using LocalStack endpoint: {}", endpoint);
        }

        SqsClient client = builder.build();
        ensureQueuesExist(client);
        return client;
    }

    private void ensureQueuesExist(SqsClient client) {
        try {
            String dlqName = queueName + "-dlq";
            createQueueIfAbsent(client, dlqName, Map.of());

            // LocalStack ARN format: arn:aws:sqs:{region}:000000000000:{queue-name}
            String dlqArn = "arn:aws:sqs:" + region + ":000000000000:" + dlqName;
            String redrivePolicy = "{\"maxReceiveCount\":\"3\",\"deadLetterTargetArn\":\"" + dlqArn + "\"}";
            createQueueIfAbsent(client, queueName,
                    Map.of(QueueAttributeName.REDRIVE_POLICY, redrivePolicy));

            log.info("SQS queues ready: {} → DLQ: {}", queueName, dlqName);
        } catch (Exception e) {
            log.warn("Could not verify/create SQS queues: {}", e.getMessage());
        }
    }

    private void createQueueIfAbsent(SqsClient client, String name, Map<QueueAttributeName, String> attributes) {
        try {
            client.getQueueUrl(GetQueueUrlRequest.builder().queueName(name).build());
        } catch (QueueDoesNotExistException e) {
            var req = CreateQueueRequest.builder().queueName(name);
            if (!attributes.isEmpty()) req.attributes(attributes);
            String url = client.createQueue(req.build()).queueUrl();
            log.info("SQS queue '{}' created: {}", name, url);
        }
    }
}
