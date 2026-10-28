package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Request body for {@code POST /api/schedules/optimize} (docs/api-contract.md section 6.4). Validated by
 * {@link com.gacs.backend.service.allocation.OptimizationProblemFactory}, not by bean validation, so every
 * rule and its message live in one place.
 *
 * <p>Timestamps are UTC and may be sent with or without a {@code Z} suffix.
 */
@Schema(description = "Optimization run request")
public record OptimizeRequestDto(

    @Schema(description = "Jobs to place. Required, with at least one job; ids must be unique.")
    List<JobInput> jobs,

    @Schema(description = "Per-slot capacity ceilings. Defaults to 256.0 CPU-hours and 64.0 GPU-hours.")
    ClusterCapacityInput clusterCapacity,

    @Schema(description = "Settlement location whose stored forecast to use when forecast[] is not sent",
        example = "LZ_NORTH")
    String forecastLocation,

    @Schema(description = "Hours of forecast to load from the earliest originalStartTime, 1-168, default 48; "
        + "ignored when forecast[] is sent", example = "48")
    Integer horizonHours,

    @Schema(description = "Explicit price per hourly slot. When sent it wins over forecastLocation and "
        + "makes the run fully reproducible.")
    List<ForecastPointInput> forecast,

    @Schema(description = "Per-slot capacity; takes precedence over clusterCapacity for that slot")
    List<CapacityOverrideInput> capacityOverrides
) {

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
        @JsonProperty("isExempt") Boolean isExempt,
        @Schema(description = "Workload priority level", example = "NORMAL", allowableValues = {"CRITICAL", "HIGH", "NORMAL", "LOW"})
        String priority
    ) {
        public JobInput(
            Long id,
            String name,
            Double cpuHours,
            Double gpuHours,
            LocalDateTime originalStartTime,
            LocalDateTime deadline,
            Integer flexibilityWindowHours,
            Boolean isExempt
        ) {
            this(id, name, cpuHours, gpuHours, originalStartTime, deadline, flexibilityWindowHours, isExempt, "NORMAL");
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
