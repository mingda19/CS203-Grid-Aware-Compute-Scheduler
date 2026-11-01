package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.PriceSeries;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * A validated optimisation run: what {@link OptimizationProblemFactory} produces from a request and
 * every {@link AllocationSolver} consumes. Every job's baseline finishes by its deadline and every
 * baseline slot is priced, so baselines can always be costed.
 *
 * @param jobs             in request order; ids are unique
 * @param baselines        each job's baseline plan; exempt jobs run exactly as theirs
 * @param constraints      every {@link PlacementConstraint} declared for this run (today always
 *                         empty: the factory does not yet parse constraint declarations from a
 *                         request - see docs/schedule-explanation-design.md section 2.6)
 * @param synchronizedGroups every {@link SynchronizedFinishConstraint} declared for this run, kept
 *                         separate from {@code constraints} since it is not a {@link
 *                         PlacementConstraint} (see that interface's javadoc for why)
 */
public record OptimizationProblem(
        List<OptimizerJob> jobs,
        PriceSeries prices,
        SlotCapacity capacity,
        Map<OptimizerJob, JobPlan> baselines,
        List<PlacementConstraint> constraints,
        List<SynchronizedFinishConstraint> synchronizedGroups) {

    /** Convenience for the common case of no constraints or synchronized-finish groups. */
    public OptimizationProblem(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity,
                               Map<OptimizerJob, JobPlan> baselines) {
        this(jobs, prices, capacity, baselines, List.of(), List.of());
    }

    /** Convenience for runs with placement constraints but no synchronized-finish groups. */
    public OptimizationProblem(List<OptimizerJob> jobs, PriceSeries prices, SlotCapacity capacity,
                               Map<OptimizerJob, JobPlan> baselines, List<PlacementConstraint> constraints) {
        this(jobs, prices, capacity, baselines, constraints, List.of());
    }

    /**
     * The order in which competing jobs get a contested slot (GRID-155 AC3 / GRID-124): the higher-priority
     * job first (CRITICAL, HIGH, NORMAL, LOW - {@link JobPriority}'s declaration order); then the earlier
     * deadline; then the job asking for fewer total resource-hours (the easier one to still fit elsewhere if
     * it loses the contested slot); then the earlier originalStartTime; then the lower id. Ids are assigned
     * in creation order, so the id stands in for "earlier creation time", which the optimiser input does not
     * carry.
     */
    public static final Comparator<OptimizerJob> TIE_BREAK_ORDER = Comparator
            .comparing(OptimizerJob::priority)
            .thenComparing(OptimizerJob::deadline)
            .thenComparing(OptimizerJob::totalResourceHours)
            .thenComparing(OptimizerJob::originalStartTime)
            .thenComparing(OptimizerJob::id, Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * How many of {@code scheduled} fall in each priority, CRITICAL first. Compare two with
     * {@code Arrays.compare}: the larger is the one that serves higher priorities better.
     */
    public static int[] priorityProfile(java.util.Collection<OptimizerJob> scheduled) {
        int[] counts = new int[JobPriority.values().length];
        scheduled.forEach(job -> counts[job.priority().ordinal()]++);
        return counts;
    }

    public OptimizationProblem {
        jobs = List.copyOf(jobs);
        baselines = Map.copyOf(baselines);
        constraints = List.copyOf(constraints);
        synchronizedGroups = List.copyOf(synchronizedGroups);
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

    /** Every declared placement constraint that governs {@code job}, in declaration order. */
    public List<PlacementConstraint> constraintsFor(OptimizerJob job) {
        return constraints.stream().filter(c -> c.jobIds().contains(job.id())).toList();
    }

    /** Flexible (non-exempt) jobs in {@link #TIE_BREAK_ORDER}. */
    public List<OptimizerJob> flexibleJobsInTieBreakOrder() {
        return jobs.stream().filter(job -> !job.exempt()).sorted(TIE_BREAK_ORDER).toList();
    }

    /**
     * {@link #flexibleJobsInTieBreakOrder()}, adjusted so a {@link PrecedenceConstraint}'s before-job
     * always comes before its after-job. Stable otherwise: among jobs with no ordering dependency
     * between them, the tie-break order is kept. A precedence cycle (nothing validates against one
     * today) leaves its jobs in tie-break order at the end, as a defensive fallback rather than a
     * guarantee this method relies on being exercised.
     */
    public List<OptimizerJob> flexibleJobsInPlacementOrder() {
        List<OptimizerJob> tieBreakOrder = flexibleJobsInTieBreakOrder();
        List<PrecedenceConstraint> edges = constraints.stream()
                .filter(c -> c instanceof PrecedenceConstraint)
                .map(c -> (PrecedenceConstraint) c)
                .toList();
        if (edges.isEmpty()) {
            return tieBreakOrder;
        }
        Set<Long> ids = new HashSet<>();
        tieBreakOrder.forEach(job -> ids.add(job.id()));
        Map<Long, Integer> remainingPredecessors = new HashMap<>();
        Map<Long, List<Long>> successorsOf = new HashMap<>();
        tieBreakOrder.forEach(job -> remainingPredecessors.put(job.id(), 0));
        for (PrecedenceConstraint edge : edges) {
            if (ids.contains(edge.beforeJobId()) && ids.contains(edge.afterJobId())) {
                successorsOf.computeIfAbsent(edge.beforeJobId(), key -> new ArrayList<>()).add(edge.afterJobId());
                remainingPredecessors.merge(edge.afterJobId(), 1, Integer::sum);
            }
        }

        List<OptimizerJob> result = new ArrayList<>();
        Set<Long> placed = new HashSet<>();
        while (result.size() < tieBreakOrder.size()) {
            OptimizerJob next = tieBreakOrder.stream()
                    .filter(job -> !placed.contains(job.id()) && remainingPredecessors.get(job.id()) == 0)
                    .findFirst()
                    .orElse(null);
            if (next == null) {
                tieBreakOrder.stream().filter(job -> !placed.contains(job.id())).forEach(result::add);
                break;
            }
            result.add(next);
            placed.add(next.id());
            successorsOf.getOrDefault(next.id(), List.of())
                    .forEach(id -> remainingPredecessors.merge(id, -1, Integer::sum));
        }
        return result;
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
