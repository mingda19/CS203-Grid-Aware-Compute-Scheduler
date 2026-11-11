package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

/**
 * Compute workload entity representing a flexible or deadline-tolerant job.
 * Designed per Jira GRID-112 and aligned with the optimizer contract in GRID-124.
 */
@Entity
@Table(name = "jobs", indexes = {
    @Index(name = "idx_jobs_owner_start", columnList = "owner_email, original_start_time"),
    @Index(name = "idx_jobs_owner_deadline", columnList = "owner_email, deadline")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Job {

    @Column(nullable = false, columnDefinition = "bigint default 0")
    private long schedulingRevision;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 1000)
    private String description;

    @Builder.Default
    @Column(name = "cpu_hours", nullable = false)
    private Double cpuHours = 0.0;

    @Builder.Default
    @Column(name = "gpu_hours", nullable = false)
    private Double gpuHours = 0.0;

    @Column(name = "original_start_time", nullable = false)
    private LocalDateTime originalStartTime;

    @Column(nullable = false)
    private LocalDateTime deadline;

    @Builder.Default
    @Column(name = "flexibility_window_hours", nullable = false)
    private Integer flexibilityWindowHours = 0;

    @Builder.Default
    @Column(name = "is_exempt_from_rescheduling", nullable = false)
    private boolean isExempt = false;

    @Column(name = "raw_constraints", length = 1000)
    private String rawConstraints;

    @Convert(converter = JobConstraintsConverter.class)
    @Column(name = "pair_constraints", columnDefinition = "TEXT")
    @Builder.Default
    private java.util.List<JobConstraint> pairConstraints = java.util.List.of();

    @Column(name = "parsed_at")
    private LocalDateTime parsedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "confirmation_outcome", length = 20)
    private ConfirmationOutcome confirmationOutcome;

    @Builder.Default
    @Column(nullable = false, length = 30)
    private String status = "PENDING";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'NORMAL'")
    private JobPriority priority = JobPriority.NORMAL;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(name = "workload_category", nullable = false, length = 20, columnDefinition = "varchar(20) default 'ML_TRAINING'")
    private WorkloadCategory workloadCategory = WorkloadCategory.ML_TRAINING;

    /**
     * Why the autorun could not plan this job at {@link #planErrorRevision}, or why the plan it got left it
     * out. Read only while the revision matches {@link #schedulingRevision}, so any edit that changes a
     * scheduling input clears it.
     */
    @Column(name = "plan_error", length = 500)
    private String planError;

    @Column(name = "plan_error_revision")
    private Long planErrorRevision;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        this.createdAt = now;
        this.updatedAt = now;
        if (this.cpuHours == null) {
            this.cpuHours = 0.0;
        }
        if (this.gpuHours == null) {
            this.gpuHours = 0.0;
        }
        if (this.flexibilityWindowHours == null) {
            this.flexibilityWindowHours = 0;
        }
        if (this.status == null || this.status.isBlank()) {
            this.status = "PENDING";
        }
        if (this.priority == null) {
            this.priority = JobPriority.NORMAL;
        }
        if (this.workloadCategory == null) {
            this.workloadCategory = WorkloadCategory.ML_TRAINING;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** True when {@link #planError} was recorded for the job as it is now. */
    public boolean hasCurrentPlanError() {
        return planError != null && planErrorRevision != null && planErrorRevision == schedulingRevision;
    }

    public void recordPlanError(String message, long revision) {
        planError = message == null ? null : message.substring(0, Math.min(500, message.length()));
        planErrorRevision = revision;
    }

    public void clearPlanError() {
        planError = null;
        planErrorRevision = null;
    }

    /** The earliest hour this job may start at - its original start, truncated to the hour. */
    public LocalDateTime earliestAllowedStart() {
        return originalStartTime.truncatedTo(ChronoUnit.HOURS);
    }

    /** The latest hour this job may start at. An exempt job may only start at its original time. */
    public LocalDateTime latestAllowedStart() {
        LocalDateTime earliest = earliestAllowedStart();
        return isExempt ? earliest : earliest.plusHours(flexibilityWindowHours);
    }
}
