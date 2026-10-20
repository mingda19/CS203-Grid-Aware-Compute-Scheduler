package com.gacs.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * The real GRID-125 solver: tries {@link ExactScheduleSolver} first, falling back to {@link
 * SimulatedAnnealingScheduleSolver} when the problem is too large to solve exactly within a
 * time budget, or when the exact attempt is skipped entirely because a cheap upfront estimate
 * already says it would be. The sole {@code @Service} implementing {@link ScheduleSolver}, so
 * {@link ScheduleOptimizationService} resolves it unambiguously by type - exactly as it did
 * for {@link GreedyStubScheduleSolver} before this class replaced it as the production bean.
 */
@Service
public class HybridScheduleSolver implements ScheduleSolver {

    /** Candidate (job, slot) pairs beyond this are skipped straight to the heuristic path
     *  without ever starting a branch-and-bound search. A starting point, not a tuned
     *  result - the backend runs with -Xmx384m on Render (backend/Dockerfile), so a large
     *  search tree is a memory risk on top of a time risk; revisit once profiled for real. */
    static final int DEFAULT_SIZE_THRESHOLD_PAIRS = 4_000;

    /** Wall-clock budget given to the exact solver before falling back. Also a starting
     *  point pending real profiling, not a tuned result. */
    static final Duration DEFAULT_TIME_BUDGET = Duration.ofSeconds(3);

    private final int sizeThresholdPairs;
    private final Duration timeBudget;

    @Autowired
    public HybridScheduleSolver() {
        this(DEFAULT_SIZE_THRESHOLD_PAIRS, DEFAULT_TIME_BUDGET);
    }

    /** Package-private: lets tests force the fallback routing deterministically and fast,
     *  instead of constructing a genuinely large input or waiting out a real time budget. */
    HybridScheduleSolver(int sizeThresholdPairs, Duration timeBudget) {
        this.sizeThresholdPairs = sizeThresholdPairs;
        this.timeBudget = timeBudget;
    }

    @Override
    public SolverResult solve(SolverInput input) {
        if (estimatedPairCount(input) <= sizeThresholdPairs) {
            try {
                ExactScheduleSolver.Outcome outcome = new ExactScheduleSolver(timeBudget).solve(input);
                if (outcome.certifiedOptimal()) {
                    return outcome.result();
                }
            } catch (RuntimeException ignored) {
                // Falls through to the heuristic path below. The exact solver should never
                // throw by construction (the unplaced slack always gives it a feasible
                // point), but a schedule the operator can actually use beats a 500 either way.
            }
        }
        return new SimulatedAnnealingScheduleSolver().solve(input);
    }

    /**
     * Total candidate (job, slot) pairs across every flexible job, computed before touching
     * ojAlgo at all - the "doomed to be slow" check that lets a clearly oversized input skip
     * straight to the heuristic path instead of waiting out a full time budget first.
     */
    private static int estimatedPairCount(SolverInput input) {
        int total = 0;
        for (JobSpec job : input.jobs()) {
            if (!job.exempt()) {
                total += CandidateSlots.forJob(job, input).size();
            }
        }
        return total;
    }
}
