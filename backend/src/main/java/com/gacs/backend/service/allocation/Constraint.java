package com.gacs.backend.service.allocation;

import org.ojalgo.optimisation.ExpressionsBasedModel;

import java.util.List;
import java.util.Map;

/**
 * A hard constraint over one or more jobs (docs/schedule-explanation-design.md section 2). Every
 * constraint, when attached to a job, is hard: a job this constraint cannot be satisfied for is
 * {@link PerJobStatus#NO_FEASIBLE_SLOT}, never a soft trade-off.
 *
 * <p>The two solvers use these hooks differently:
 * <ul>
 *   <li>{@link GreedyAllocator} (and the heuristic search built on it) places jobs one at a time, so
 *       {@link #restrict} and {@link #isSatisfiedBy} - which both see what is already placed - are
 *       enough for most types.</li>
 *   <li>{@link ExactAllocationSolver} builds every flexible job's variables at once, so cross-job
 *       rules need {@link #applyToExactModel} instead; {@link #restrict} is still used there too, but
 *       only for the single-job, order-independent part of a constraint (there is never a
 *       {@code placedSoFar} to consult while the model is being built).</li>
 * </ul>
 * A concrete type overrides only the hooks it actually needs; the rest keep these no-op defaults.
 */
public abstract class Constraint {

    public abstract ConstraintType type();

    /** The job(s) this constraint governs. */
    public abstract List<Long> jobIds();

    /**
     * Narrows {@code window} for {@code job}, given what has already been placed for the other
     * job(s) this constraint governs ({@code placedSoFar}, keyed by job id, may be missing an entry
     * for a job not yet placed - including, for the exact solver, every job, since nothing is placed
     * incrementally there). Must never widen the window.
     */
    public JobWindow restrict(OptimizerJob job, JobWindow window, Map<Long, JobPlan> placedSoFar) {
        return window;
    }

    /**
     * True when {@code candidate} for {@code job} is still compatible with this constraint given
     * what has already been placed. Used by the greedy/heuristic solver as a final accept/reject
     * check after {@link #restrict} has already narrowed the window.
     */
    public boolean isSatisfiedBy(OptimizerJob job, JobPlan candidate, Map<Long, JobPlan> placedSoFar) {
        return true;
    }

    /**
     * Adds this constraint's linear expressions directly into the exact solver's model, using
     * {@code runningVariableOf} to look up each governed job's per-hour "running" variable.
     */
    public void applyToExactModel(ExpressionsBasedModel model, RunningVariableLookup runningVariableOf) {
    }
}
