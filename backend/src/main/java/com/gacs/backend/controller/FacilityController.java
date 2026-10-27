package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Tag(name = "Facility & Cluster Management", description = "Endpoints for configuring facility and cluster hardware capacity envelopes.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/facilities")
public class FacilityController {

    private final FacilityService facilityService;

    public FacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @Operation(
            summary = "Get current facility configuration",
            description = "Retrieves the cluster capacity envelope and grid node for the authenticated operator. Creates a standard 256 CPU / 64 GPU default if none exists."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Facility configuration retrieved successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            )
    })
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<FacilityResponseDto>> getCurrentFacility(Principal principal) {
        FacilityResponseDto facility = facilityService.getOrCreateFacility(principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Facility configuration retrieved successfully", facility));
    }

    @Operation(
            summary = "Update current facility configuration",
            description = "Updates the cluster capacity envelope (CPU/GPU limits per slot) and grid location for the authenticated operator."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Facility configuration updated successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "400",
                    description = "Validation failed: invalid capacity or missing required fields."
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            )
    })
    @PutMapping("/me")
    public ResponseEntity<ApiResponse<FacilityResponseDto>> updateCurrentFacility(
            @Valid @RequestBody FacilityRequestDto request,
            Principal principal
    ) {
        FacilityResponseDto updated = facilityService.updateFacility(request, principal.getName());
        return ResponseEntity.ok(ApiResponse.ok("Facility configuration updated successfully", updated));
    }
}
