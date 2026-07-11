package com.smartlend.notification.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ses.SesClient;

import java.net.URI;

@Configuration
@Slf4j
public class SesConfig {

    @Value("${aws.endpoint:}")
    private String endpoint;

    @Value("${aws.region:ap-south-1}")
    private String region;

    @Value("${aws.access-key:test}")
    private String accessKey;

    @Value("${aws.secret-key:test}")
    private String secretKey;

    @Value("${aws.ses.from-email:noreply@smartlend.com}")
    private String fromEmail;

    @Bean
    @ConditionalOnProperty(name = "aws.ses.enabled", havingValue = "true")
    public SesClient sesClient() {
        var credentials = StaticCredentialsProvider.create(
                AwsBasicCredentials.create(accessKey, secretKey));

        var builder = SesClient.builder()
                .region(Region.of(region))
                .credentialsProvider(credentials);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
            log.info("SES using LocalStack endpoint: {}", endpoint);
        }

        SesClient client = builder.build();
        verifyFromAddress(client);
        return client;
    }

    private void verifyFromAddress(SesClient client) {
        try {
            client.verifyEmailIdentity(r -> r.emailAddress(fromEmail));
            log.info("SES sender address verified: {}", fromEmail);
        } catch (Exception e) {
            log.warn("SES sender address verification skipped: {}", e.getMessage());
        }
    }
}
