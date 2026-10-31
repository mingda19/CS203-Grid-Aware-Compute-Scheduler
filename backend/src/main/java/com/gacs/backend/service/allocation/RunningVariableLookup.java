package com.gacs.backend.service.allocation;

import org.ojalgo.optimisation.Variable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Looks up a flexible job's "running" binary variable ({@code a[j,t]} in
 * {@link ExactAllocationSolver}) for a given job and hour, for {@link Constraint#applyToExactModel}
 * to build linear expressions over. Empty when the job has no variable for that hour - either the
 * hour is outside the job's own window, or the job is exempt (exempt jobs have no decision
 * variables at all; a constraint referencing one is a no-op against it in the exact model).
 */
public interface RunningVariableLookup {

    Optional<Variable> runningAt(long jobId, LocalDateTime hour);

    /** Every hour {@code jobId} has a running variable for, earliest first; empty for an exempt job
     *  or one with no feasible hour at all. */
    List<LocalDateTime> hoursOf(long jobId);
}
