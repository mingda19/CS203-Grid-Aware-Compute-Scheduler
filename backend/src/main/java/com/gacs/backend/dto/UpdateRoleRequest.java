package com.gacs.backend.dto;

import jakarta.validation.constraints.NotBlank;

public class UpdateRoleRequest {

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
