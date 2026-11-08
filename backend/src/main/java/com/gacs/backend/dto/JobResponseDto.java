package com.gacs.backend.dto;

import com.gacs.backend.model.ConfirmationOutcome;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.model.WorkloadCategory;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Schema(description = "Compute workload entity representation")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class JobResponseDto {

    @Schema(description = "Unique numeric identifier of the job", example = "101")
    private Long id;

    @Schema(description = "Authenticated operator email who owns the job", example = "operator@datacenter.io")
    private String ownerEmail;

    @Schema(description = "Display name of the owner; set only in the admin listing of all jobs")
    private String ownerName;

    @Schema(description = "Run ID of the approved plan this job is in; null when it is in none. Such a job cannot be edited, only deleted.")
    private String approvedRunId;

    @Schema(description = "Workload name", example = "LLM Fine-Tuning Run #42")
    private String name;

    @Schema(description = "Optional free-text description; null when none")
    private String description;

    @Schema(description = "Total CPU core-hours required", example = "64.0")
    private Double cpuHours;

    @Schema(description = "Total GPU hours required", example = "16.0")
    private Double gpuHours;

    @Schema(description = "Baseline scheduled start time in UTC ISO-8601", example = "2026-10-20T10:00:00")
    private LocalDateTime originalStartTime;

    @Schema(description = "Hard completion deadline in UTC ISO-8601", example = "2026-10-21T18:00:00")
    private LocalDateTime deadline;

    @Schema(description = "Flexibility delay tolerance in hours", example = "12")
    private Integer flexibilityWindowHours;

    @Schema(description = "Flag indicating exemption from automatic rescheduling", example = "false")
    @JsonProperty("isExempt")
    private boolean isExempt;

    @Schema(description = "Optional natural language constraints or notes", example = "Can wait up to 12 hours for low price valley, finish by tomorrow evening")
    private String rawConstraints;

    @Schema(description = "Actual parser timestamp in UTC ISO-8601; null when unparsed", example = "2026-10-20T09:30:00")
    private LocalDateTime parsedAt;

    @Schema(description = "Decision on the latest parse; null when no parsing metadata is recorded", allowableValues = {"PENDING", "CONFIRMED", "REJECTED"})
    private ConfirmationOutcome confirmationOutcome;

    @Schema(description = "Operational status", example = "PENDING")
    private String status;

    @Schema(description = "Workload priority level", example = "NORMAL", allowableValues = {"CRITICAL", "HIGH", "NORMAL", "LOW"})
    private JobPriority priority;

    @Schema(description = "Workload category, matching the workload calendar's job types", example = "ML_TRAINING",
            allowableValues = {"ML_TRAINING", "CRYPTO_MINING", "HPC_BATCH", "HVAC_PRE_COOL", "GRID_ALERT"})
    private WorkloadCategory workloadCategory;

    @Schema(description = "Timestamp when the record was created in UTC ISO-8601", example = "2026-10-18T00:30:00")
    private LocalDateTime createdAt;

    @Schema(description = "Timestamp when the record was last updated in UTC ISO-8601", example = "2026-10-18T00:35:00")
    private LocalDateTime updatedAt;

    @JsonProperty("isExempt")
    public boolean isExempt() {
        return isExempt;
    }

    @JsonProperty("isExempt")
    public void setExempt(boolean exempt) {
        this.isExempt = exempt;
    }

    public static JobResponseDto fromEntity(Job job) {
        if (job == null) {
            return null;
        }
        return JobResponseDto.builder()
                .id(job.getId())
                .ownerEmail(job.getOwnerEmail())
                .name(job.getName())
                .description(job.getDescription())
                .cpuHours(job.getCpuHours())
                .gpuHours(job.getGpuHours())
                .originalStartTime(job.getOriginalStartTime())
                .deadline(job.getDeadline())
                .flexibilityWindowHours(job.getFlexibilityWindowHours())
                .isExempt(job.isExempt())
                .rawConstraints(job.getRawConstraints())
                .parsedAt(job.getParsedAt())
                .confirmationOutcome(job.getConfirmationOutcome())
                .status(job.getStatus())
                .priority(job.getPriority() != null ? job.getPriority() : JobPriority.NORMAL)
                .workloadCategory(job.getWorkloadCategory() != null ? job.getWorkloadCategory() : WorkloadCategory.ML_TRAINING)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
