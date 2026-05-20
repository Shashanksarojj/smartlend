package com.smartlend.loan.client;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserServiceClient {

    private final RestTemplate restTemplate;

    @Value("${services.user-service-url}")
    private String userServiceUrl;

    @CircuitBreaker(name = "user-service", fallbackMethod = "fallbackProfile")
    public UserProfile getProfile(String userId) {
        return restTemplate.getForObject(
            userServiceUrl + "/api/auth/profile/" + userId,
            UserProfile.class
        );
    }

    // Called by the circuit breaker when open or when getProfile() throws
    private UserProfile fallbackProfile(String userId, Throwable t) {
        log.warn("User-service circuit breaker fallback for userId={}. Cause: {}", userId, t.getMessage());
        return null;  // LoanService handles null gracefully with default name/email/phone
    }

    @Data
    public static class UserProfile {
        private String id;
        private String email;
        private String fullName;
        private String phone;
        private Double monthlyIncome;
        private String employmentType;
        private String role;
        private String kycStatus;
    }
}
