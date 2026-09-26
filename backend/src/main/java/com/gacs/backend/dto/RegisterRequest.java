package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

@Schema(description = "User registration request payload")
public class RegisterRequest {

    @Schema(description = "Valid email address to be used as account username and OTP delivery target", example = "engineer@datacenter.io", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Email is required")
    @Email(message = "Invalid email format")
    private String email;

    @Schema(description = "Account password (minimum 6 characters)", example = "SecurePass123!", requiredMode = Schema.RequiredMode.REQUIRED, minLength = 6)
    @NotBlank(message = "Password is required")
    @Size(min = 6, message = "Password must be at least 6 characters")
    private String password;

    @Schema(description = "Full name of the user", example = "Alex Morgan", requiredMode = Schema.RequiredMode.NOT_REQUIRED)
    private String fullName;

    @Schema(description = "Initial assigned role (defaults to ROLE_USER; administrative escalation requires admin intervention)", example = "ROLE_USER", accessMode = Schema.AccessMode.READ_ONLY)
    private String role = "ROLE_USER";

    public RegisterRequest() {
    }

    public RegisterRequest(String email, String password, String fullName) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = "ROLE_USER";
    }

    public RegisterRequest(String email, String password, String fullName, String role) {
        this.email = email;
        this.password = password;
        this.fullName = fullName;
        this.role = "ROLE_USER"; // Always enforce ROLE_USER on public registration
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public String getFullName() {
        return fullName;
    }

    public void setFullName(String fullName) {
        this.fullName = fullName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
