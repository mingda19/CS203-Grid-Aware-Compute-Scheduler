package com.gacs.backend.controller;

import com.gacs.backend.dto.*;
import com.gacs.backend.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}", allowCredentials = "true")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * User registration endpoint.
     * Encrypts the password, stores unverified user record, and sends 6-digit OTP via email.
     */
    @PostMapping({"/register", "/api/auth/register"})
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    /**
     * OTP verification endpoint.
     * Verifies the 6-digit OTP sent to the user's email and marks user as verified.
     */
    @PostMapping({"/verify-otp", "/api/auth/verify-otp"})
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Resend OTP endpoint.
     */
    @PostMapping({"/resend-otp", "/api/auth/resend-otp"})
    public ResponseEntity<ApiResponse<String>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        ApiResponse<String> response = authService.resendOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * User login endpoint.
     * Verifies credentials against BCrypt encrypted password and ensures account is verified.
     */
    @PostMapping({"/login", "/api/auth/login"})
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest httpRequest) {
        AuthResponse response = authService.login(request, httpRequest);
        return ResponseEntity.ok(response);
    }

    /**
     * User logout endpoint.
     * Invalidates the active session and clears security context.
     */
    @PostMapping({"/logout", "/api/auth/logout"})
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest httpRequest) {
        ApiResponse<Void> response = authService.logout(httpRequest);
        return ResponseEntity.ok(response);
    }
}
