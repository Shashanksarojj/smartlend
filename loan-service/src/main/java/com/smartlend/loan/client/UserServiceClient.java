package com.smartlend.loan.client;

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

    public UserProfile getProfile(String userId) {
        try {
            return restTemplate.getForObject(
                userServiceUrl + "/api/auth/profile/" + userId,
                UserProfile.class
            );
        } catch (Exception e) {
            log.warn("Could not fetch profile for userId={}: {}", userId, e.getMessage());
            return null;
        }
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
