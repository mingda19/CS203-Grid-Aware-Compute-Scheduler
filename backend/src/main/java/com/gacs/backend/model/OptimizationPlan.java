package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;

@Entity
@Table(name = "optimization_plans", indexes = @Index(name = "idx_plan_owner", columnList = "owner_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class OptimizationPlan {
    @Id
    @Column(name = "run_id", length = 36, nullable = false, updatable = false)
    private String runId;

    @MapsId
    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "run_id", nullable = false, updatable = false)
    private OptimizerRun run;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_id", nullable = false, updatable = false)
    private User owner;

    @Column(nullable = false, updatable = false, columnDefinition = "text")
    private String resultJson;

    @Column(nullable = false, updatable = false)
    private Instant createdAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private ReviewState reviewState = ReviewState.PENDING;

    @Column(columnDefinition = "text", updatable = false)
    private String inputSnapshot;

    @Column(columnDefinition = "text")
    private String invalidationReason;

    private Instant invalidatedAt;

    private Instant appliedAt;

    public void markApplied(Instant now) { appliedAt = now; }

    public void recordInputs(String snapshot) { this.inputSnapshot = snapshot; }

    public void invalidate(ReviewState state, String reason, Instant now) {
        if (reviewState == ReviewState.INVALIDATED || reviewState == ReviewState.STALE) return;
        reviewState = state;
        invalidationReason = reason;
        invalidatedAt = now;
    }

    /** IDs of the jobs in {@link #resultJson}, so audit queries can find the runs that include a job (GRID-134 AC6.1a). */
    @ElementCollection
    @CollectionTable(name = "optimization_plan_jobs", joinColumns = @JoinColumn(name = "run_id"),
        indexes = @Index(name = "idx_plan_jobs_job", columnList = "job_id"))
    @Column(name = "job_id", nullable = false)
    private Set<Long> jobIds = new HashSet<>();

    public OptimizationPlan(OptimizerRun run, User owner, String resultJson, Collection<Long> jobIds, Instant createdAt) {
        // Leave the derived ID unset so Spring Data persists this new entity instead of merging it.
        this.run = run;
        this.owner = owner;
        this.resultJson = resultJson;
        this.jobIds.addAll(jobIds);
        this.createdAt = createdAt;
    }

    /** Records job IDs for a plan stored before they were kept; does nothing once it has any. */
    public void backfillJobIds(Collection<Long> ids) {
        if (jobIds.isEmpty()) {
            jobIds.addAll(ids);
        }
    }

    public void recordDecision(AuditActionType action) {
        if (reviewState == ReviewState.INVALIDATED || reviewState == ReviewState.STALE)
            throw new IllegalArgumentException("Generate a new recommendation for an invalidated or stale run");
        reviewState = switch (action) {
            case APPROVE -> ReviewState.APPROVED;
            case REJECT -> ReviewState.REJECTED;
            case REVISE -> ReviewState.REVISION_REQUESTED;
            // A revoked approval lands in REJECTED so that every existing execution gate - which
            // already refuses to apply, start or export anything not APPROVED - halts the schedule
            // without needing to learn a new state.
            case REVOKE -> ReviewState.REJECTED;
            default -> throw new IllegalArgumentException("Unsupported schedule decision: " + action);
        };
    }
}
