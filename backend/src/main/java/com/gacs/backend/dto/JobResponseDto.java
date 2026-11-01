package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
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

    @Schema(description = "Workload name", example = "LLM Fine-Tuning Run #42")
    private String name;

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

    @Schema(description = "Operational status", example = "PENDING")
    private String status;

    @Schema(description = "Workload priority level", example = "NORMAL", allowableValues = {"CRITICAL", "HIGH", "NORMAL", "LOW"})
    private JobPriority priority;

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
                .cpuHours(job.getCpuHours())
                .gpuHours(job.getGpuHours())
                .originalStartTime(job.getOriginalStartTime())
                .deadline(job.getDeadline())
                .flexibilityWindowHours(job.getFlexibilityWindowHours())
                .isExempt(job.isExempt())
                .rawConstraints(job.getRawConstraints())
                .status(job.getStatus())
                .priority(job.getPriority() != null ? job.getPriority() : JobPriority.NORMAL)
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }
}
