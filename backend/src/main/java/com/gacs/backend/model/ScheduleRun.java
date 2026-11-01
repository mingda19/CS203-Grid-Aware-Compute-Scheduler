package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * One {@code POST /api/schedules/optimize} call (docs/schedule-explanation-design.md section 1.2).
 * Every field here is read by either the explanation prompt, the guardrail, or basic run identity -
 * nothing is stored speculatively.
 */
@Entity
@Table(name = "schedule_runs", indexes = {
    @Index(name = "idx_schedule_runs_owner_requested", columnList = "owner_email, requested_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleRun {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The run id already generated in {@code ScheduleOptimizationService}, now persisted instead
     *  of discarded - the same value returned in {@code OptimizeResponseDto.runId}. */
    @Column(name = "run_id", nullable = false, unique = true, length = 36)
    private String runId;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt;

    @Column(nullable = false, length = 30)
    private String status;

    @Column(name = "proven_optimal", nullable = false)
    private boolean provenOptimal;

    @Column(name = "baseline_cost_usd", nullable = false)
    private double baselineCostUsd;

    @Column(name = "optimized_cost_usd", nullable = false)
    private double optimizedCostUsd;

    @Column(name = "projected_savings_usd", nullable = false)
    private double projectedSavingsUsd;

    @Column(name = "projected_savings_percent", nullable = false)
    private double projectedSavingsPercent;

    @Column(name = "used_seeded_fallback_prices", nullable = false)
    private boolean usedSeededFallbackPrices;

    @PrePersist
    protected void onCreate() {
        if (requestedAt == null) {
            requestedAt = LocalDateTime.now();
        }
    }
}
