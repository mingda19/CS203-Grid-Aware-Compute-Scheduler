package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Places every {@link SynchronizedFinishConstraint} group in a problem before the normal per-job
 * search/model ever sees its members (docs/schedule-explanation-design.md section 2.4): each group
 * gets the cheapest common end hour every member can reach, tried hour by hour across the range
 * every member could possibly end in, reserving members' capacity in declaration order within the
 * group. A group with no common feasible end hour fails closed together - none of its members get a
 * plan, even one that could have run alone.
 *
 * <p>Shared verbatim by both solvers: groups are placed greedily, first, against capacity left
 * after exempt jobs, before anything else is scheduled. A run containing a synchronized-finish
 * group is therefore not globally proven optimal even when the remaining jobs' placement is - the
 * group's own placement is chosen independently of them, exactly once, regardless of which solver
 * handles the rest.
 */
final class SynchronizedGroupPlacer {

    private static final double COST_TOLERANCE = 1e-9;

    private SynchronizedGroupPlacer() {
    }

    /**
     * @param plans         the chosen plan for every successfully placed group member
     * @param groupedJobIds every flexible job that belongs to a group of 2+ flexible members -
     *                      whether or not that group was successfully placed, these ids must be
     *                      excluded from the normal per-job search/model entirely
     */
    record Placement(Map<OptimizerJob, JobPlan> plans, Set<Long> groupedJobIds) {
    }

    static Placement place(OptimizationProblem problem, EnergyCostModel costs) {
        List<SynchronizedFinishConstraint> groups = problem.constraints().stream()
                .filter(c -> c instanceof SynchronizedFinishConstraint)
                .map(c -> (SynchronizedFinishConstraint) c)
                .sorted(Comparator.comparing(group -> group.jobIds().stream().min(Long::compareTo).orElseThrow()))
                .toList();

        ResidualCapacity free = problem.residualAfterExemptJobs();
        Map<OptimizerJob, JobPlan> plans = new LinkedHashMap<>();
        Set<Long> groupedIds = new HashSet<>();

        for (SynchronizedFinishConstraint group : groups) {
            List<OptimizerJob> members = problem.jobs().stream()
                    .filter(job -> !job.exempt() && group.jobIds().contains(job.id()))
                    .toList();
            if (members.size() < 2) {
                continue; // nothing to synchronize among fewer than 2 flexible members
            }
            members.forEach(member -> groupedIds.add(member.id()));
            cheapestCommonEndHour(members, free, problem.prices(), costs).ifPresent(groupPlans -> {
                groupPlans.values().forEach(free::reserve);
                plans.putAll(groupPlans);
            });
        }
        return new Placement(plans, groupedIds);
    }

    /**
     * Tries every hour in the range every member could possibly end in, cheapest feasible total
     * first; within one trial hour, members reserve capacity from each other in declaration order.
     */
    private static Optional<Map<OptimizerJob, JobPlan>> cheapestCommonEndHour(
            List<OptimizerJob> members, ResidualCapacity free, PriceSeries prices, EnergyCostModel costs) {
        LocalDateTime rangeStart = members.stream().map(OptimizerJob::originalStartTime)
                .max(LocalDateTime::compareTo).orElseThrow();
        LocalDateTime rangeEnd = members.stream().map(member -> new JobWindow(member, prices).lastSlot())
                .min(LocalDateTime::compareTo).orElseThrow();
        if (rangeStart.isAfter(rangeEnd)) {
            return Optional.empty();
        }

        Map<OptimizerJob, JobPlan> best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (LocalDateTime end = rangeStart; !end.isAfter(rangeEnd); end = end.plusHours(1)) {
            ResidualCapacity trial = free.copy();
            Map<OptimizerJob, JobPlan> candidate = new LinkedHashMap<>();
            double cost = 0.0;
            boolean feasible = true;
            for (OptimizerJob member : members) {
                Optional<JobPlan> plan = GreedyAllocator.cheapestPlanEndingAt(member, end, trial, prices, costs);
                if (plan.isEmpty()) {
                    feasible = false;
                    break;
                }
                trial.reserve(plan.get());
                candidate.put(member, plan.get());
                cost += costs.costUsd(plan.get(), prices);
            }
            if (feasible && cost < bestCost - COST_TOLERANCE) {
                best = candidate;
                bestCost = cost;
            }
        }
        return Optional.ofNullable(best);
    }
}
