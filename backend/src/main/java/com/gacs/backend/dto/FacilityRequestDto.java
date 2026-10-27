package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Request payload for creating or updating a compute facility / cluster hardware envelope")
public class FacilityRequestDto {

    @NotBlank(message = "Facility name must not be blank")
    @Size(max = 255, message = "Facility name must be at most 255 characters")
    @Schema(description = "User-facing facility or cluster identifier", example = "Austin Primary Cluster")
    private String name;

    @NotNull(message = "maxCpuHours is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "maxCpuHours must be greater than or equal to 0")
    @Schema(description = "Maximum concurrent CPU-hours per hourly slot", example = "256.0")
    private Double maxCpuHours;

    @NotNull(message = "maxGpuHours is required")
    @DecimalMin(value = "0.0", inclusive = true, message = "maxGpuHours must be greater than or equal to 0")
    @Schema(description = "Maximum concurrent GPU-hours per hourly slot", example = "64.0")
    private Double maxGpuHours;

    @NotBlank(message = "Location is required")
    @Size(max = 50, message = "Location must be at most 50 characters")
    @Schema(description = "ERCOT Settlement Point or Grid Node location", example = "LZ_NORTH")
    private String location;
}
