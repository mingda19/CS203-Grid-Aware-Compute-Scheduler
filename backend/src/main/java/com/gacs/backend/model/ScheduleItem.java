package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One job's placement within a {@link ScheduleRun} (docs/schedule-explanation-design.md section
 * 1.3) - distinct from {@code com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem}, the
 * HTTP-facing record this entity is built from; different package, no collision.
 *
 * <p>Deliberately excludes the per-slot allocation breakdown (cpu/gpu hours, hour-by-hour shares):
 * that's execution detail the explanation never touches, not the decision being explained.
 */
@Entity
@Table(name = "schedule_items",
    uniqueConstraints = @UniqueConstraint(name = "uq_schedule_items_run_job", columnNames = {"schedule_run_id", "job_id"}),
    indexes = @Index(name = "idx_schedule_items_run", columnList = "schedule_run_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_run_id", nullable = false)
    private Long scheduleRunId;

    /** Not a hard FK to {@code jobs}: an inline optimize request can use a job id that was never
     *  CRUD-created, and must still persist cleanly. */
    @Column(name = "job_id", nullable = false)
    private Long jobId;

    @Column(name = "job_name", nullable = false, length = 255)
    private String jobName;

    @Column(name = "baseline_start", nullable = false)
    private LocalDateTime baselineStart;

    @Column(name = "scheduled_start")
    private LocalDateTime scheduledStart;

    @Column(name = "scheduled_end")
    private LocalDateTime scheduledEnd;

    @Column(name = "per_job_status", nullable = false, length = 30)
    private String perJobStatus;

    @Column(length = 500)
    private String reason;

    /** Nullable, and can be negative - the true optimum can raise one job's own cost to lower the
     *  fleet-wide total. Matches the existing DTO's documented behavior. */
    @Column(name = "baseline_cost_usd")
    private Double baselineCostUsd;

    @Column(name = "optimized_cost_usd")
    private Double optimizedCostUsd;

    @Column(name = "savings_usd")
    private Double savingsUsd;

    /** The per-job explanation text - filled by either the LLM or the deterministic fallback,
     *  same column either way. Null until an explanation has been generated.
     *
     *  <p>Security contract: may be LLM-generated text narrating this job's user-supplied
     *  {@code jobName} - treat as untrusted on display. Any future consumer rendering this in a
     *  web view must HTML-encode it (ordinary text interpolation, never raw/unencoded HTML
     *  insertion), the same rule every other free-text field in this system follows. */
    @Column(name = "why_this_placement", columnDefinition = "TEXT")
    private String whyThisPlacement;
}
