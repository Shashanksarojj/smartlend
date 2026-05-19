package com.smartlend.user.service;

import com.smartlend.user.dto.AuthDto;
import com.smartlend.user.model.User;
import com.smartlend.user.repository.UserRepository;
import com.smartlend.user.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;
    private final RabbitTemplate rabbitTemplate;

    @Value("${rabbitmq.exchange}")
    private String exchange;

    @Value("${rabbitmq.routing-key.user-registered}")
    private String userRegisteredKey;

    @Transactional
    public AuthDto.AuthResponse register(AuthDto.RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .monthlyIncome(request.getMonthlyIncome())
                .employmentType(request.getEmploymentType())
                .role(User.Role.APPLICANT)
                .kycStatus(User.KycStatus.PENDING)
                .build();

        user = userRepository.save(user);
        log.info("New applicant registered: {}", user.getEmail());

        rabbitTemplate.convertAndSend(exchange, userRegisteredKey,
            Map.of("userId", user.getId(), "userEmail", user.getEmail(),
                   "userName", user.getFullName(),
                   "userPhone", user.getPhone() != null ? user.getPhone() : "",
                   "type", "USER_REGISTERED"));
        log.debug("Published user.registered event — userId={}", user.getId());

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return buildAuthResponse(user, token);
    }

    public AuthDto.AuthResponse login(AuthDto.LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new RuntimeException("Invalid credentials"));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new RuntimeException("Invalid credentials");
        }

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        log.info("User logged in: {} role={}", user.getEmail(), user.getRole());
        return buildAuthResponse(user, token);
    }

    public AuthDto.UserProfileResponse getProfile(String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new RuntimeException("User not found"));

        return AuthDto.UserProfileResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .phone(user.getPhone())
                .panCard(user.getPanCard())
                .monthlyIncome(user.getMonthlyIncome())
                .employmentType(user.getEmploymentType())
                .role(user.getRole())
                .kycStatus(user.getKycStatus())
                .build();
    }

    public AuthDto.AuthResponse createAdmin(AuthDto.RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new RuntimeException("Email already registered");
        }

        User user = User.builder()
                .email(request.getEmail())
                .password(passwordEncoder.encode(request.getPassword()))
                .fullName(request.getFullName())
                .phone(request.getPhone())
                .monthlyIncome(request.getMonthlyIncome())
                .employmentType(request.getEmploymentType())
                .role(User.Role.ADMIN)
                .kycStatus(User.KycStatus.VERIFIED)
                .build();

        user = userRepository.save(user);
        log.info("New admin created: {}", user.getEmail());

        String token = jwtUtil.generateToken(user.getId(), user.getEmail(), user.getRole().name());
        return buildAuthResponse(user, token);
    }

    private AuthDto.AuthResponse buildAuthResponse(User user, String token) {
        return AuthDto.AuthResponse.builder()
                .token(token)
                .userId(user.getId())
                .email(user.getEmail())
                .fullName(user.getFullName())
                .role(user.getRole().name())
                .kycStatus(user.getKycStatus().name())
                .monthlyIncome(user.getMonthlyIncome())
                .employmentType(user.getEmploymentType() != null ? user.getEmploymentType().name() : null)
                .build();
    }
}
