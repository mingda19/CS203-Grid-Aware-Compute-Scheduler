package com.gacs.backend.service.allocation;

import java.util.List;
import java.util.Objects;

/**
 * "Every job in {@code jobIds} must finish at the same hour." The one declared constraint type that
 * cannot be resolved by placing jobs one at a time (the first member placed would fix the group's
 * finish hour before anyone knows whether the rest can actually reach it) or added to the exact
 * model as ordinary linear expressions over already-built per-job variables - both solvers instead
 * run {@link SynchronizedGroupPlacer} as a dedicated pre-pass, before a member's job is ever handed
 * to the normal per-job search/model.
 *
 * <p>Deliberately does not implement {@link PlacementConstraint}: that interface's hooks
 * ({@code restrict}/{@code isSatisfiedBy}/{@code applyToExactModel}) are called by the solvers
 * while placing jobs, and this type is never resolved that way, so it would have nothing to
 * implement them with. {@link SynchronizedGroupPlacer} reads {@link #jobIds()} directly instead,
 * and {@link OptimizationProblem} keeps this type's declarations in their own list
 * ({@link OptimizationProblem#synchronizedGroups()}) rather than mixed into {@code constraints()}.
 */
public final class SynchronizedFinishConstraint {

    private final List<Long> jobIds;

    public SynchronizedFinishConstraint(List<Long> jobIds) {
        if (jobIds.size() < 2) {
            throw new IllegalArgumentException("a synchronized-finish group needs at least 2 jobs");
        }
        this.jobIds = List.copyOf(jobIds);
    }

    public ConstraintType type() {
        return ConstraintType.SYNCHRONIZED_FINISH;
    }

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
