package com.gacs.backend.dto;

import com.gacs.backend.model.AppSettings;

public record AppSettingsDto(boolean allowSelfApproval) {
    public static AppSettingsDto of(AppSettings settings) {
        return new AppSettingsDto(settings.isAllowSelfApproval());
    }
}
