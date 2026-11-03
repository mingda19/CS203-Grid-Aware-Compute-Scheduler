package com.gacs.backend.dto;

import com.gacs.backend.model.AuditActionType;

import java.time.Instant;

/**
 * Optional criteria for querying the audit log (GRID-134 AC6.1a).
 * {@code from} is inclusive and {@code to} is exclusive; {@code actor} and {@code runId}
 * match case-insensitively on any substring; {@code jobId} matches events on runs whose stored plan lists the
 * job. Null or blank criteria are ignored.
 */
public record AuditEventFilter(
    Instant from,
    Instant to,
    String actor,
    AuditActionType actionType,
    String runId,
    Long jobId
) {
    public static AuditEventFilter none() {
        return new AuditEventFilter(null, null, null, null, null, null);
    }
}
