package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.AppSettingsDto;
import com.gacs.backend.service.AppSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin Settings", description = "Deployment-wide settings. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/settings")
@PreAuthorize("hasRole('ADMIN')")
public class AdminSettingsController {

    private final AppSettingsService settings;

    public AdminSettingsController(AppSettingsService settings) {
        this.settings = settings;
    }

    @Operation(summary = "Read the deployment settings")
    @GetMapping
    public ApiResponse<AppSettingsDto> get() {
        return ApiResponse.ok("Settings retrieved", AppSettingsDto.of(settings.get()));
    }

    @Operation(summary = "Change the deployment settings",
            description = "allowSelfApproval lets a plan's submitter approve it. Admins can always approve their own plans.")
    @PutMapping
    public ApiResponse<AppSettingsDto> update(@RequestBody AppSettingsDto request) {
        return ApiResponse.ok("Settings updated", AppSettingsDto.of(settings.update(request.allowSelfApproval())));
    }
}
