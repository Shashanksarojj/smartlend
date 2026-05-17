package com.smartlend.loan.exception;

import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.time.Instant;
import java.util.Map;
import java.util.stream.Collectors;

@RestControllerAdvice
@Slf4j
public class GlobalExceptionHandler {

    public record ApiError(
            int status,
            String error,
            String message,
            String path,
            String timestamp
    ) {}

    public record ApiValidationError(
            int status,
            String error,
            String message,
            Map<String, String> fields,
            String path,
            String timestamp
    ) {}

    private String now() { return Instant.now().toString(); }

    // ── 400 Bad Request ───────────────────────────────────────

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiError> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest req) {
        log.warn("Bad request [{}]: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "BAD_REQUEST", ex.getMessage(), req.getRequestURI(), now()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> handleMissingHeader(MissingRequestHeaderException ex, HttpServletRequest req) {
        String message = "Required request header '" + ex.getHeaderName() + "' is missing";
        log.warn("Missing header [{}]: {}", req.getRequestURI(), message);
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "MISSING_HEADER", message, req.getRequestURI(), now()));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> handleTypeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest req) {
        String message = "Parameter '" + ex.getName() + "' has invalid value: '" + ex.getValue() + "'";
        log.warn("Type mismatch [{}]: {}", req.getRequestURI(), message);
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "INVALID_PARAMETER", message, req.getRequestURI(), now()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<ApiError> handleRuntime(RuntimeException ex, HttpServletRequest req) {
        log.warn("Business error [{}]: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.badRequest()
                .body(new ApiError(400, "BAD_REQUEST", ex.getMessage(), req.getRequestURI(), now()));
    }

    // ── 401 Unauthorized ──────────────────────────────────────

    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> handleAuthentication(AuthenticationException ex, HttpServletRequest req) {
        log.warn("Authentication failed [{}]: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(new ApiError(401, "UNAUTHORIZED",
                        "Authentication required. Please provide a valid Bearer token.",
                        req.getRequestURI(), now()));
    }

    // ── 403 Forbidden ─────────────────────────────────────────

    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        log.warn("Access denied [{}]: {}", req.getRequestURI(), ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(new ApiError(403, "FORBIDDEN",
                        "You do not have permission to access this resource. Required role may be ADMIN.",
                        req.getRequestURI(), now()));
    }

    // ── 404 Not Found ─────────────────────────────────────────

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(NoResourceFoundException ex, HttpServletRequest req) {
        log.warn("Resource not found [{}]", req.getRequestURI());
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(new ApiError(404, "NOT_FOUND",
                        "The requested endpoint does not exist: " + req.getMethod() + " " + req.getRequestURI(),
                        req.getRequestURI(), now()));
    }

    // ── 422 Validation Error ──────────────────────────────────

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiValidationError> handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        Map<String, String> fields = ex.getBindingResult().getFieldErrors().stream()
                .collect(Collectors.toMap(
                        FieldError::getField,
                        f -> f.getDefaultMessage() != null ? f.getDefaultMessage() : "invalid value",
                        (a, b) -> a
                ));
        log.warn("Validation failed [{}]: {}", req.getRequestURI(), fields);
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(new ApiValidationError(422, "VALIDATION_ERROR",
                        "Request validation failed. Check the 'fields' map for details.",
                        fields, req.getRequestURI(), now()));
    }

    // ── 500 Internal Server Error ─────────────────────────────

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception ex, HttpServletRequest req) {
        log.error("Unexpected error [{}]", req.getRequestURI(), ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(new ApiError(500, "INTERNAL_SERVER_ERROR",
                        "An unexpected error occurred. Please try again later.",
                        req.getRequestURI(), now()));
    }
}
