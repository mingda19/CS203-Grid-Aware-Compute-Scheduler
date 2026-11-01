package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The outcome of one run: a {@link JobOutcome} per input job, in input order.
 *
 * @param provenOptimal true when the exact solver proved no better plan exists; false for a heuristic plan,
 *                      which still meets every window, deadline and capacity rule
 */
public record AllocationResult(List<JobOutcome> outcomes, boolean provenOptimal) {

    /** Costs closer than this are treated as equal when ranking plans. Far below the 4 dp shown to users. */
    static final double COST_TOLERANCE = 1e-7;

    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    public AllocationResult {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * Builds the result from the plans chosen for flexible jobs. Exempt jobs run as their baseline; a
     * flexible job without a plan is {@link PerJobStatus#NO_FEASIBLE_SLOT} with a reason.
     */
    static AllocationResult of(OptimizationProblem problem, Map<OptimizerJob, JobPlan> flexiblePlans,
                               boolean provenOptimal) {
        List<JobOutcome> outcomes = new ArrayList<>();
        Map<OptimizerJob, JobPlan> scheduled = new HashMap<>(flexiblePlans);
        problem.jobs().stream().filter(OptimizerJob::exempt).forEach(job -> scheduled.put(job, problem.baselineOf(job)));
        for (OptimizerJob job : problem.jobs()) {
            if (job.exempt()) {
                outcomes.add(new JobOutcome(job, problem.baselineOf(job), PerJobStatus.UNCHANGED_EXEMPT, null));
            } else if (flexiblePlans.containsKey(job)) {
                outcomes.add(new JobOutcome(job, flexiblePlans.get(job), PerJobStatus.OPTIMISED, null));
            } else {
                outcomes.add(new JobOutcome(job, null, PerJobStatus.NO_FEASIBLE_SLOT, noRoomReason(job, problem, scheduled)));
            }
        }
        return new AllocationResult(outcomes, provenOptimal);
    }

    /** Every scheduled job (exempt included) and its plan, in input order. */
    public Map<OptimizerJob, JobPlan> plans() {
        Map<OptimizerJob, JobPlan> plans = new LinkedHashMap<>();
        outcomes.forEach(outcome -> outcome.planIfScheduled().ifPresent(plan -> plans.put(outcome.job(), plan)));
        return plans;
    }

    public int scheduledCount() {
        return (int) outcomes.stream().filter(JobOutcome::scheduled).count();
    }

    /** Raw (unrounded) total cost of every scheduled job, exempt included. */
    public double costUsd(EnergyCostModel costs, PriceSeries prices) {
        return plans().values().stream().mapToDouble(plan -> costs.costUsd(plan, prices)).sum();
    }

    /**
     * True when {@code candidate} is better than {@code incumbent} under the contract's objective: more jobs
     * scheduled, then the plan that serves higher priorities (more CRITICAL jobs, then more HIGH, ...), then
     * lower cost, then (all equal) the plan that schedules the job that comes first in
     * {@link OptimizationProblem#TIE_BREAK_ORDER} where the two differ.
     */
    static boolean isBetter(AllocationResult candidate, AllocationResult incumbent, OptimizationProblem problem,
                            EnergyCostModel costs) {
        if (incumbent == null) {
            return true;
        }
        int count = Integer.compare(candidate.scheduledCount(), incumbent.scheduledCount());
        if (count != 0) {
            return count > 0;
        }
        int priority = Arrays.compare(OptimizationProblem.priorityProfile(candidate.plans().keySet()),
                OptimizationProblem.priorityProfile(incumbent.plans().keySet()));
        if (priority != 0) {
            return priority > 0;
        }
        double difference = candidate.costUsd(costs, problem.prices()) - incumbent.costUsd(costs, problem.prices());
        if (Math.abs(difference) > COST_TOLERANCE) {
            return difference < 0;
        }
        Map<OptimizerJob, JobPlan> mine = candidate.plans();
        Map<OptimizerJob, JobPlan> theirs = incumbent.plans();
        for (OptimizerJob job : problem.flexibleJobsInTieBreakOrder()) {
            if (mine.containsKey(job) != theirs.containsKey(job)) {
                return mine.containsKey(job);
            }
        }
        return false;
    }

    /**
     * Why a job got no plan. Request validation already ensured it could finish alone on the cluster, so
     * the cause is always capacity taken by other jobs within the hours it was allowed to use.
     */
    private static String noRoomReason(OptimizerJob job, OptimizationProblem problem,
                                       Map<OptimizerJob, JobPlan> scheduled) {
        JobWindow window = new JobWindow(job, problem.prices());
        LocalDateTime lastEnd = window.lastSlot().plusHours(1);
        // The job that took the slot: the first (by tie-break order) of those running inside this job's window.
        List<OptimizerJob> blockers = scheduled.entrySet().stream()
                .filter(e -> e.getValue().allocations().stream().anyMatch(a ->
                        !a.slotStart().isBefore(job.originalStartTime()) && a.slotStart().isBefore(lastEnd)))
                .map(Map.Entry::getKey)
                .sorted(OptimizationProblem.TIE_BREAK_ORDER)
                .toList();
        String took = blockers.isEmpty() ? "other jobs use"
                : "job " + blockers.get(0).label() + " (" + blockers.get(0).priority() + ")"
                + (blockers.size() > 1 ? " and " + (blockers.size() - 1) + " other(s) use" : " uses");
        return "Cluster full between " + job.originalStartTime().format(HOUR) + " and " + lastEnd.format(HOUR)
                + " UTC: " + took + " the capacity this job needs to finish by its deadline.";
    }
}
