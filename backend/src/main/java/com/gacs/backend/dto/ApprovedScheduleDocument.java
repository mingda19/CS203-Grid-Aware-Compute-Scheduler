package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.databind.JsonNode;
import io.swagger.v3.oas.annotations.media.Schema;

import java.math.BigDecimal;
import java.util.List;

/**
 * Approved-schedule JSON export (docs/api-contract.md section 6.6, GRID-135 AC2): the run's metadata, the
 * approval it was released under, and every job's approved time slots. Timestamps are pre-formatted UTC strings
 * (section 2.4) and numbers carry a fixed scale, so the document always serializes to the same text.
 */
@Schema(description = "Approved schedule export: run metadata, approval and per-job time slots")
@JsonPropertyOrder({"formatVersion", "run", "approval", "jobs"})
public record ApprovedScheduleDocument(
    @Schema(description = "Version of this export format", example = "1") int formatVersion,
    @Schema(description = "Run-level metadata") RunMetadata run,
    @Schema(description = "The decision that approved the schedule; null when none was supplied") Approval approval,
    @Schema(description = "Every job in the run exactly once, sorted by jobId") List<Job> jobs
) {

    public static final int FORMAT_VERSION = 1;

    @Schema(description = "Run-level metadata from the approved optimiser run and its stored run record")
    @JsonPropertyOrder({"runId", "status", "provenOptimal", "usedSeededFallbackPrices", "baselineCostUsd",
        "optimizedCostUsd", "projectedSavingsUsd", "projectedSavingsPercent", "plainEnglishExplanation",
        "createdAt", "solver"})
    public record RunMetadata(
        @Schema(example = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44") String runId,
        @Schema(description = "SCHEDULED or NEEDS_ATTENTION", example = "SCHEDULED") String status,
        @Schema(example = "true") boolean provenOptimal,
        @Schema(example = "false") boolean usedSeededFallbackPrices,
        @Schema(description = "4 decimal places", example = "1.0752") BigDecimal baselineCostUsd,
        @Schema(description = "4 decimal places", example = "0.2304") BigDecimal optimizedCostUsd,
        @Schema(description = "4 decimal places; may be negative", example = "0.8448") BigDecimal projectedSavingsUsd,
        @Schema(description = "2 decimal places", example = "78.57") BigDecimal projectedSavingsPercent,
        @Schema(description = "Run summary; may be null") String plainEnglishExplanation,
        @Schema(description = "When the run was stored, UTC, whole seconds; null without a run record",
            example = "2026-10-20T09:00:00Z") String createdAt,
        @Schema(description = "Solver that produced the schedule; null without a run record") Solver solver
    ) {}

    @Schema(description = "Solver name, version and the configuration snapshot the run used")
    @JsonPropertyOrder({"name", "version", "config"})
    public record Solver(
        @Schema(example = "HybridAllocationSolver") String name,
        @Schema(example = "1") String version,
        @Schema(description = "The gacs.optimiser.* settings as stored with the run; a JSON object, or the "
            + "stored text if it is not valid JSON") JsonNode config
    ) {}

    @Schema(description = "The audit event that approved the schedule (contract section 6.5)")
    @JsonPropertyOrder({"decisionId", "approvedBy", "approvedAt", "comment", "scheduleHash"})
    public record Approval(
        @Schema(description = "Audit event ID", example = "42") Long decisionId,
        @Schema(description = "Operator who approved", example = "operator@gacs.internal") String approvedBy,
        @Schema(description = "UTC, whole seconds", example = "2026-10-20T09:30:15Z") String approvedAt,
        @Schema(description = "Operator rationale") String comment,
        @Schema(description = "SHA-256 of the stored schedule recorded with the approval") String scheduleHash
    ) {}

    @Schema(description = "One job and its approved time slots")
    @JsonPropertyOrder({"jobId", "jobName", "perJobStatus", "reason", "baselineStart", "scheduledStart",
        "scheduledEnd", "allocatedCpuHours", "allocatedGpuHours", "baselineCostUsd", "optimizedCostUsd",
        "savingsUsd", "slots"})
    public record Job(
        @Schema(example = "101") Long jobId,
        @Schema(example = "LLM Fine-Tuning Run #42") String jobName,
        @Schema(description = "OPTIMISED, UNCHANGED_EXEMPT or NO_FEASIBLE_SLOT", example = "OPTIMISED") String perJobStatus,
        @Schema(description = "Why the job was not scheduled; null otherwise") String reason,
        @Schema(example = "2026-10-20T10:00:00Z") String baselineStart,
        @Schema(description = "Null for NO_FEASIBLE_SLOT", example = "2026-10-21T01:00:00Z") String scheduledStart,
        @Schema(description = "Null for NO_FEASIBLE_SLOT", example = "2026-10-21T03:00:00Z") String scheduledEnd,
        @Schema(description = "4 decimal places", example = "64.0000") BigDecimal allocatedCpuHours,
        @Schema(description = "4 decimal places", example = "16.0000") BigDecimal allocatedGpuHours,
        @Schema(description = "4 decimal places; null for NO_FEASIBLE_SLOT", example = "1.0752") BigDecimal baselineCostUsd,
        @Schema(description = "4 decimal places; null for NO_FEASIBLE_SLOT", example = "0.2304") BigDecimal optimizedCostUsd,
        @Schema(description = "4 decimal places; null for NO_FEASIBLE_SLOT", example = "0.8448") BigDecimal savingsUsd,
        @Schema(description = "Approved slots sorted by start; empty for NO_FEASIBLE_SLOT") List<Slot> slots
    ) {}

    @Schema(description = "Work a job does in one 1-hour slot")
    @JsonPropertyOrder({"intervalStartUtc", "intervalEndUtc", "cpuHours", "gpuHours"})
    public record Slot(
        @Schema(example = "2026-10-21T01:00:00Z") String intervalStartUtc,
        @Schema(example = "2026-10-21T02:00:00Z") String intervalEndUtc,
        @Schema(description = "4 decimal places", example = "21.3333") BigDecimal cpuHours,
        @Schema(description = "4 decimal places", example = "5.3333") BigDecimal gpuHours
    ) {}
}
