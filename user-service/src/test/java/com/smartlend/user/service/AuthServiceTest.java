package com.smartlend.user.service;

import com.smartlend.user.dto.AuthDto;
import com.smartlend.user.model.User;
import com.smartlend.user.repository.UserRepository;
import com.smartlend.user.security.JwtUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtUtil jwtUtil;
    @Mock private RabbitTemplate rabbitTemplate;

    @InjectMocks private AuthService authService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(authService, "exchange", "smartlend.exchange");
        ReflectionTestUtils.setField(authService, "userRegisteredKey", "user.registered");
    }

    // ── register ───────────────────────────────────────────────

    @Test
    void register_persistsUserAndPublishesRabbitMQEvent() {
        AuthDto.RegisterRequest req = buildRegisterRequest("new@example.com");

        when(userRepository.existsByEmail("new@example.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");
        when(userRepository.save(any(User.class))).thenReturn(savedApplicant("user-1", "new@example.com"));
        when(jwtUtil.generateToken("user-1", "new@example.com", "APPLICANT")).thenReturn("token-abc");

        AuthDto.AuthResponse res = authService.register(req);

        assertThat(res.getEmail()).isEqualTo("new@example.com");
        assertThat(res.getRole()).isEqualTo("APPLICANT");
        assertThat(res.getToken()).isEqualTo("token-abc");
        verify(rabbitTemplate).convertAndSend(eq("smartlend.exchange"), eq("user.registered"), anyMap());
    }

    @Test
    void register_throwsWhenEmailAlreadyRegistered() {
        AuthDto.RegisterRequest req = buildRegisterRequest("taken@example.com");
        when(userRepository.existsByEmail("taken@example.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Email already registered");
    }

    // ── login ──────────────────────────────────────────────────

    @Test
    void login_returnsAuthResponseForValidCredentials() {
        User user = savedApplicant("user-1", "test@example.com");
        user.setPassword("hashed");

        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("password123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("password123", "hashed")).thenReturn(true);
        when(jwtUtil.generateToken("user-1", "test@example.com", "APPLICANT")).thenReturn("token-xyz");

        AuthDto.AuthResponse res = authService.login(req);

        assertThat(res.getToken()).isEqualTo("token-xyz");
        assertThat(res.getRole()).isEqualTo("APPLICANT");
    }

    @Test
    void login_throwsForUnknownEmail() {
        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setEmail("nobody@example.com");
        req.setPassword("any");

        when(userRepository.findByEmail("nobody@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid credentials");
    }

    @Test
    void login_throwsForWrongPassword() {
        User user = savedApplicant("user-1", "test@example.com");
        user.setPassword("hashed");

        AuthDto.LoginRequest req = new AuthDto.LoginRequest();
        req.setEmail("test@example.com");
        req.setPassword("wrong");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("wrong", "hashed")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Invalid credentials");
    }

    // ── createAdmin ────────────────────────────────────────────

    @Test
    void createAdmin_assignsAdminRoleAndVerifiedKyc() {
        AuthDto.RegisterRequest req = buildRegisterRequest("admin@smartlend.com");

        when(userRepository.existsByEmail("admin@smartlend.com")).thenReturn(false);
        when(passwordEncoder.encode("password123")).thenReturn("hashed");

        User adminUser = User.builder()
                .id("admin-1").email("admin@smartlend.com").fullName("Admin")
                .role(User.Role.ADMIN).kycStatus(User.KycStatus.VERIFIED)
                .build();
        when(userRepository.save(any(User.class))).thenReturn(adminUser);
        when(jwtUtil.generateToken("admin-1", "admin@smartlend.com", "ADMIN")).thenReturn("admin-token");

        AuthDto.AuthResponse res = authService.createAdmin(req);

        assertThat(res.getRole()).isEqualTo("ADMIN");
        assertThat(res.getKycStatus()).isEqualTo("VERIFIED");
    }

    @Test
    void createAdmin_throwsWhenEmailAlreadyTaken() {
        AuthDto.RegisterRequest req = buildRegisterRequest("existing@smartlend.com");
        when(userRepository.existsByEmail("existing@smartlend.com")).thenReturn(true);

        assertThatThrownBy(() -> authService.createAdmin(req))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Email already registered");
    }

    // ── getProfile ─────────────────────────────────────────────

    @Test
    void getProfile_returnsProfileForKnownUser() {
        User user = savedApplicant("user-1", "test@example.com");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        AuthDto.UserProfileResponse profile = authService.getProfile("user-1");

        assertThat(profile.getEmail()).isEqualTo("test@example.com");
        assertThat(profile.getRole()).isEqualTo(User.Role.APPLICANT);
    }

    @Test
    void getProfile_throwsForUnknownUser() {
        when(userRepository.findById("unknown")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.getProfile("unknown"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("User not found");
    }

    // ── helpers ────────────────────────────────────────────────

    private AuthDto.RegisterRequest buildRegisterRequest(String email) {
        AuthDto.RegisterRequest req = new AuthDto.RegisterRequest();
        req.setEmail(email);
        req.setPassword("password123");
        req.setFullName("Test User");
        req.setPhone("9999999999");
        req.setMonthlyIncome(50000.0);
        req.setEmploymentType(User.EmploymentType.SALARIED);
        return req;
    }

    private User savedApplicant(String id, String email) {
        return User.builder()
                .id(id).email(email).fullName("Test User")
                .role(User.Role.APPLICANT).kycStatus(User.KycStatus.PENDING)
                .build();
    }
}