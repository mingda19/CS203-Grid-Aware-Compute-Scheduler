package com.gacs.backend.service;

import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.SolverInput;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure, exhaustive enumeration of every valid total assignment for a small {@link
 * SolverInput}, used as the ground-truth oracle in {@link ExactScheduleSolverTest}.
 *
 * <p>Deliberately encodes NO per-job ceiling (an assignment where one job's cost rises above
 * its own baseline is a legal candidate here, as long as the aggregate is lower) - this is the
 * single most important property of this class. The real solver targets the true global
 * minimum (see {@link ExactScheduleSolver}'s javadoc), not the greedy stub's self-imposed
 * "never pricier than my own baseline" rule, so an oracle that accidentally encoded that rule
 * would make {@link ExactScheduleSolverTest} pass for the wrong reason.
 *
 * <p>Exempt jobs are fixed at their baseline slot and are not enumerated. Each flexible job's
 * options are its {@link CandidateSlots#forJob} list plus one "unplaced" option (cost = its
 * own baseline cost, no capacity consumed) - mirroring exactly what {@link ExactScheduleSolver}
 * models, since the oracle must agree on what counts as feasible, not just on the objective.
 * Only small inputs are safe here: this is {@code product(|candidates_j| + 1)} total
 * combinations, fully materialized.
 */
final class BruteForceScheduleOracle {

    private BruteForceScheduleOracle() {
    }

    record Placement(JobSpec job, LocalDateTime slot, boolean unplaced) {
    }

    /**
     * The true minimum total raw (unrounded) cost over every capacity-feasible total
     * assignment, and one assignment that achieves it (for inspecting *what* moved, not just
     * the total).
     */
    record Optimum(double totalRawCost, List<Placement> placements) {
    }

    static Optimum solve(SolverInput input) {
        List<JobSpec> flexible = new ArrayList<>();
        List<Placement> exemptPlacements = new ArrayList<>();
        Map<LocalDateTime, double[]> exemptReserved = new HashMap<>();

        for (JobSpec job : input.jobs()) {
            if (job.exempt()) {
                LocalDateTime slot = job.baselineSlot();
                exemptPlacements.add(new Placement(job, slot, false));
                double[] used = exemptReserved.computeIfAbsent(slot, k -> new double[] {0.0, 0.0});
                used[0] += job.cpuHours();
                used[1] += job.gpuHours();
            } else {
                flexible.add(job);
            }
        }

        // Each flexible job's own option list: every real candidate slot, plus "unplaced".
        List<List<LocalDateTime>> optionsPerJob = new ArrayList<>();
        for (JobSpec job : flexible) {
            List<LocalDateTime> options = new ArrayList<>(CandidateSlots.forJob(job, input));
            options.add(null); // null = unplaced
            optionsPerJob.add(options);
        }

        Optimum[] best = {null};
        List<Placement> current = new ArrayList<>();
        enumerate(0, flexible, optionsPerJob, input, exemptReserved, current, 0.0, best);

        List<Placement> all = new ArrayList<>(exemptPlacements);
        all.addAll(best[0].placements());
        return new Optimum(best[0].totalRawCost(), List.copyOf(all));
    }

    private static void enumerate(
        int jobIdx, List<JobSpec> flexible, List<List<LocalDateTime>> optionsPerJob, SolverInput input,
        Map<LocalDateTime, double[]> reservedSoFar, List<Placement> current, double costSoFar, Optimum[] best
    ) {
        if (jobIdx == flexible.size()) {
            if (best[0] == null || costSoFar < best[0].totalRawCost()) {
                best[0] = new Optimum(costSoFar, List.copyOf(current));
            }
            return;
        }

        JobSpec job = flexible.get(jobIdx);
        double baselineCost = CostModel.cost(job.energyKwh(),
            input.prices().priceAt(job.baselineSlot()).orElse(0.0));

        for (LocalDateTime option : optionsPerJob.get(jobIdx)) {
            if (option == null) {
                current.add(new Placement(job, job.baselineSlot(), true));
                enumerate(jobIdx + 1, flexible, optionsPerJob, input, reservedSoFar, current,
                    costSoFar + baselineCost, best);
                current.remove(current.size() - 1);
                continue;
            }
            double[] used = reservedSoFar.getOrDefault(option, new double[] {0.0, 0.0});
            boolean fits = used[0] + job.cpuHours() <= input.capacity().maxConcurrentCpuHours()
                && used[1] + job.gpuHours() <= input.capacity().maxConcurrentGpuHours();
            if (!fits) {
                continue;
            }
            double[] next = {used[0] + job.cpuHours(), used[1] + job.gpuHours()};
            double[] previous = reservedSoFar.put(option, next);
            double price = input.prices().priceAt(option).orElseThrow();
            double cost = CostModel.cost(job.energyKwh(), price);

            current.add(new Placement(job, option, false));
            enumerate(jobIdx + 1, flexible, optionsPerJob, input, reservedSoFar, current,
                costSoFar + cost, best);
            current.remove(current.size() - 1);

            if (previous == null) {
                reservedSoFar.remove(option);
            } else {
                reservedSoFar.put(option, previous);
            }
        }
    }
}
