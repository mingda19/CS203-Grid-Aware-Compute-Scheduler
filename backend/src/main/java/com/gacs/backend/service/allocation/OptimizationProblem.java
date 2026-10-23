package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A validated optimisation run: what {@link OptimizationProblemFactory} produces from a request and
 * every {@link AllocationSolver} consumes. Every job's baseline finishes by its deadline and every
 * baseline slot is priced, so baselines can always be costed.
 *
 * @param jobs      in request order; ids are unique
 * @param baselines each job's baseline plan; exempt jobs run exactly as theirs
 */
public record OptimizationProblem(
        List<OptimizerJob> jobs,
        PriceSeries prices,
        SlotCapacity capacity,
        Map<OptimizerJob, JobPlan> baselines) {

    /**
     * The order used to break ties between plans that place the same number of jobs at the same cost:
     * the job with the earlier originalStartTime, then the lower id, is placed. Temporary until job
     * priority in Sprint 4.
     */
    public static final Comparator<OptimizerJob> TIE_BREAK_ORDER = Comparator
            .comparing(OptimizerJob::originalStartTime)
            .thenComparing(OptimizerJob::id, Comparator.nullsLast(Comparator.naturalOrder()));

    public OptimizationProblem {
        jobs = List.copyOf(jobs);
        baselines = Map.copyOf(baselines);
        Objects.requireNonNull(prices, "prices");
        Objects.requireNonNull(capacity, "capacity");
        for (OptimizerJob job : jobs) {
            if (!baselines.containsKey(job)) {
                throw new IllegalArgumentException("missing baseline for job " + job.label());
            }
        }
    }

    public JobPlan baselineOf(OptimizerJob job) {
        return baselines.get(job);
    }

    /** Flexible (non-exempt) jobs in {@link #TIE_BREAK_ORDER}. */
    public List<OptimizerJob> flexibleJobsInTieBreakOrder() {
        return jobs.stream().filter(job -> !job.exempt()).sorted(TIE_BREAK_ORDER).toList();
    }

    /**
     * Upper bound on the size of any total cost in this run: every flexible job's energy at the largest
     * absolute price. Used to scale tie-break weights so they stay far below real cost differences.
     */
    double costScale(EnergyCostModel costs) {
        double maxAbsPrice = prices.asMap().values().stream().mapToDouble(Math::abs).max().orElse(0.0);
        double scale = jobs.stream().filter(job -> !job.exempt())
                .mapToDouble(job -> costs.slotCostUsd(job, 1.0, maxAbsPrice)).sum();
        return Math.max(scale, 1e-9);
    }

    /** Capacity left once every exempt job runs as its baseline (decision 4b: never below 0). */
    ResidualCapacity residualAfterExemptJobs() {
        ResidualCapacity residual = new ResidualCapacity(capacity);
        jobs.stream().filter(OptimizerJob::exempt).forEach(job -> residual.reserve(baselineOf(job)));
        return residual;
    }
}
