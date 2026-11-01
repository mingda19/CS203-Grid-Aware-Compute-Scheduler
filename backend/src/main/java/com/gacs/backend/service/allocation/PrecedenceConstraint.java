package com.gacs.backend.service.allocation;

import org.ojalgo.optimisation.ExpressionsBasedModel;
import org.ojalgo.optimisation.Variable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * "Job {@code beforeJobId} must finish before job {@code afterJobId} starts."
 *
 * <p>Greedy solver: {@link #restrict} only has an effect once the before-job is in
 * {@code placedSoFar} - until then it is a no-op, which is why the solvers that use it must place
 * the before-job first (see {@code AnnealingAllocationSolver}'s topological ordering and
 * swap-rejection, driven by {@link #beforeJobId()}/{@link #afterJobId()}).
 *
 * <p>Exact solver: {@link #applyToExactModel} forbids the after-job from running at any hour at or
 * after an hour the before-job is running at - i.e. the after-job cannot start until the before-job
 * is fully done. Checking this at every hour the after-job could run (not only its candidate starts)
 * is deliberately redundant but still correct: if the after-job's start already satisfies the rule,
 * every later hour it runs in does too, since the before-job's hours only get further in the past.
 *
 * <p>Deliberate edge case: a before-job that ends up {@link PerJobStatus#NO_FEASIBLE_SLOT} imposes no
 * restriction on its after-job at all - both hooks and {@code applyToExactModel}'s pairwise
 * expressions only bind once the before-job is actually running. "Must finish before" only
 * constrains the after-job relative to a before-job that actually runs; it does not cascade into
 * also failing the after-job just because the before-job itself could not be placed.
 */
public final class PrecedenceConstraint extends Constraint {

    private final long beforeJobId;
    private final long afterJobId;

    public PrecedenceConstraint(long beforeJobId, long afterJobId) {
        this.beforeJobId = beforeJobId;
        this.afterJobId = afterJobId;
    }

    public long beforeJobId() {
        return beforeJobId;
    }

    public long afterJobId() {
        return afterJobId;
    }

    @Override
    public ConstraintType type() {
        return ConstraintType.PRECEDENCE;
    }

    @Override
    public List<Long> jobIds() {
        return List.of(beforeJobId, afterJobId);
    }

    @Override
    public JobWindow restrict(OptimizerJob job, JobWindow window, Map<Long, JobPlan> placedSoFar) {
        if (job.id() != afterJobId) {
            return window;
        }
        JobPlan beforePlan = placedSoFar.get(beforeJobId);
        if (beforePlan == null) {
            return window;
        }
        LocalDateTime earliestStart = beforePlan.end();
        return window.restrictedTo(hour -> !hour.isBefore(earliestStart));
    }

    @Override
    public boolean isSatisfiedBy(OptimizerJob job, JobPlan candidate, Map<Long, JobPlan> placedSoFar) {
        if (job.id() != afterJobId) {
            return true;
        }
        JobPlan beforePlan = placedSoFar.get(beforeJobId);
        return beforePlan == null || !candidate.start().isBefore(beforePlan.end());
    }

    @Override
    public void applyToExactModel(ExpressionsBasedModel model, RunningVariableLookup runningVariableOf) {
        List<LocalDateTime> beforeHours = runningVariableOf.hoursOf(beforeJobId);
        for (LocalDateTime afterHour : runningVariableOf.hoursOf(afterJobId)) {
            Optional<Variable> afterRunning = runningVariableOf.runningAt(afterJobId, afterHour);
            if (afterRunning.isEmpty()) {
                continue;
            }
            for (LocalDateTime beforeHour : beforeHours) {
                if (beforeHour.isBefore(afterHour)) {
                    continue;
                }
                Optional<Variable> beforeRunning = runningVariableOf.runningAt(beforeJobId, beforeHour);
                if (beforeRunning.isPresent()) {
                    model.newExpression("precedence_" + beforeJobId + "_" + afterJobId + "_" + afterHour + "_"
                                    + beforeHour)
                            .upper(1)
                            .set(afterRunning.get(), 1)
                            .set(beforeRunning.get(), 1);
                }
            }
        }
    }
}
