package com.gacs.backend.dto;

public class AuthResponse {
    private boolean success;
    private String message;
    private UserDto user;
    private boolean requiresOtp;
    private String email;

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
}
