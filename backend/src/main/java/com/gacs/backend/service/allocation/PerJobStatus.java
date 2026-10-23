package com.gacs.backend.service.allocation;

/** What happened to one job in a run (contract section 6.4, {@code perJobStatus}). */
public enum PerJobStatus {
    /** Not exempt and placed on its cheapest feasible plan, which may or may not start at originalStartTime. */
    OPTIMISED,
    /** Kept at originalStartTime, running as in its baseline, because isExempt is true. */
    UNCHANGED_EXEMPT,
    /** No room left for the job around the other jobs; not scheduled. */
    NO_FEASIBLE_SLOT
}
