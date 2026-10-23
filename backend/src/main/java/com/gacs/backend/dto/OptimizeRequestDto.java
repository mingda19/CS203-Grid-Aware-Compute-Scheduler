package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request body for {@code POST /api/schedules/optimize}, per the Optimizer Input
 * Contract in {@code docs/api-contract.md} (GRID-124).
 *
 * <p>Timestamps are UTC ISO local date-times without an offset suffix, matching every
 * other endpoint in this service.
 */
@Schema(description = "Optimization run request")
public record OptimizeRequestDto(

    @Schema(description = "Jobs to optimize. Omit the field entirely to optimize the "
        + "authenticated operator's own stored jobs instead.")
    List<JobInput> jobs,

    @Schema(description = "Per-slot capacity ceilings. Defaults to 256.0 CPU-hours and 64.0 GPU-hours.")
    ClusterCapacityInput clusterCapacity,

    @Schema(description = "Settlement location whose price forecast to optimize against", example = "LZ_NORTH")
    String forecastLocation,

    @Schema(description = "Length of the optimization window in hours, 1-168", example = "48")
    Integer horizonHours,

    @Schema(description = "Explicit price per hourly slot. When sent it wins over forecastLocation and "
        + "makes the run fully reproducible.")
    List<ForecastPointInput> forecast,

    @Schema(description = "Per-slot capacity; takes precedence over clusterCapacity for that slot")
    List<CapacityOverrideInput> capacityOverrides
) {

    /** The request without explicit prices or per-slot capacity (the shape used before the work-based contract). */
    public OptimizeRequestDto(List<JobInput> jobs, ClusterCapacityInput clusterCapacity, String forecastLocation,
                              Integer horizonHours) {
        this(jobs, clusterCapacity, forecastLocation, horizonHours, null, null);
    }

    public static final String DEFAULT_FORECAST_LOCATION = "LZ_NORTH";
    public static final int DEFAULT_HORIZON_HOURS = 48;
    public static final int MIN_HORIZON_HOURS = 1;
    public static final int MAX_HORIZON_HOURS = 168;

    public String forecastLocationOrDefault() {
        return (forecastLocation == null || forecastLocation.isBlank())
            ? DEFAULT_FORECAST_LOCATION
            : forecastLocation.trim();
    }

    public int horizonHoursOrDefault() {
        return horizonHours == null ? DEFAULT_HORIZON_HOURS : horizonHours;
    }

    @Schema(description = "One job submitted for placement")
    public record JobInput(
        @Schema(description = "Job identifier", example = "101") Long id,
        @Schema(description = "Job name", example = "LLM Fine-Tuning Run #42") String name,
        @Schema(description = "Total CPU core-hours required", example = "64.0") Double cpuHours,
        @Schema(description = "Total GPU hours required", example = "16.0") Double gpuHours,
        @Schema(description = "Baseline start time, UTC", example = "2026-10-20T10:00:00") LocalDateTime originalStartTime,
        @Schema(description = "Hard completion deadline, UTC", example = "2026-10-21T18:00:00") LocalDateTime deadline,
        @Schema(description = "Delay tolerance in hours", example = "12") Integer flexibilityWindowHours,
        @Schema(description = "When true the job is pinned to its original start", example = "false")
        @JsonProperty("isExempt") Boolean isExempt
    ) {
        public double cpuHoursOrZero() {
            return cpuHours == null ? 0.0 : cpuHours;
        }

        public double gpuHoursOrZero() {
            return gpuHours == null ? 0.0 : gpuHours;
        }

        public int flexibilityWindowHoursOrZero() {
            return flexibilityWindowHours == null ? 0 : flexibilityWindowHours;
        }

        public boolean exempt() {
            return Boolean.TRUE.equals(isExempt);
        }
    }

    @Schema(description = "Price for one hourly slot")
    public record ForecastPointInput(
        @Schema(description = "Slot start, UTC, on the hour", example = "2026-10-20T10:00:00") LocalDateTime intervalStartUtc,
        @Schema(description = "Price in $/MWh", example = "112.0") Double predictedPrice
    ) {}

    @Schema(description = "Capacity for one hourly slot")
    public record CapacityOverrideInput(
        @Schema(description = "Slot start, UTC, on the hour", example = "2026-10-20T10:00:00") LocalDateTime intervalStartUtc,
        @Schema(description = "CPU-hours available in that slot", example = "128.0") Double maxCpuHours,
        @Schema(description = "GPU-hours available in that slot", example = "32.0") Double maxGpuHours
    ) {}

    @Schema(description = "Per-slot cluster capacity ceilings")
    public record ClusterCapacityInput(
        @Schema(description = "Maximum concurrent CPU core-hours per slot", example = "256.0")
        Double maxConcurrentCpuHours,
        @Schema(description = "Maximum concurrent GPU hours per slot", example = "64.0")
        Double maxConcurrentGpuHours
    ) {}
}
