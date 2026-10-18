package com.gacs.backend.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Response payload for {@code POST /api/schedules/optimize}, per the Optimizer Output
 * Contract in {@code docs/api-contract.md} (GRID-124 AC4, AC5). Delivered inside the
 * standard {@link ApiResponse} envelope.
 *
 * <p>All dollar figures are rounded to cent precision, and the summary totals are
 * derived from the already-rounded per-job figures so the reported baseline, optimized
 * and savings values always reconcile.
 */
@Schema(description = "Optimized schedule plan")
public record OptimizeResponseDto(

    @Schema(description = "Identifier for this optimization run", example = "run-a1b2c3d4-e5f6-7890-abcd-1234567890ab")
    String runId,

    @Schema(description = "Outcome of the run", example = "OPTIMAL")
    String status,

    @Schema(description = "Total cost of leaving every job at its original time", example = "4120.00")
    double baselineCostUsd,

    @Schema(description = "Total cost of the proposed schedule", example = "3140.25")
    double optimizedCostUsd,

    @Schema(description = "Baseline cost minus optimized cost", example = "979.75")
    double projectedSavingsUsd,

    @Schema(description = "Savings as a percentage of baseline cost", example = "23.78")
    double projectedSavingsPercent,

    @Schema(description = "Operator-facing summary of what the run changed and why")
    String plainEnglishExplanation,

    @Schema(description = "True when stored forecasts were unavailable and the seeded "
        + "fallback price curve was used for both baseline and optimized costs")
    boolean usedSeededFallbackPrices,

    @Schema(description = "Per-job placement decisions")
    List<ScheduleItem> items
) {

    /** The stub solver reports a completed run as OPTIMAL, the only value the contract documents. */
    public static final String STATUS_OPTIMAL = "OPTIMAL";

    @Schema(description = "Placement decision for one job")
    public record ScheduleItem(
        @Schema(description = "Job identifier", example = "101") Long jobId,
        @Schema(description = "Job name", example = "LLM Fine-Tuning Run #42") String jobName,
        @Schema(description = "Slot the job would have run in unchanged", example = "2026-10-20T10:00:00")
        LocalDateTime baselineStart,
        @Schema(description = "Slot the job is scheduled into", example = "2026-10-21T01:00:00")
        LocalDateTime scheduledStart,
        @Schema(description = "End of the scheduled slot, always one hour after the start", example = "2026-10-21T02:00:00")
        LocalDateTime scheduledEnd,
        @Schema(description = "CPU core-hours allocated", example = "64.0") double allocatedCpuHours,
        @Schema(description = "GPU hours allocated", example = "16.0") double allocatedGpuHours,
        @Schema(description = "Why this job ended up where it did", example = "RESCHEDULED")
        String perJobStatus,
        @Schema(description = "Cost of this job at its baseline slot", example = "4.12") double baselineCostUsd,
        @Schema(description = "Cost of this job at its scheduled slot", example = "3.14") double optimizedCostUsd,
        @Schema(description = "Baseline cost minus optimized cost for this job", example = "0.98") double savingsUsd
    ) {}
}
