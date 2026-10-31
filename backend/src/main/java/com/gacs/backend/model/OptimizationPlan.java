package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;

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

    public OptimizationPlan(OptimizerRun run, User owner, String resultJson, Instant createdAt) {
        // Leave the derived ID unset so Spring Data persists this new entity instead of merging it.
        this.run = run;
        this.owner = owner;
        this.resultJson = resultJson;
        this.createdAt = createdAt;
    }

    public void recordDecision(AuditActionType action) {
        reviewState = switch (action) {
            case APPROVE -> ReviewState.APPROVED;
            case REJECT -> ReviewState.REJECTED;
            case REVISE -> ReviewState.REVISION_REQUESTED;
            default -> throw new IllegalArgumentException("Unsupported schedule decision: " + action);
        };
    }
}
