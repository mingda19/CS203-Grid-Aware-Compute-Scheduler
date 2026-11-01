package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Checks a set of plans against every hard rule of the contract (section 6.4) and lists the broken
 * ones. Used on the brute-force checker now, and on the real solvers in Phase 5.
 * Shape rules (consecutive slots, shares add up to 1) are already enforced by {@link JobPlan}.
 * {@link #violations(OptimizationProblem, AllocationResult)} additionally checks every attached
 * {@link Constraint} (section 2) against the result.
 */
final class ContractRules {

    /** Relative slack for capacity checks: solver results carry floating-point dust. */
    private static final double TOLERANCE = 1e-7;

    private ContractRules() {
    }

    /** {@code plans} maps each scheduled job to its plan; a job missing from it is not scheduled. */
    static List<String> violations(List<OptimizerJob> jobs, Map<OptimizerJob, JobPlan> plans, PriceSeries prices,
                                   SlotCapacity capacity) {
        List<String> problems = new ArrayList<>();
        BaselineCalculator baselines = new BaselineCalculator(capacity);
        Map<LocalDateTime, double[]> exemptUse = new HashMap<>();
        Map<LocalDateTime, double[]> flexibleUse = new HashMap<>();

        for (OptimizerJob job : jobs) {
            JobPlan plan = plans.get(job);
            if (job.exempt()) {
                Optional<JobPlan> baseline = baselines.baseline(job).plan();
                if (plan == null || baseline.isEmpty() || !plan.allocations().equals(baseline.get().allocations())) {
                    problems.add("exempt job " + job.label() + " does not run exactly as its baseline");
                }
                if (plan != null) {
                    addUse(exemptUse, plan);
                }
                continue;
            }
            if (plan == null) {
                continue;
            }
            if (!new JobWindow(job, prices).allows(plan)) {
                problems.add("job " + job.label() + " runs outside its window, past its deadline or in an unpriced slot");
            }
            addUse(flexibleUse, plan);
        }

        flexibleUse.forEach((slot, used) -> {
            double[] reserved = exemptUse.getOrDefault(slot, new double[2]);
            CapacityLimit free = capacity.limitAt(slot).minus(reserved[0], reserved[1]);
            if (used[0] > free.cpuHours() + TOLERANCE * Math.max(1.0, free.cpuHours())
                    || used[1] > free.gpuHours() + TOLERANCE * Math.max(1.0, free.gpuHours())) {
                problems.add("slot " + slot + " is over capacity");
            }
        });
        return problems;
    }

    static List<String> violations(OptimizationProblem problem, AllocationResult result) {
        List<String> problems = new ArrayList<>(violations(problem.jobs(), result.plans(), problem.prices(),
                problem.capacity()));
        Map<Long, JobPlan> plansById = new HashMap<>();
        result.plans().forEach((job, plan) -> plansById.put(job.id(), plan));
        for (Constraint constraint : problem.constraints()) {
            if (constraint instanceof AbsoluteTimeWindowConstraint c) {
                checkAbsoluteWindow(c, plansById, problems);
            } else if (constraint instanceof PrecedenceConstraint c) {
                checkPrecedence(c, plansById, problems);
            } else if (constraint instanceof MutualExclusionConstraint c) {
                checkMutualExclusion(c, plansById, problems);
            } else if (constraint instanceof SynchronizedFinishConstraint c) {
                checkSynchronizedFinish(c, plansById, problems);
            }
        }
        return problems;
    }

    private static void checkAbsoluteWindow(AbsoluteTimeWindowConstraint c, Map<Long, JobPlan> plansById,
                                             List<String> problems) {
        for (long jobId : c.jobIds()) {
            JobPlan plan = plansById.get(jobId);
            if (plan == null) {
                continue;
            }
            for (SlotAllocation allocation : plan.allocations()) {
                if (!withinDaily(allocation.slotStart().toLocalTime(), c.dailyStartTime(), c.dailyEndTime())) {
                    problems.add("job id " + jobId + " runs at " + allocation.slotStart()
                            + ", outside its absolute time window");
                }
            }
        }
    }

    private static boolean withinDaily(LocalTime time, LocalTime start, LocalTime end) {
        return !start.isAfter(end) ? (!time.isBefore(start) && time.isBefore(end))
                : (!time.isBefore(start) || time.isBefore(end));
    }

    private static void checkPrecedence(PrecedenceConstraint c, Map<Long, JobPlan> plansById,
                                        List<String> problems) {
        JobPlan before = plansById.get(c.beforeJobId());
        JobPlan after = plansById.get(c.afterJobId());
        if (before != null && after != null && after.start().isBefore(before.end())) {
            problems.add("job id " + c.afterJobId() + " starts before job id " + c.beforeJobId() + " finishes");
        }
    }

    private static void checkMutualExclusion(MutualExclusionConstraint c, Map<Long, JobPlan> plansById,
                                              List<String> problems) {
        List<Long> ids = c.jobIds();
        JobPlan a = plansById.get(ids.get(0));
        JobPlan b = plansById.get(ids.get(1));
        if (a != null && b != null && a.start().isBefore(b.end()) && b.start().isBefore(a.end())) {
            problems.add("job ids " + ids + " overlap despite mutual exclusion");
        }
    }

    private static void checkSynchronizedFinish(SynchronizedFinishConstraint c, Map<Long, JobPlan> plansById,
                                                 List<String> problems) {
        List<Long> ids = c.jobIds();
        long scheduledMembers = ids.stream().filter(plansById::containsKey).count();
        if (scheduledMembers > 0 && scheduledMembers < ids.size()) {
            problems.add("synchronized-finish group " + ids + " partially placed (" + scheduledMembers + " of "
                    + ids.size() + ")");
        }
        List<LocalDateTime> ends = ids.stream().map(plansById::get).filter(Objects::nonNull).map(JobPlan::end)
                .distinct().toList();
        if (ends.size() > 1) {
            problems.add("synchronized-finish group " + ids + " members do not all finish at the same hour: "
                    + ends);
        }
    }

    static Map<OptimizerJob, JobPlan> plansOf(BruteForceAllocator.Result result) {
        Map<OptimizerJob, JobPlan> plans = new HashMap<>();
        result.outcomes().forEach(outcome -> outcome.plan().ifPresent(plan -> plans.put(outcome.job(), plan)));
        return plans;
    }

    private static void addUse(Map<LocalDateTime, double[]> use, JobPlan plan) {
        for (SlotAllocation allocation : plan.allocations()) {
            double[] used = use.computeIfAbsent(allocation.slotStart(), slot -> new double[2]);
            used[0] += allocation.cpuHours(plan.job());
            used[1] += allocation.gpuHours(plan.job());
        }
    }
}
