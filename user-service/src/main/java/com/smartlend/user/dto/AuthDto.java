package com.smartlend.user.dto;

import com.smartlend.user.model.User;
import jakarta.validation.constraints.*;
import lombok.Data;

public class AuthDto {

    @Data
    public static class RegisterRequest {
        @NotBlank @Email
        private String email;

        @NotBlank @Size(min = 8)
        private String password;

        @NotBlank
        private String fullName;

        private String phone;
        private Double monthlyIncome;
        private User.EmploymentType employmentType;
    }

    @Data
    public static class LoginRequest {
        @NotBlank @Email
        private String email;

        @NotBlank
        private String password;
    }

    @Data
    @lombok.Builder
    public static class AuthResponse {
        private String token;
        private String userId;
        private String email;
        private String fullName;
        private String role;
        private String kycStatus;
        private Double monthlyIncome;
        private String employmentType;
    }

    @Data
    @lombok.Builder
    public static class UserProfileResponse {
        private String id;
        private String email;
        private String fullName;
        private String phone;
        private String panCard;
        private Double monthlyIncome;
        private User.EmploymentType employmentType;
        private User.Role role;
        private User.KycStatus kycStatus;
    }
}
