package com.gacs.backend.controller;

import com.gacs.backend.dto.*;
import com.gacs.backend.service.AuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Authentication & Session", description = "Endpoints for user registration, email OTP verification, JWT authentication, sliding-window Refresh Token rotation, current user profile, and session termination.")
@RestController
@CrossOrigin(origins = "${cors.allowed-origins:http://localhost:3000,http://127.0.0.1:3000}", allowCredentials = "true")
public class AuthController {

    @Autowired
    private AuthService authService;

    /**
     * User registration endpoint.
     * Encrypts the password, stores unverified user record, and sends 6-digit OTP via email.
     */
    @Operation(
            summary = "Register a new user account",
            description = "Creates a new user profile with encrypted credentials, marks the account as unverified, "
                    + "and dispatches a 6-digit OTP verification code valid for 10 minutes to the user's email address."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "User registered successfully; OTP dispatched to email.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid payload (e.g., malformed email, password < 6 characters, or email already registered)."
            )
    })
    @PostMapping({"/register", "/api/auth/register"})
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        AuthResponse response = authService.register(request);
        return ResponseEntity.ok(response);
    }

    /**
     * OTP verification endpoint.
     * Verifies the 6-digit OTP sent to the user's email and marks user as verified.
     */
    @Operation(
            summary = "Verify account email with 6-digit OTP",
            description = "Validates the 6-digit verification code sent via email. Upon successful verification, "
                    + "the user account is marked as verified and can proceed to login."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "OTP successfully verified; user account activated.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Invalid or expired OTP code, or email not found."
            )
    })
    @PostMapping({"/verify-otp", "/api/auth/verify-otp"})
    public ResponseEntity<AuthResponse> verifyOtp(@Valid @RequestBody VerifyOtpRequest request) {
        AuthResponse response = authService.verifyOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * Resend OTP endpoint.
     */
    @Operation(
            summary = "Resend a fresh email OTP verification code",
            description = "Invalidates any existing active OTP for the specified email address and sends a newly generated 6-digit code."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Fresh OTP successfully dispatched to email.",
                    content = @Content(schema = @Schema(implementation = com.gacs.backend.dto.ApiResponse.class))
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Account already verified, email not found, or rate limit exceeded."
            )
    })
    @PostMapping({"/resend-otp", "/api/auth/resend-otp"})
    public ResponseEntity<com.gacs.backend.dto.ApiResponse<String>> resendOtp(@Valid @RequestBody ResendOtpRequest request) {
        com.gacs.backend.dto.ApiResponse<String> response = authService.resendOtp(request);
        return ResponseEntity.ok(response);
    }

    /**
     * User login endpoint.
     * Verifies credentials against BCrypt encrypted password and ensures account is verified.
     * Returns a 15-minute JWT Access Token and sets a persistent Refresh Token in an HttpOnly cookie.
     */
    @Operation(
            summary = "Authenticate user with email and password",
            description = "Validates credentials against BCrypt password hash. If the account is unverified, "
                    + "returns `requiresOtp: true` and triggers a new OTP email. If verified, returns a 15-minute JWT Access Token "
                    + "in the response body and sets a persistent Refresh Token in a secure, HttpOnly cookie (24 hours standard, 14 days if rememberMe=true)."
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Authentication successful. Access token returned in body, refresh token set in HttpOnly cookie.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Invalid credentials (incorrect email or password)."
            ),
            @ApiResponse(
                    responseCode = "400",
                    description = "Malformed request body or validation failure."
            )
    })
    @PostMapping({"/login", "/api/auth/login"})
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest request,
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResponse response = authService.login(request, httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }

    /**
     * Refresh token endpoint.
     * Rotates the refresh token (remember-me session) and issues a fresh JWT access token.
     */
    @Operation(
            summary = "Rotate refresh token and issue new JWT access token",
            description = "Extracts the persistent refresh token from the HttpOnly `refreshToken` cookie. "
                    + "Performs Refresh Token Rotation (RTR): invalidates the current refresh token, issues a brand new refresh token "
                    + "in the cookie, and returns a fresh 15-minute JWT Access Token. If an already-used or revoked token is presented, "
                    + "the system immediately revokes the entire token family as a theft mitigation measure.",
            security = @SecurityRequirement(name = "cookieAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Session successfully refreshed. Fresh access token returned.",
                    content = @Content(schema = @Schema(implementation = AuthResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Refresh token missing, expired, or invalid."
            )
    })
    @PostMapping({"/refresh", "/api/auth/refresh"})
    public ResponseEntity<AuthResponse> refreshToken(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        AuthResponse response = authService.refreshToken(httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }

    /**
     * Current authenticated user endpoint.
     * Returns the user profile for the validated JWT access token.
     */
    @Operation(
            summary = "Get current authenticated user profile",
            description = "Returns the user profile associated with the validated JWT Bearer token in the `Authorization` header.",
            security = @SecurityRequirement(name = "bearerAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Active session profile retrieved successfully.",
                    content = @Content(schema = @Schema(implementation = com.gacs.backend.dto.ApiResponse.class))
            ),
            @ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Missing, expired, or invalid JWT access token."
            )
    })
    @GetMapping({"/me", "/api/auth/me"})
    public ResponseEntity<com.gacs.backend.dto.ApiResponse<UserDto>> getCurrentUser(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated() || "anonymousUser".equals(authentication.getPrincipal())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(com.gacs.backend.dto.ApiResponse.error("Unauthorized: No active session."));
        }
        UserDto userDto = authService.getCurrentUser(authentication.getName());
        return ResponseEntity.ok(com.gacs.backend.dto.ApiResponse.ok("User session is active.", userDto));
    }

    /**
     * User logout endpoint.
     * Revokes the persistent refresh token, clears HttpOnly cookie, and clears security context.
     */
    @Operation(
            summary = "Terminate user session and revoke refresh token",
            description = "Revokes the active refresh token in the database, sets the `refreshToken` HttpOnly cookie max-age to 0 to clear it from the client, "
                    + "and clears the Spring Security context.",
            security = @SecurityRequirement(name = "cookieAuth")
    )
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Successfully logged out. Refresh cookie cleared and session invalidated.",
                    content = @Content(schema = @Schema(implementation = com.gacs.backend.dto.ApiResponse.class))
            )
    })
    @PostMapping({"/logout", "/api/auth/logout"})
    public ResponseEntity<com.gacs.backend.dto.ApiResponse<Void>> logout(
            HttpServletRequest httpRequest,
            HttpServletResponse httpResponse) {
        com.gacs.backend.dto.ApiResponse<Void> response = authService.logout(httpRequest, httpResponse);
        return ResponseEntity.ok(response);
    }
}
