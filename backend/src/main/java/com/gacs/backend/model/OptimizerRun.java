package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One optimiser run: which solver and configuration produced it, and how it ended (GRID-140).
 * The id is the {@code runId} the optimise endpoint returns, so audit events can refer to it.
 */
@Entity
@Table(name = "optimizer_runs", indexes = {
    @Index(name = "idx_optimizer_runs_created_at", columnList = "created_at")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OptimizerRun {

    /** UUID v4 string, assigned by the caller. */
    @Id
    @Column(name = "id", nullable = false, updatable = false, length = 36)
    private String id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "solver_name", nullable = false, updatable = false, length = 100)
    private String solverName;

    @Column(name = "solver_version", nullable = false, updatable = false, length = 50)
    private String solverVersion;

    /** JSON snapshot of the {@code gacs.optimiser.*} properties the run used. */
    @Column(name = "solver_config", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String solverConfig;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, updatable = false, length = 30)
    private OptimizerRunStatus status;

    /** Null when the run produced no plan (timeout, failure). */
    @Column(name = "proven_optimal", updatable = false)
    private Boolean provenOptimal;
}
