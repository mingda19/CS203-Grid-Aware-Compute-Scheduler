package com.gacs.backend.service;

import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;

/**
 * The one short, DB-only transaction {@link ScheduleReviewService} records an
 * {@code OptimizerRun}/{@code OptimizationPlan} pair in, kept on a separate bean for the same
 * reason {@code ScheduleRunRecorder} delegates to {@code ScheduleRunPersistence}: Spring's
 * {@code @Transactional} proxy does not intercept a method calling another {@code @Transactional}
 * method on {@code this} (self-invocation bypasses the proxy entirely). {@code
 * ScheduleReviewService.optimize()} calls the slow part - {@code ScheduleOptimizationService
 * .optimize()}, which makes an external LLM call that must never hold a database connection -
 * before this method ever runs, so this being on its own bean is what keeps that call genuinely
 * outside any transaction rather than silently folded back into one by REQUIRED propagation.
 */
@Service
class OptimizationPlanRecorder {

    private final OptimizerRunRepository runs;
    private final OptimizationPlanRepository plans;

    OptimizationPlanRecorder(OptimizerRunRepository runs, OptimizationPlanRepository plans) {
        this.runs = runs;
        this.plans = plans;
    }

    @Transactional
    void record(String runId, Instant createdAt, String solverName, String solverVersion, String solverConfig,
               User owner, String resultJson, Collection<Long> jobIds) {
        OptimizerRun run = runs.save(OptimizerRun.builder()
                .id(runId)
                .createdAt(createdAt)
                .solverName(solverName)
                .solverVersion(solverVersion)
                .solverConfig(solverConfig)
                .build());
        plans.save(new OptimizationPlan(run, owner, resultJson, jobIds, createdAt));
    }
}
