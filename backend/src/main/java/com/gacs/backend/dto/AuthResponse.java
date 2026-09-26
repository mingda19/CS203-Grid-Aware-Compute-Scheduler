package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Authentication response payload containing JWT access tokens, expiry information, and profile")
public class AuthResponse {

    @Schema(description = "Whether the authentication request was successful", example = "true")
    private boolean success;

    @Schema(description = "Descriptive outcome message", example = "Login successful.")
    private String message;

    @Schema(description = "User profile object (returned on successful authentication)")
    private UserDto user;

    @Schema(description = "Set to true if account registration/login requires email OTP verification before granting session tokens", example = "false")
    private boolean requiresOtp;

    @Schema(description = "Email address associated with the authentication or pending OTP verification", example = "operator@datacenter.io")
    private String email;

    @Schema(description = "Stateless signed JWT Bearer access token valid for 15 minutes", example = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJvcGVyYXRvckBkYXRhY2VudGVyLmlvIiwidXNlcklkIjoxLCJyb2xlIjoiUk9MRV9VU0VSIiwiZXhwIjoxNzg5MDAwMDAwfQ...")
    private String accessToken;

    @Schema(description = "Token type prefix for HTTP Authorization header", example = "Bearer")
    private String tokenType = "Bearer";

    @Schema(description = "Access token time-to-live in seconds (900s = 15 minutes)", example = "900")
    private Long expiresIn;

    @Schema(description = "Refresh token cookie time-to-live in seconds (86400s standard, 1209600s remember-me)", example = "86400")
    private Long refreshExpiresIn;

    public AuthResponse() {
    }

    public static AuthResponse success(String message, UserDto user) {
        AuthResponse resp = new AuthResponse();
        resp.success = true;
        resp.message = message;
        resp.user = user;
        resp.requiresOtp = false;
        return resp;
    }

    public static AuthResponse successWithToken(String message, UserDto user, String accessToken, Long expiresIn) {
        return successWithToken(message, user, accessToken, expiresIn, null);
    }

    public static AuthResponse successWithToken(String message, UserDto user, String accessToken, Long expiresIn, Long refreshExpiresIn) {
        AuthResponse resp = new AuthResponse();
        resp.success = true;
        resp.message = message;
        resp.user = user;
        resp.accessToken = accessToken;
        resp.tokenType = "Bearer";
        resp.expiresIn = expiresIn;
        resp.refreshExpiresIn = refreshExpiresIn;
        resp.requiresOtp = false;
        return resp;
    }

    public static AuthResponse otpRequired(String message, String email) {
        AuthResponse resp = new AuthResponse();
        resp.success = true;
        resp.message = message;
        resp.requiresOtp = true;
        resp.email = email;
        return resp;
    }

    public static AuthResponse error(String message) {
        AuthResponse resp = new AuthResponse();
        resp.success = false;
        resp.message = message;
        return resp;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public UserDto getUser() {
        return user;
    }

    public void setUser(UserDto user) {
        this.user = user;
    }

    public boolean isRequiresOtp() {
        return requiresOtp;
    }

    public void setRequiresOtp(boolean requiresOtp) {
        this.requiresOtp = requiresOtp;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getAccessToken() {
        return accessToken;
    }

    public void setAccessToken(String accessToken) {
        this.accessToken = accessToken;
    }

    public String getTokenType() {
        return tokenType;
    }

    public void setTokenType(String tokenType) {
        this.tokenType = tokenType;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }

    public Long getRefreshExpiresIn() {
        return refreshExpiresIn;
    }

    public void setRefreshExpiresIn(Long refreshExpiresIn) {
        this.refreshExpiresIn = refreshExpiresIn;
    }
}
