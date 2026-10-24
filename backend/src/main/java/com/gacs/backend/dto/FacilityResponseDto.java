package com.gacs.backend.dto;

import com.gacs.backend.model.Facility;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Schema(description = "Response payload representing a registered compute facility / cluster configuration")
public class FacilityResponseDto {

    @Schema(description = "Primary key ID", example = "1")
    private Long id;

    @Schema(description = "Owner email address", example = "operator@datacenter.io")
    private String ownerEmail;

    @Schema(description = "Facility or cluster identifier name", example = "Austin Primary Cluster")
    private String name;

    @Schema(description = "Maximum concurrent CPU-hours per hourly slot", example = "256.0")
    private Double maxCpuHours;

    @Schema(description = "Maximum concurrent GPU-hours per hourly slot", example = "64.0")
    private Double maxGpuHours;

    @Schema(description = "Default grid settlement location", example = "LZ_NORTH")
    private String location;

    @Schema(description = "Last update timestamp")
    private LocalDateTime updatedAt;

    public static FacilityResponseDto fromEntity(Facility facility) {
        if (facility == null) {
            return null;
        }
        return FacilityResponseDto.builder()
                .id(facility.getId())
                .ownerEmail(facility.getOwnerEmail())
                .name(facility.getName())
                .maxCpuHours(facility.getMaxCpuHours())
                .maxGpuHours(facility.getMaxGpuHours())
                .location(facility.getLocation())
                .updatedAt(facility.getUpdatedAt())
                .build();
    }
}
