package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response payload for {@code POST /api/schedules/optimize} (docs/api-contract.md section 6.4), inside the
 * standard {@link ApiResponse} envelope. Timestamps are UTC with a {@code Z} suffix (section 2.4).
 *
 * <p>Costs are rounded to 4 decimal places. Per-job costs are rounded first, and the run totals are sums of
 * those rounded figures, so every number shown adds up exactly. Savings may be negative: each job's baseline
 * prices it alone on the cluster, so jobs that cannot all run at their original times can cost more.
 */
@Schema(description = "Optimized schedule plan")
public record OptimizeResponseDto(

    @Schema(description = "UUID v4 generated per run, not stored yet", example = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44")
    String runId,

    @Schema(description = "SCHEDULED when every job is scheduled or exempt; NEEDS_ATTENTION when at least one "
        + "job is NO_FEASIBLE_SLOT", example = "SCHEDULED")
    String status,

    @Schema(description = "True when the solver proved no cheaper schedule exists; false when a heuristic "
        + "produced the plan, which still meets every rule", example = "true")
    boolean provenOptimal,

    @Schema(description = "Baseline cost of the scheduled jobs (NO_FEASIBLE_SLOT jobs excluded)", example = "1.0752")
    double baselineCostUsd,

    @Schema(description = "Cost of the proposed schedule (NO_FEASIBLE_SLOT jobs excluded)", example = "0.2304")
    double optimizedCostUsd,

    @Schema(description = "baselineCostUsd minus optimizedCostUsd; may be negative", example = "0.8448")
    double projectedSavingsUsd,

    @Schema(description = "Savings as a percentage of baselineCostUsd, 2 decimal places; 0 when the baseline "
        + "is 0", example = "78.57")
    double projectedSavingsPercent,

    @Schema(description = "Plain-English summary of the run")
    String plainEnglishExplanation,

    @Schema(description = "True when no stored forecast covered the window and the history-based fallback "
        + "price curve was used")
    boolean usedSeededFallbackPrices,

    @Schema(description = "Every input job exactly once, in request order")
    List<ScheduleItem> items
) {

    public static final String STATUS_SCHEDULED = "SCHEDULED";
    public static final String STATUS_NEEDS_ATTENTION = "NEEDS_ATTENTION";

    /** Response timestamps: UTC, with the {@code Z} suffix the contract uses. */
    static final String UTC_PATTERN = "yyyy-MM-dd'T'HH:mm:ss'Z'";

    @Schema(description = "Result for one job")
    public record ScheduleItem(
        @Schema(description = "Job id from the request", example = "101") Long jobId,
        @Schema(description = "Job name from the request", example = "LLM Fine-Tuning Run #42") String jobName,
        @Schema(description = "The job's originalStartTime", example = "2026-10-20T10:00:00Z")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = UTC_PATTERN) LocalDateTime baselineStart,
        @Schema(description = "Start of the first slot used; null for NO_FEASIBLE_SLOT", example = "2026-10-21T01:00:00Z")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = UTC_PATTERN) LocalDateTime scheduledStart,
        @Schema(description = "End of the last slot used; null for NO_FEASIBLE_SLOT", example = "2026-10-21T02:00:00Z")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = UTC_PATTERN) LocalDateTime scheduledEnd,
        @Schema(description = "CPU-hours allocated over all slots; 0 for NO_FEASIBLE_SLOT", example = "64.0")
        double allocatedCpuHours,
        @Schema(description = "GPU-hours allocated over all slots; 0 for NO_FEASIBLE_SLOT", example = "16.0")
        double allocatedGpuHours,
        @Schema(description = "Slots used and the hours taken in each; empty for NO_FEASIBLE_SLOT")
        List<Allocation> allocations,
        @Schema(description = "OPTIMISED, UNCHANGED_EXEMPT or NO_FEASIBLE_SLOT", example = "OPTIMISED")
        String perJobStatus,
        @Schema(description = "Why the job was not scheduled; null otherwise")
        String reason,
        @Schema(description = "Cost of the job's baseline; null for NO_FEASIBLE_SLOT", example = "1.0752")
        Double baselineCostUsd,
        @Schema(description = "Cost of the job's scheduled plan; null for NO_FEASIBLE_SLOT", example = "0.2304")
        Double optimizedCostUsd,
        @Schema(description = "baselineCostUsd minus optimizedCostUsd; may be negative; null for NO_FEASIBLE_SLOT",
            example = "0.8448")
        Double savingsUsd
    ) {}

    @Schema(description = "Work done by a job in one slot")
    public record Allocation(
        @Schema(description = "Slot start", example = "2026-10-21T01:00:00Z")
        @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = UTC_PATTERN) LocalDateTime intervalStartUtc,
        @Schema(description = "CPU-hours in this slot", example = "64.0") double cpuHours,
        @Schema(description = "GPU-hours in this slot", example = "16.0") double gpuHours
    ) {}
}
