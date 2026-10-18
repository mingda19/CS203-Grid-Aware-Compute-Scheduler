package com.gacs.backend.service;

import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Deterministic greedy placement stub standing in for the real optimizer (GRID-125).
 *
 * <p>Walks each job's flexibility window and takes the cheapest slot that still meets
 * the job's deadline and fits inside the remaining per-slot capacity. It is a heuristic,
 * not an optimum: it will not back out an earlier job's placement to make room for a
 * later one. What it does guarantee are the invariants the acceptance tests assert,
 * namely that no non-exempt job is placed past its deadline, that no exempt job moves,
 * that no slot is overcommitted by jobs this solver placed, and that a job with no
 * feasible slot comes back at its original time rather than being dropped or erroring.
 *
 * <p>Processing order is fixed so runs are reproducible:
 * <ol>
 *   <li>Exempt jobs first. They are pinned to their original slot and so must claim
 *       capacity before anything flexible competes for it.</li>
 *   <li>Then flexible jobs, tightest first - fewest window hours, then earliest
 *       deadline, then id. A job with less freedom gets first pick of the cheap slots,
 *       which is both the better heuristic and a stable ordering.</li>
 * </ol>
 */
@Service
public class GreedyStubScheduleSolver implements ScheduleSolver {

    @Override
    public SolverResult solve(SolverInput input) {
        Map<LocalDateTime, double[]> usedPerSlot = new HashMap<>();
        List<SolverAssignment> assignments = new ArrayList<>();

        List<JobSpec> exempt = input.jobs().stream().filter(JobSpec::exempt).sorted(tightestFirst()).toList();
        List<JobSpec> flexible = input.jobs().stream().filter(job -> !job.exempt()).sorted(tightestFirst()).toList();

        // Exempt work is non-negotiable (GRID-86 / GRID-24): it stays at its original slot
        // even when that slot already breaches its own deadline or the capacity ceiling, and
        // it reserves that capacity so flexible jobs cannot claim it.
        for (JobSpec job : exempt) {
            LocalDateTime slot = job.baselineSlot();
            claim(usedPerSlot, slot, job);
            assignments.add(new SolverAssignment(job, slot, PerJobStatus.UNCHANGED_EXEMPT));
        }

        for (JobSpec job : flexible) {
            Optional<LocalDateTime> cheapest = cheapestFeasibleSlot(job, input, usedPerSlot);
            if (cheapest.isEmpty()) {
                // No slot in the window clears both the deadline and the remaining capacity.
                // Report it at its original time rather than failing the whole run, and do not
                // claim capacity for work that was never placed.
                assignments.add(new SolverAssignment(job, job.baselineSlot(), PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT));
                continue;
            }
            LocalDateTime slot = cheapest.get();
            claim(usedPerSlot, slot, job);
            PerJobStatus status = slot.equals(job.baselineSlot())
                ? PerJobStatus.UNCHANGED_ALREADY_OPTIMAL
                : PerJobStatus.RESCHEDULED;
            assignments.add(new SolverAssignment(job, slot, status));
        }

        // Return in the caller's original job order so the response is stable and readable
        // regardless of the internal processing order above.
        List<SolverAssignment> ordered = new ArrayList<>();
        for (JobSpec job : input.jobs()) {
            assignments.stream().filter(a -> a.job() == job).findFirst().ifPresent(ordered::add);
        }
        return new SolverResult(List.copyOf(ordered));
    }

    /**
     * Cheapest slot in {@code [baseline, baseline + flexibilityWindowHours]} that is priced,
     * inside the horizon, finishes by the deadline, and still has room. Ties break to the
     * earliest slot so the result does not depend on map iteration order.
     */
    private static Optional<LocalDateTime> cheapestFeasibleSlot(
        JobSpec job, SolverInput input, Map<LocalDateTime, double[]> usedPerSlot
    ) {
        LocalDateTime baseline = job.baselineSlot();
        LocalDateTime best = null;
        double bestPrice = Double.MAX_VALUE;

        // Never move a job into an hour dearer than the one it already sits in. Without this
        // guard a pinned exempt job can displace a flexible job out of its own baseline slot
        // and into a more expensive one, making the whole plan cost more than doing nothing.
        // Where the baseline hour has no price there is nothing to compare against, so the
        // ceiling is left open.
        double baselineCeiling = input.prices().priceAt(baseline).orElse(Double.MAX_VALUE);

        for (int offset = 0; offset <= job.flexibilityWindowHours(); offset++) {
            LocalDateTime slot = baseline.plusHours(offset);

            if (slot.isBefore(input.horizonStart()) || !slot.isBefore(input.horizonEnd())) {
                continue;
            }
            // A one-hour job starting at `slot` ends at slot+1h; finishing exactly on the
            // deadline is allowed, finishing after it is not.
            if (slot.plusHours(1).isAfter(job.deadline())) {
                continue;
            }
            Optional<Double> price = input.prices().priceAt(slot);
            if (price.isEmpty()) {
                continue;
            }
            if (price.get() > baselineCeiling) {
                continue;
            }
            if (!fits(usedPerSlot, slot, job, input.capacity())) {
                continue;
            }
            if (price.get() < bestPrice) {
                bestPrice = price.get();
                best = slot;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean fits(
        Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime slot, JobSpec job, ClusterCapacity capacity
    ) {
        double[] used = usedPerSlot.getOrDefault(slot, new double[] {0.0, 0.0});
        return used[0] + job.cpuHours() <= capacity.maxConcurrentCpuHours()
            && used[1] + job.gpuHours() <= capacity.maxConcurrentGpuHours();
    }

    private static void claim(Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime slot, JobSpec job) {
        double[] used = usedPerSlot.computeIfAbsent(slot, key -> new double[] {0.0, 0.0});
        used[0] += job.cpuHours();
        used[1] += job.gpuHours();
    }

    private static Comparator<JobSpec> tightestFirst() {
        return Comparator.comparingInt(JobSpec::flexibilityWindowHours)
            .thenComparing(JobSpec::deadline)
            .thenComparing(JobSpec::id, Comparator.nullsLast(Comparator.naturalOrder()))
            .thenComparing(JobSpec::name, Comparator.nullsLast(Comparator.naturalOrder()));
    }
}
