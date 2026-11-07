package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The summary shown above a plan, stored per run.
 *
 * <p>Written once, deterministically, from the run's own figures - see
 * {@code ScheduleOptimizationService.explain}. It is persisted rather than regenerated on read
 * because it is part of the audit record: an approval has to be reconstructable as the operator
 * saw it, and a later change to the wording must not retroactively alter what was approved
 * (contract FR-34).
 *
 * <p>No longer carries provenance columns. An earlier design generated this text with an LLM and
 * recorded which path produced it, the model name, the raw provider response and any validation
 * errors; with one source of text those columns only ever held constants.
 *
 * <p>{@code headline} and {@code narrative} are still free text and must be HTML-encoded by any
 * view that renders them - ordinary JSX interpolation, never raw-HTML insertion.
 */
@Entity
@Table(name = "schedule_explanations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_run_id", nullable = false, unique = true)
    private Long scheduleRunId;

    /** See the class-level security contract above. */
    @Column(nullable = false, length = 200)
    private String headline;

    /** See the class-level security contract above. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String narrative;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
