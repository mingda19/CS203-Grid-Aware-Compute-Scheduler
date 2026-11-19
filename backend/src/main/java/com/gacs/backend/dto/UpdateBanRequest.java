package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;

@Schema(description = "Request payload to ban or unban a user")
public class UpdateBanRequest {

    @Schema(description = "True to ban the user, false to unban", example = "true", requiredMode = Schema.RequiredMode.REQUIRED)
    @NotNull(message = "banned is required")
    private Boolean banned;

    public UpdateBanRequest() {
    }

    public UpdateBanRequest(Boolean banned) {
        this.banned = banned;
    }

    public Boolean getBanned() {
        return banned;
    }

    public void setBanned(Boolean banned) {
        this.banned = banned;
    }
}
