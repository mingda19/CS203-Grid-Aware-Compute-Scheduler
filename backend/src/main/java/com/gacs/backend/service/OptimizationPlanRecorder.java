package com.gacs.backend.service;

import com.gacs.backend.model.Job;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

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
    private final JobRepository jobs;

    OptimizationPlanRecorder(OptimizerRunRepository runs, OptimizationPlanRepository plans, JobRepository jobs) {
        this.runs = runs;
        this.plans = plans;
        this.jobs = jobs;
    }

    /** A run that failed before producing a plan: the run record only, so there is nothing to approve. */
    @Transactional
    void recordFailed(String runId, Instant createdAt, String solverName, String solverVersion, String solverConfig,
                      String inputSnapshot) {
        runs.save(OptimizerRun.builder().id(runId).createdAt(createdAt).solverName(solverName)
                .solverVersion(solverVersion).solverConfig(solverConfig).inputSnapshot(inputSnapshot)
                .inputHash(ScheduleHashUtil.computeSha256(inputSnapshot)).build());
    }

    /**
     * Records the run and its plan, in one transaction with everything below.
     *
     * <ul>
     *   <li>Older waiting plans of the owner that share a job with this one become STALE: this plan replaces
     *       them. It also settles a poller and Recompute that planned the same job at once.</li>
     *   <li>A job the run left out gets the reason and the revision it was solved at, so it is not planned
     *       again until it is edited or recomputed. A job it placed has any earlier reason cleared.</li>
     *   <li>{@code discardReason}, when set, ends the plan at once, so a run that placed nothing never
     *       enters the queue.</li>
     * </ul>
     *
     * @param unplaced the reason for each job the run left out
     * @param revisions each job's scheduling revision as the run solved it
     */
    @Transactional
    void record(String runId, Instant createdAt, String solverName, String solverVersion, String solverConfig,
               User owner, String resultJson, Collection<Long> jobIds, String inputSnapshot, String provenance,
               Map<Long, String> unplaced, Map<Long, Long> revisions, String discardReason) {
        OptimizerRun run = runs.save(OptimizerRun.builder()
                .id(runId)
                .createdAt(createdAt)
                .solverName(solverName)
                .solverVersion(solverVersion)
                .solverConfig(solverConfig)
                .inputSnapshot(inputSnapshot)
                .inputHash(ScheduleHashUtil.computeSha256(inputSnapshot))
                .build());
        var plan = new OptimizationPlan(run, owner, resultJson, jobIds, createdAt);
        plan.recordInputs(provenance);
        for (var other : plans.findByOwnerIdAndReviewState(owner.getId(), ReviewState.PENDING))
            if (!other.getRunId().equals(runId) && !Collections.disjoint(other.getJobIds(), jobIds))
                other.invalidate(ReviewState.STALE, "Replaced by run " + runId, createdAt);
        for (Job job : jobs.findAllById(jobIds)) {
            Long revision = revisions.get(job.getId());
            if (revision == null || revision != job.getSchedulingRevision()) continue; // edited since the run began
            if (unplaced.containsKey(job.getId())) job.recordPlanError(unplaced.get(job.getId()), revision);
            else job.clearPlanError();
        }
        if (discardReason != null) plan.invalidate(ReviewState.INVALIDATED, discardReason, createdAt);
        plans.save(plan);
    }
}
