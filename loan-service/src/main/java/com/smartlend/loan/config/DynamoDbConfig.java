package com.smartlend.loan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import software.amazon.awssdk.services.dynamodb.model.*;

import java.net.URI;
import java.util.List;

@Configuration
@Slf4j
public class DynamoDbConfig {

    @Value("${aws.endpoint:}")
    private String endpoint;

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Value("${aws.dynamodb.table-name:loan-audit-log}")
    private String tableName;

    /**
     * DynamoDbClient with LocalStack endpoint override in local dev.
     * Same pattern as S3Client — blank endpoint = real AWS in production.
     *
     * Table schema (single-table design):
     *   PK  loanId  (String)  — all events for a loan live together
     *   SK  sk      (String)  — format: {ISO-timestamp}#{eventId}
     *                           ISO prefix gives chronological sort for free.
     */
    @Bean
    public DynamoDbClient dynamoDbClient() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var builder = DynamoDbClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            log.info("DynamoDB using LocalStack endpoint: {}", endpoint);
        }

        DynamoDbClient client = builder.build();
        ensureTableExists(client);
        return client;
    }

    private void ensureTableExists(DynamoDbClient client) {
        try {
            client.describeTable(r -> r.tableName(tableName));
            log.info("DynamoDB table '{}' ready", tableName);
        } catch (ResourceNotFoundException e) {
            // Table doesn't exist — create it with PAY_PER_REQUEST billing
            client.createTable(CreateTableRequest.builder()
                    .tableName(tableName)
                    .attributeDefinitions(
                            AttributeDefinition.builder().attributeName("loanId").attributeType(ScalarAttributeType.S).build(),
                            AttributeDefinition.builder().attributeName("sk").attributeType(ScalarAttributeType.S).build()
                    )
                    .keySchema(
                            KeySchemaElement.builder().attributeName("loanId").keyType(KeyType.HASH).build(),
                            KeySchemaElement.builder().attributeName("sk").keyType(KeyType.RANGE).build()
                    )
                    .billingMode(BillingMode.PAY_PER_REQUEST)
                    .build());
            log.info("DynamoDB table '{}' created", tableName);
        } catch (Exception e) {
            log.warn("Could not verify/create DynamoDB table '{}': {}", tableName, e.getMessage());
        }
    }
}
