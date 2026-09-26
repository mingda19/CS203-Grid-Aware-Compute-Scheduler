package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;

@Schema(description = "Request payload to update a user's authority role")
public class UpdateRoleRequest {

    @Schema(description = "New authority role to assign to the target user", example = "ROLE_ADMIN", allowableValues = {"ROLE_USER", "ROLE_ADMIN"}, requiredMode = Schema.RequiredMode.REQUIRED)
    @NotBlank(message = "Role is required")
    private String role;

    public UpdateRoleRequest() {
    }

    public UpdateRoleRequest(String role) {
        this.role = role;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
