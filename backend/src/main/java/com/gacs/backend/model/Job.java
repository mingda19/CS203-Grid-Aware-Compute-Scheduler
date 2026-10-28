package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

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

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Column(nullable = false, length = 255)
    private String name;

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

    @Builder.Default
    @Column(nullable = false, length = 30)
    private String status = "PENDING";

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, columnDefinition = "varchar(20) default 'NORMAL'")
    private JobPriority priority = JobPriority.NORMAL;

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
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
