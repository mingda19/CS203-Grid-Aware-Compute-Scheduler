package com.gacs.backend.model;

/**
 * How an optimiser run ended. SCHEDULED and NEEDS_ATTENTION are the run statuses the optimise endpoint
 * already returns; the rest are the non-approved outcomes a run can fail safely with.
 */
public enum OptimizerRunStatus {
    SCHEDULED,
    NEEDS_ATTENTION,
    INFEASIBLE,
    TIMEOUT,
    FAILED
}
