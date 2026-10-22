package com.gacs.backend.service.allocation;

import com.gacs.backend.service.PriceSeries;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Checks a set of plans against every hard rule of the contract (section 6.4) and lists the broken
 * ones. Used on the brute-force checker now, and on the real solvers in Phase 5.
 * Shape rules (consecutive slots, shares add up to 1) are already enforced by {@link JobPlan}.
 */
final class ContractRules {

    private static final double TOLERANCE = 1e-9;

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
            if (used[0] > free.cpuHours() + TOLERANCE || used[1] > free.gpuHours() + TOLERANCE) {
                problems.add("slot " + slot + " is over capacity");
            }
        });
        return problems;
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
