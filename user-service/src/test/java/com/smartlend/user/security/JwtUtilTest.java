package com.smartlend.user.security;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.*;

class JwtUtilTest {

    private JwtUtil jwtUtil;

    private static final String SECRET = "smartlend-super-secret-jwt-key-change-in-prod";

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "secret", SECRET);
        ReflectionTestUtils.setField(jwtUtil, "expiration", 86400000L);
    }

    @Test
    void generateToken_returnsNonBlankToken() {
        String token = jwtUtil.generateToken("user-123", "test@example.com", "APPLICANT");
        assertThat(token).isNotBlank();
    }

    @Test
    void validateToken_containsCorrectClaims() {
        String token = jwtUtil.generateToken("user-123", "test@example.com", "APPLICANT");

        Claims claims = jwtUtil.validateToken(token);

        assertThat(claims.getSubject()).isEqualTo("user-123");
        assertThat(claims.get("email", String.class)).isEqualTo("test@example.com");
        assertThat(claims.get("role", String.class)).isEqualTo("APPLICANT");
    }

    @Test
    void isValid_returnsTrueForFreshToken() {
        String token = jwtUtil.generateToken("user-123", "test@example.com", "APPLICANT");
        assertThat(jwtUtil.isValid(token)).isTrue();
    }

    @Test
    void isValid_returnsFalseForGarbageString() {
        assertThat(jwtUtil.isValid("not.a.jwt")).isFalse();
    }

    @Test
    void isValid_returnsFalseForExpiredToken() {
        ReflectionTestUtils.setField(jwtUtil, "expiration", -1000L);
        String token = jwtUtil.generateToken("user-123", "test@example.com", "APPLICANT");
        assertThat(jwtUtil.isValid(token)).isFalse();
    }

    @Test
    void adminToken_hasAdminRole() {
        String token = jwtUtil.generateToken("admin-1", "admin@smartlend.com", "ADMIN");
        Claims claims = jwtUtil.validateToken(token);
        assertThat(claims.get("role", String.class)).isEqualTo("ADMIN");
    }
}