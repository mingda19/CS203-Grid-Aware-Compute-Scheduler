package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.dto.FacilityUtilizationDto;
import com.gacs.backend.service.FacilityService;
import com.gacs.backend.service.FacilityUtilizationService;
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
    private final FacilityUtilizationService utilizationService;

    public FacilityController(FacilityService facilityService, FacilityUtilizationService utilizationService) {
        this.facilityService = facilityService;
        this.utilizationService = utilizationService;
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
            summary = "Get scheduled CPU and GPU use for the current hour",
            description = "Sums the CPU-hours and GPU-hours that all live approved plans schedule in the current UTC hour, "
                    + "against the facility limits per hourly slot. This is scheduled use, not measured use. "
                    + "The call can change stored plan states, because it runs the validity gate on each approved plan "
                    + "and records every plan that is no longer valid."
    )
    @ApiResponses(value = {
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "200",
                    description = "Utilization calculated successfully.",
                    content = @Content(schema = @Schema(implementation = ApiResponse.class))
            ),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(
                    responseCode = "401",
                    description = "Unauthorized: Invalid or missing JWT access token."
            )
    })
    @GetMapping("/me/utilization")
    public ResponseEntity<ApiResponse<FacilityUtilizationDto>> getCurrentUtilization(Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Facility utilization calculated successfully",
                utilizationService.current(principal.getName())));
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
