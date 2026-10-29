package com.gacs.backend.service.allocation;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Variable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Jobs {@code jobIdA} and {@code jobIdB} may never run in the same hour." Symmetric and
 * order-independent: whichever of the two is placed second always checks against the first's
 * actual placement, so (unlike {@link PrecedenceConstraint}) the greedy/heuristic solvers need no
 * special placement order for this type.
 *
 * <p>Greedy solver: enforced entirely by {@link #isSatisfiedBy} rejecting an overlapping candidate -
 * {@link #restrict} is left as the no-op default, since the "forbidden" hours are whichever the
 * other job happens to land on, not a fixed boundary a window can be narrowed to in advance.
 *
 * <p>Exact solver: one linear expression per hour either job could possibly run in.
 */
public final class MutualExclusionConstraint extends Constraint {

    private final long jobIdA;
    private final long jobIdB;

    public MutualExclusionConstraint(long jobIdA, long jobIdB) {
        this.jobIdA = jobIdA;
        this.jobIdB = jobIdB;
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.MUTUAL_EXCLUSION;
    }

    @Override
    public List<Long> jobIds() {
        return List.of(jobIdA, jobIdB);
    }

    @Override
    public boolean isSatisfiedBy(OptimizerJob job, JobPlan candidate, Map<Long, JobPlan> placedSoFar) {
        long otherId = job.id() == jobIdA ? jobIdB : job.id() == jobIdB ? jobIdA : -1;
        if (otherId == -1) {
            return true;
        }
        JobPlan other = placedSoFar.get(otherId);
        return other == null || !overlaps(candidate, other);
    }

    private static boolean overlaps(JobPlan a, JobPlan b) {
        return a.start().isBefore(b.end()) && b.start().isBefore(a.end());
    }

    @Override
    public void applyToExactModel(ExpressionsBasedModel model, RunningVariableLookup runningVariableOf) {
        for (LocalDateTime hour : runningVariableOf.hoursOf(jobIdA)) {
            Optional<Variable> runningA = runningVariableOf.runningAt(jobIdA, hour);
            Optional<Variable> runningB = runningVariableOf.runningAt(jobIdB, hour);
            if (runningA.isPresent() && runningB.isPresent()) {
                model.newExpression("mutex_" + jobIdA + "_" + jobIdB + "_" + hour)
                        .upper(1)
                        .set(runningA.get(), 1)
                        .set(runningB.get(), 1);
            }
        }
    }
}
