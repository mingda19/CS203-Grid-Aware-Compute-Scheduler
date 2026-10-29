package com.gacs.backend.service.allocation;

import java.util.List;
import java.util.Objects;

/**
 * "Every job in {@code jobIds} must finish at the same hour." The one type that cannot be resolved
 * by placing jobs one at a time (the first member placed would fix the group's finish hour before
 * anyone knows whether the rest can actually reach it) or added to the exact model as ordinary
 * linear expressions over already-built per-job variables - both solvers instead run
 * {@link SynchronizedGroupPlacer} as a dedicated pre-pass, before a member's job is ever handed to
 * the normal per-job search/model. Consequently this class carries no {@code restrict}/
 * {@code isSatisfiedBy}/{@code applyToExactModel} logic of its own - {@link SynchronizedGroupPlacer}
 * reads {@link #jobIds()} directly instead.
 */
public final class SynchronizedFinishConstraint extends Constraint {

    private final List<Long> jobIds;

    public SynchronizedFinishConstraint(List<Long> jobIds) {
        if (jobIds.size() < 2) {
            throw new IllegalArgumentException("a synchronized-finish group needs at least 2 jobs");
        }
        this.jobIds = List.copyOf(jobIds);
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.SYNCHRONIZED_FINISH;
    }

    @Override
    public List<Long> jobIds() {
        return jobIds;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof SynchronizedFinishConstraint other && jobIds.equals(other.jobIds);
    }

    @Override
    public int hashCode() {
        return Objects.hash(jobIds);
    }
}
