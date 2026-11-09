package com.gacs.backend.dto;

import jakarta.validation.constraints.*;

public record ResetPasswordRequest(
        @NotBlank @Email String email,
        @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{43}") String token,
        @NotBlank @Size(min = 15, max = 72, message = "Password must be between 15 and 72 characters") String password) {}
