package com.gacs.backend.dto;

public class AuthResponse {
    private boolean success;
    private String message;
    private UserDto user;
    private boolean requiresOtp;
    private String email;
    private String accessToken;
    private String tokenType = "Bearer";
    private Long expiresIn;
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
