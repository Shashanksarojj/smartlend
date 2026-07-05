package com.smartlend.loan.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

import java.net.URI;

@Configuration
@Slf4j
public class S3Config {

    @Value("${aws.s3.endpoint:}")
    private String endpoint;

    @Value("${aws.s3.region:us-east-1}")
    private String region;

    @Value("${aws.s3.access-key:test}")
    private String accessKey;

    @Value("${aws.s3.secret-key:test}")
    private String secretKey;

    @Value("${aws.s3.bucket-name:smartlend-documents}")
    private String bucketName;

    /**
     * S3Client with LocalStack endpoint override in local dev.
     * forcePathStyle(true) is required by LocalStack — it can't resolve virtual-hosted
     * bucket URLs like bucket.localhost.
     * In production omit AWS_S3_ENDPOINT and the client will hit real AWS.
     */
    @Bean
    public S3Client s3Client() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var builder = S3Client.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials)
                .forcePathStyle(true);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            log.info("S3 using LocalStack endpoint: {}", endpoint);
        }

        S3Client client = builder.build();
        ensureBucketExists(client);
        return client;
    }

    /**
     * S3Presigner generates time-limited signed URLs for object downloads.
     * Same endpoint override as the main client.
     */
    @Bean
    public S3Presigner s3Presigner() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var builder = S3Presigner.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }

        return builder.build();
    }

    private void ensureBucketExists(S3Client client) {
        try {
            boolean exists = client.listBuckets().buckets().stream()
                    .anyMatch(b -> b.name().equals(bucketName));
            if (!exists) {
                client.createBucket(r -> r.bucket(bucketName));
                log.info("S3 bucket '{}' created", bucketName);
            } else {
                log.info("S3 bucket '{}' ready", bucketName);
            }
        } catch (Exception e) {
            log.warn("Could not verify/create S3 bucket '{}': {}", bucketName, e.getMessage());
        }
    }
}
