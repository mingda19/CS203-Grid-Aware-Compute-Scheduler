package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * Append-only audit event entity recording operator actions, decisions, and rationale.
 * Aligned with Jira GRID-83 and GRID-134 AC4.1.
 * Marked @Immutable to enforce that audit entries cannot be mutated or updated.
 */
@Entity
@Table(name = "audit_events", indexes = {
    @Index(name = "idx_audit_events_run_id", columnList = "run_id"),
    @Index(name = "idx_audit_events_actor_ts", columnList = "actor, timestamp"),
    @Index(name = "idx_audit_events_timestamp", columnList = "timestamp")
})
@Immutable
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "actor", nullable = false, updatable = false, length = 255)
    private String actor;

    @Enumerated(EnumType.STRING)
    @Column(name = "action_type", nullable = false, updatable = false, length = 50)
    private AuditActionType actionType;

    @Column(name = "timestamp", nullable = false, updatable = false)
    private Instant timestamp;

    @Column(name = "run_id", nullable = false, updatable = false, length = 64)
    private String runId;

    @Column(name = "schedule_hash", nullable = false, updatable = false, length = 64)
    private String scheduleHash;

    @Column(name = "operator_rationale", nullable = false, updatable = false, length = 2000)
    private String operatorRationale;

    @PreRemove
    public void onRemove() {
        throw new UnsupportedOperationException("Audit events are append-only. Deletions are forbidden.");
    }
}
