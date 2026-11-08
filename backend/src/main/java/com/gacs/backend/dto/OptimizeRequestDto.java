package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gacs.backend.service.allocation.ConstraintType;
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
    List<CapacityOverrideInput> capacityOverrides,

    @Schema(description = "When true, a job whose originalStartTime has already passed may start "
        + "anywhere from the current hour up to the last slot that still lets it finish by its "
        + "deadline, instead of being reported NO_FEASIBLE_SLOT because its flexibility window is "
        + "anchored to a start time that no longer exists. Defaults to false, which keeps the "
        + "operator's stated window exactly. This widens placement beyond what was originally "
        + "specified, so it must be an explicit choice: the UI asks for confirmation, naming the "
        + "affected jobs, before sending it. It never weakens the rule that no slot may begin "
        + "before the current hour, and a job whose deadline has also passed stays infeasible.",
        example = "false", defaultValue = "false")
    Boolean rescheduleElapsedJobs,

    @Schema(description = "Ordering and exclusion rules between pairs of jobs in this run. Both job "
        + "ids must appear in jobs[]; a rule naming a job that is not in the run, or naming the same "
        + "job twice, is rejected rather than ignored. Optional; absent means no pair rules.")
    List<PairConstraintInput> pairConstraints
) {

    /** {@code pairConstraints} with its absent-means-empty default applied. */
    public List<PairConstraintInput> pairConstraintsOrEmpty() {
        return pairConstraints == null ? List.of() : pairConstraints;
    }

    /**
     * One rule binding two jobs in this run.
     *
     * <p>Both ends are job ids, never names. Names are not unique in this system, so a rule stated
     * by name could bind to a different job than the operator meant, and would break silently when
     * a job was renamed. Resolving words to an id happens before a request is built - see
     * {@code JobReferenceResolver}.
     *
     * @param type       {@code PRECEDENCE} (jobId must finish before otherJobId starts) or
     *                   {@code MUTUAL_EXCLUSION} (the two may never share an hour). Order matters
     *                   for the first and not for the second.
     * @param jobId      for PRECEDENCE, the job that must finish first
     * @param otherJobId for PRECEDENCE, the job that must start after
     */
    @Schema(description = "An ordering or exclusion rule between two jobs in this run")
    public record PairConstraintInput(
        @Schema(description = "PRECEDENCE or MUTUAL_EXCLUSION", example = "PRECEDENCE")
        ConstraintType type,
        @Schema(description = "For PRECEDENCE, the job that must finish first", example = "101")
        Long jobId,
        @Schema(description = "For PRECEDENCE, the job that must start afterwards", example = "102")
        Long otherJobId
    ) { }

    /**
     * Convenience for the default behaviour, and what every caller predating the elapsed-job option
     * means: keep each job's window exactly as specified.
     */
    public OptimizeRequestDto(List<JobInput> jobs, ClusterCapacityInput clusterCapacity, String forecastLocation,
                              Integer horizonHours, List<ForecastPointInput> forecast,
                              List<CapacityOverrideInput> capacityOverrides) {
        this(jobs, clusterCapacity, forecastLocation, horizonHours, forecast, capacityOverrides, false, List.of());
    }

    /** Convenience for callers predating pair constraints: no ordering or exclusion rules. */
    public OptimizeRequestDto(List<JobInput> jobs, ClusterCapacityInput clusterCapacity, String forecastLocation,
                              Integer horizonHours, List<ForecastPointInput> forecast,
                              List<CapacityOverrideInput> capacityOverrides, Boolean rescheduleElapsedJobs) {
        this(jobs, clusterCapacity, forecastLocation, horizonHours, forecast, capacityOverrides,
                rescheduleElapsedJobs, List.of());
    }

    /** {@code rescheduleElapsedJobs} with its absent-means-false default applied. */
    public boolean reschedulesElapsedJobs() {
        return Boolean.TRUE.equals(rescheduleElapsedJobs);
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
