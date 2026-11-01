package com.gacs.backend.service.allocation;

import com.gacs.backend.model.JobPriority;
import com.gacs.backend.service.PriceSeries;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
     * The order used to break ties between plans that place the same number of jobs at the same cost
     * (GRID-155 / GRID-124): the higher-priority job (CRITICAL first, then HIGH, NORMAL, LOW - {@link
     * JobPriority}'s declaration order) is placed first; if priority ties, the job asking for fewer
     * total resource-hours is placed first, since it is the easier of the two to still fit somewhere
     * else if it loses the contested slot; if that ties too, the earlier originalStartTime, then the
     * lower id, same as before priority existed.
     */
    public static final Comparator<OptimizerJob> TIE_BREAK_ORDER = Comparator
            .comparing(OptimizerJob::priority)
            .thenComparing(OptimizerJob::totalResourceHours)
            .thenComparing(OptimizerJob::originalStartTime)
            .thenComparing(OptimizerJob::id, Comparator.nullsLast(Comparator.naturalOrder()));

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

    /**
     * Canonical text of this run's inputs, stored with the run and hashed so a later change can be
     * detected (GRID-141). Line 1: the jobs, sorted by id, name left out (see {@link #jobEntry}).
     * Line 2: the price of every priced slot. Line 3: the capacity limit of those same slots, the
     * only slots the solver can use.
     */
    public String snapshot() {
        String jobLine = jobs.stream()
                .sorted(Comparator.comparing(OptimizerJob::id, Comparator.nullsLast(Comparator.<Long>naturalOrder())))
                .map(job -> jobEntry(job.id(), job.cpuHours(), job.gpuHours(), job.originalStartTime(),
                        job.deadline(), job.flexibilityWindowHours(), job.exempt()))
                .collect(Collectors.joining(";"));
        StringBuilder priceLine = new StringBuilder();
        StringBuilder capacityLine = new StringBuilder();
        prices.asMap().forEach((slot, price) -> {
            CapacityLimit limit = capacity.limitAt(slot);
            priceLine.append(slot).append('=').append(number(price)).append(';');
            capacityLine.append(slot).append('=').append(number(limit.cpuHours())).append('/')
                    .append(number(limit.gpuHours())).append(';');
        });
        return jobLine + "\n" + priceLine + "\n" + capacityLine;
    }

    /**
     * One job's line in {@link #snapshot()}. Shared with the export check, which rebuilds it from the
     * job as it is now: one formatter, so the two can never drift apart. Priority is left out because
     * the solver does not use it yet.
     */
    public static String jobEntry(Long id, double cpuHours, double gpuHours, LocalDateTime start,
                                  LocalDateTime deadline, int flexibilityWindowHours, boolean exempt) {
        return id + "," + number(cpuHours) + "," + number(gpuHours) + "," + start + "," + deadline + ","
                + flexibilityWindowHours + "," + exempt;
    }

    private static String number(double value) {
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
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
