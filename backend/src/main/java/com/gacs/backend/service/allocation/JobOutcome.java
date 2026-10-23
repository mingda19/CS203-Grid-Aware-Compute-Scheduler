package com.gacs.backend.service.allocation;

import java.util.Objects;
import java.util.Optional;

/**
 * The result for one job.
 *
 * @param plan   where the job runs; {@code null} exactly when the status is {@link PerJobStatus#NO_FEASIBLE_SLOT}
 * @param reason short human-readable cause for {@link PerJobStatus#NO_FEASIBLE_SLOT}; {@code null} otherwise
 */
public record JobOutcome(OptimizerJob job, JobPlan plan, PerJobStatus status, String reason) {

    public JobOutcome {
        Objects.requireNonNull(job, "job");
        Objects.requireNonNull(status, "status");
        boolean scheduled = status != PerJobStatus.NO_FEASIBLE_SLOT;
        if (scheduled != (plan != null)) {
            throw new IllegalArgumentException("job " + job.label() + ": a plan is required exactly when the job is scheduled");
        }
        if (scheduled != (reason == null)) {
            throw new IllegalArgumentException("job " + job.label() + ": a reason is required exactly when the job is not scheduled");
        }
    }

    public Optional<JobPlan> planIfScheduled() {
        return Optional.ofNullable(plan);
    }

    public boolean scheduled() {
        return plan != null;
    }
}
