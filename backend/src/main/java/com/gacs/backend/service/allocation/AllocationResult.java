package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
        for (OptimizerJob job : problem.jobs()) {
            if (job.exempt()) {
                outcomes.add(new JobOutcome(job, problem.baselineOf(job), PerJobStatus.UNCHANGED_EXEMPT, null));
            } else if (flexiblePlans.containsKey(job)) {
                outcomes.add(new JobOutcome(job, flexiblePlans.get(job), PerJobStatus.OPTIMISED, null));
            } else {
                outcomes.add(new JobOutcome(job, null, PerJobStatus.NO_FEASIBLE_SLOT, noRoomReason(job, problem)));
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
     * scheduled, then lower cost, then (equal count and cost) the plan that schedules the job that comes
     * first in {@link OptimizationProblem#TIE_BREAK_ORDER} where the two differ.
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
    private static String noRoomReason(OptimizerJob job, OptimizationProblem problem) {
        JobWindow window = new JobWindow(job, problem.prices());
        LocalDateTime lastEnd = window.lastSlot().plusHours(1);
        return "Cluster full between " + job.originalStartTime().format(HOUR) + " and " + lastEnd.format(HOUR)
                + " UTC: other jobs use the capacity this job needs to finish by its deadline.";
    }
}
