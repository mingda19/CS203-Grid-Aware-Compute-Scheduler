package com.gacs.backend.service.allocation;

/**
 * The constraint types a job may carry (docs/schedule-explanation-design.md section 2).
 *
 * <p>{@link #DEADLINE}, {@link #FLEXIBILITY_WINDOW}, {@link #PRECEDENCE}, {@link #DEPENDENCY} and
 * {@link #MUTUAL_EXCLUSION} are enforced by the solvers; {@link #ABSOLUTE_TIME_WINDOW} and
 * {@link #SYNCHRONIZED_FINISH} are carried here for {@code ConstraintImpact.constraintType} and
 * handled outside {@link PlacementConstraint} (see {@link SynchronizedFinishConstraint}).
 *
 * <p>Adding a member here needs no migration: {@code constraint_impacts.constraint_type} is a plain
 * varchar, not an {@code @Enumerated} column, so Hibernate generates no CHECK over the value set -
 * verified against the live schema. That is not true of every enum-ish column in this codebase;
 * {@code audit_events.action_type} did carry a generated CHECK and needed V9 to widen it.
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
    /**
     * Pair: <em>this</em> job must finish before the other one starts. The job carrying the
     * constraint is the one that goes first.
     */
    PRECEDENCE,
    /**
     * Pair: the mirror of {@link #PRECEDENCE} - the other job must finish before <em>this</em> one
     * starts. "Run it after the ETL job is done."
     *
     * <p>The solver has no separate concept for this, and should not: {@link PrecedenceConstraint}
     * is directional, so a dependency is that same constraint with its two jobs swapped, and
     * {@link OptimizationProblemFactory} does the swap when it builds the run. Everything
     * downstream - both solvers, cycle detection, the per-job windows - sees one ordering rule and
     * needs no change.
     *
     * <p>It exists as its own type because it is a different thing to <em>say</em>. An operator who
     * writes "run it after the ETL job" has not stated a precedence and should not be shown one to
     * confirm; the explanation of the resulting schedule reads the same way round as what they
     * typed. Storing the operator's own direction is also what makes the stored
     * {@code ConstraintImpact.constraintType} worth reading back.
     */
    DEPENDENCY,
    /** Pair: two jobs may never run in the same hour. */
    MUTUAL_EXCLUSION,
    /** Group: every member must finish at the same hour. */
    SYNCHRONIZED_FINISH
}
