package com.gacs.backend.service.allocation;

/**
 * The constraint types a job may carry (docs/schedule-explanation-design.md section 2). Only
 * {@link #DEADLINE} and {@link #FLEXIBILITY_WINDOW} are enforced by the solver today - the other
 * four are reserved here so {@code ConstraintImpact.constraintType}'s valid value set doesn't
 * need to change when they're built.
 */
public enum ConstraintType {
    /** Single-job: a hard finish-by time. Enforced today via {@link OptimizerJob#deadline()}. */
    DEADLINE,
    /** Single-job: a delay window relative to the job's own baseline start. Enforced today via
     *  {@link OptimizerJob#flexibilityWindowHours()}. */
    FLEXIBILITY_WINDOW,
    /** Single-job: an absolute, recurring daily wall-clock window (e.g. 19:00-22:00 UTC) -
     *  distinct from {@link #FLEXIBILITY_WINDOW}, which is relative to the job's own baseline. */
    ABSOLUTE_TIME_WINDOW,
    /** Pair: one job must finish before another starts. */
    PRECEDENCE,
    /** Pair: two jobs may never run in the same hour. */
    MUTUAL_EXCLUSION,
    /** Group: every member must finish at the same hour. */
    SYNCHRONIZED_FINISH
}
