package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

/**
 * One optimiser run: which solver and configuration produced it (GRID-140). The id is the
 * {@code runId} the optimise endpoint returns, so audit events can refer to it.
 *
 * <p>Deliberately holds no {@code status}/{@code provenOptimal}: {@code ScheduleRun} (written by
 * the same {@code /optimize} call, keyed by the same run id) already owns both, and nothing about
 * this table's own purpose - solver provenance for the audit trail - needs a second copy. Any
 * reader that needs a run's status/provenOptimal (e.g. {@code AuditEventService}) looks it up from
 * {@code ScheduleRun} by the shared id. Known gap, not addressed here: a run that fails before
 * {@code ScheduleRun} is ever persisted (solver exception, timeout) has no row in either table
 * today, so {@code OptimizerRunStatus}'s INFEASIBLE/TIMEOUT/FAILED values are not currently
 * reachable from any code path - unrelated to this table's own field set.
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
}
