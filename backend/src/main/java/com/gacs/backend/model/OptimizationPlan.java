package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "optimization_plans", indexes = @Index(name = "idx_plan_owner", columnList = "owner_id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class OptimizationPlan {
    @Id
    private UUID runId;

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

    public OptimizationPlan(UUID runId, User owner, String resultJson, Instant createdAt) {
        this.runId = runId;
        this.owner = owner;
        this.resultJson = resultJson;
        this.createdAt = createdAt;
    }

    public void recordDecision(DecisionAction action) { reviewState = action.reviewState(); }
}
