package com.gacs.backend.dto;

import com.gacs.backend.model.User;

public class UserDto {
    private Long id;
    private String email;
    private String fullName;
    private String role;
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
