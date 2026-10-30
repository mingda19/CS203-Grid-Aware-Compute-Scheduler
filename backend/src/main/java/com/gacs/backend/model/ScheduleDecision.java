package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "schedule_decisions", indexes = @Index(name = "idx_decision_plan_history", columnList = "plan_id,id"))
@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
public class ScheduleDecision {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "plan_id", nullable = false, updatable = false)
    private OptimizationPlan plan;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "operator_id", nullable = false, updatable = false)
    private User operator;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 20)
    private DecisionAction action;

    @Column(name = "comment_text", updatable = false, length = 2000)
    private String comment;

    @Column(nullable = false, updatable = false)
    private Instant decidedAt;

    public ScheduleDecision(OptimizationPlan plan, User operator, DecisionAction action, String comment, Instant decidedAt) {
        this.plan = plan;
        this.operator = operator;
        this.action = action;
        this.comment = comment;
        this.decidedAt = decidedAt;
    }
}
