package com.gacs.backend.dto;

import com.gacs.backend.model.AuditActionType;

import java.time.Instant;

/**
 * Optional criteria for querying the audit log (GRID-134 AC6.1a).
 * {@code from} is inclusive and {@code to} is exclusive; {@code actor} and {@code runId}
 * match case-insensitively on any substring; {@code jobId} matches events on runs whose stored plan lists the
 * job. Null or blank criteria are ignored.
 *
 * @param onlyActor when set, restricts results to events whose actor equals it exactly, ignoring
 *                  case, or that concern a run it owns. This is the ownership scope, not a search term, which is why it is separate
 *                  from {@code actor}: that one matches any substring, so forcing a caller's own
 *                  address into it would also return events belonging to anyone whose address
 *                  contains theirs. Callers must not take this from request parameters - the
 *                  controller sets it from the authenticated principal.
 */
public record AuditEventFilter(
    Instant from,
    Instant to,
    String actor,
    AuditActionType actionType,
    String runId,
    Long jobId,
    String onlyActor
) {
    /**
     * An unscoped filter: the search criteria only, with no ownership restriction. The controller
     * narrows it with {@link #scopedTo} for anyone who is not an administrator, so an unscoped filter
     * never reaches the query for an ordinary operator.
     */
    public AuditEventFilter(Instant from, Instant to, String actor, AuditActionType actionType,
                            String runId, Long jobId) {
        this(from, to, actor, actionType, runId, jobId, null);
    }

    public static AuditEventFilter none() {
        return new AuditEventFilter(null, null, null, null, null, null, null);
    }

    /** This filter, restricted to {@code email}'s own events and the events on the runs they own. */
    public AuditEventFilter scopedTo(String email) {
        return new AuditEventFilter(from, to, actor, actionType, runId, jobId, email);
    }
}
