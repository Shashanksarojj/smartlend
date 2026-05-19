package com.smartlend.user.controller;

import com.smartlend.user.dto.AuthDto;
import com.smartlend.user.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Tag(name = "Authentication", description = "Register, login, and user profile")
public class AuthController {

    private final AuthService authService;

    @Value("${app.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${app.cookie.same-site:Lax}")
    private String cookieSameSite;

    // ── Public endpoints ──────────────────────────────────────

    @PostMapping("/register")
    @Operation(summary = "Register a new applicant — sets HttpOnly auth cookie")
    public ResponseEntity<AuthDto.AuthResponse> register(
            @Valid @RequestBody AuthDto.RegisterRequest request,
            HttpServletResponse response) {
        AuthDto.AuthResponse auth = authService.register(request);
        setAuthCookie(response, auth.getToken());
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/login")
    @Operation(summary = "Login — sets HttpOnly auth cookie")
    public ResponseEntity<AuthDto.AuthResponse> login(
            @Valid @RequestBody AuthDto.LoginRequest request,
            HttpServletResponse response) {
        AuthDto.AuthResponse auth = authService.login(request);
        setAuthCookie(response, auth.getToken());
        return ResponseEntity.ok(auth);
    }

    @PostMapping("/logout")
    @Operation(summary = "Logout — clears the auth cookie")
    public ResponseEntity<Void> logout(HttpServletResponse response) {
        clearAuthCookie(response);
        return ResponseEntity.noContent().build();
    }

    // ── Authenticated endpoints ───────────────────────────────

    @GetMapping("/me")
    @Operation(summary = "Restore session — validates cookie and returns fresh profile + token")
    public ResponseEntity<AuthDto.AuthResponse> me() {
        String userId = (String) SecurityContextHolder.getContext().getAuthentication().getPrincipal();
        return ResponseEntity.ok(authService.getMyProfile(userId));
    }

    @GetMapping("/profile/{userId}")
    @Operation(summary = "Get user profile by ID (internal use by loan-service)")
    public ResponseEntity<AuthDto.UserProfileResponse> getProfile(@PathVariable String userId) {
        return ResponseEntity.ok(authService.getProfile(userId));
    }

    @PostMapping("/admin/create-admin")
    @Operation(summary = "Admin: create a new admin user", security = @SecurityRequirement(name = "bearerAuth"))
    public ResponseEntity<AuthDto.AuthResponse> createAdmin(
            @Valid @RequestBody AuthDto.RegisterRequest request,
            HttpServletResponse response) {
        AuthDto.AuthResponse auth = authService.createAdmin(request);
        setAuthCookie(response, auth.getToken());
        return ResponseEntity.status(HttpStatus.CREATED).body(auth);
    }

    // ── Cookie helpers ────────────────────────────────────────

    private void setAuthCookie(HttpServletResponse response, String token) {
        ResponseCookie cookie = ResponseCookie.from("auth_token", token)
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(Duration.ofDays(7))
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }

    private void clearAuthCookie(HttpServletResponse response) {
        ResponseCookie cookie = ResponseCookie.from("auth_token", "")
                .httpOnly(true)
                .secure(cookieSecure)
                .sameSite(cookieSameSite)
                .path("/")
                .maxAge(0)
                .build();
        response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString());
    }
}