package com.gacs.backend.dto;

import com.gacs.backend.model.User;
import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "User account profile representation")
public class UserDto {

    @Schema(description = "Unique numeric identifier of the user", example = "1")
    private Long id;

    @Schema(description = "User's registered email address", example = "operator@datacenter.io")
    private String email;

    @Schema(description = "User's full display name", example = "Alex Morgan")
    private String fullName;

    @Schema(description = "Assigned security role authority", example = "ROLE_USER", allowableValues = {"ROLE_USER", "ROLE_ADMIN"})
    private String role;

    @Schema(description = "Indicates whether the account has verified email ownership via OTP", example = "true")
    private boolean isVerified;

    public UserDto() {
    }

    public UserDto(User user) {
        this.id = user.getId();
        this.email = user.getEmail();
        this.fullName = user.getFullName();
        this.role = user.getRole();
        this.isVerified = user.isVerified();
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
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

    @com.fasterxml.jackson.annotation.JsonProperty("isVerified")
    public boolean isVerified() {
        return isVerified;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("isVerified")
    public void setVerified(boolean verified) {
        this.isVerified = verified;
    }

    @com.fasterxml.jackson.annotation.JsonProperty("verified")
    public boolean getVerified() {
        return isVerified;
    }
}
