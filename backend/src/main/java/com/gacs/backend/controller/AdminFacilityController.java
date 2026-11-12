package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.service.FacilityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.security.Principal;

@Tag(name = "Admin Facility", description = "Changes the shared facility. Requires ROLE_ADMIN.")
@SecurityRequirement(name = "bearerAuth")
@RestController
@RequestMapping("/api/admin/facility")
@PreAuthorize("hasRole('ADMIN')")
public class AdminFacilityController {

    private final FacilityService facilityService;

    public AdminFacilityController(FacilityService facilityService) {
        this.facilityService = facilityService;
    }

    @Operation(
            summary = "Update the shared facility",
            description = "Sets the CPU and GPU limits per hourly slot and the grid location for every user. "
                    + "A change to capacity or location ends the approval of every approved plan."
    )
    @PutMapping
    public ResponseEntity<ApiResponse<FacilityResponseDto>> update(
            @Valid @RequestBody FacilityRequestDto request, Principal principal) {
        return ResponseEntity.ok(ApiResponse.ok("Facility configuration updated successfully",
                facilityService.updateFacility(request, principal.getName())));
    }
}
