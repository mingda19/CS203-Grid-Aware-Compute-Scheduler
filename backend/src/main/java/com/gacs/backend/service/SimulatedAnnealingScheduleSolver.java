package com.gacs.backend.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.TreeMap;

/**
 * Deterministic simulated-annealing fallback for inputs {@link HybridScheduleSolver} judges
 * too large for {@link ExactScheduleSolver} to solve exactly within its time budget.
 *
 * <p>Initializes from {@link GreedyStubScheduleSolver}'s own result - deterministic and
 * feasible by construction - then explores only capacity/deadline-feasible neighbour states
 * (reassigning one job to another of its own {@link CandidateSlots}, or swapping two jobs'
 * slots), accepting worse moves via the standard Metropolis criterion with geometric cooling.
 * Deliberately not a penalty-based search over the infeasible region: that would need its own
 * penalty-weight tuning and is harder to keep auditable.
 *
 * <p>The PRNG is seeded from a stable hash of the input itself, never the wall clock, and
 * every data structure walked during the search is a sorted list or {@link TreeMap} - this is
 * what lets this class satisfy {@link ScheduleSolver}'s own determinism requirement despite
 * being a randomized algorithm internally.
 */
public final class SimulatedAnnealingScheduleSolver implements ScheduleSolver {

    private static final double COOLING_RATE = 0.95;
    private static final double STOP_TEMPERATURE_FRACTION = 1e-4;
    private static final int MAX_ITERATIONS = 20_000;
    private static final double SWAP_MOVE_PROBABILITY = 0.2;

    @Override
    public SolverResult solve(SolverInput input) {
        SolverResult seed = new GreedyStubScheduleSolver().solve(input);

        List<JobSpec> jobs = input.jobs();
        int n = jobs.size();
        List<Integer> flexibleIdx = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (!jobs.get(i).exempt()) {
                flexibleIdx.add(i);
            }
        }
        if (flexibleIdx.isEmpty()) {
            return seed;
        }

        List<List<LocalDateTime>> candidates = new ArrayList<>();
        for (int i : flexibleIdx) {
            candidates.add(CandidateSlots.forJob(jobs.get(i), input));
        }

        // Exempt reservations never change during the search - subtracted once up front.
        java.util.Map<LocalDateTime, double[]> exemptReserved = new TreeMap<>();
        for (JobSpec job : jobs) {
            if (job.exempt()) {
                double[] used = exemptReserved.computeIfAbsent(job.baselineSlot(), k -> new double[] {0.0, 0.0});
                used[0] += job.cpuHours();
                used[1] += job.gpuHours();
            }
        }

        // State: current[k] = slot for flexibleIdx.get(k), or null for "unplaced".
        LocalDateTime[] current = initialStateFrom(seed, flexibleIdx);
        java.util.Map<LocalDateTime, double[]> usedPerSlot = new TreeMap<>(exemptReserved);
        for (int k = 0; k < flexibleIdx.size(); k++) {
            if (current[k] != null) {
                claim(usedPerSlot, current[k], jobs.get(flexibleIdx.get(k)));
            }
        }

        double currentCost = totalRawCost(current, flexibleIdx, jobs, input);
        LocalDateTime[] best = current.clone();
        double bestCost = currentCost;

        Random rng = new Random(seedFor(input));
        double temperature = Math.max(0.1 * Math.max(currentCost, 1e-6), 1e-6);
        int perEpoch = Math.min(200, Math.max(20, 20 * flexibleIdx.size()));
        int iterations = 0;

        while (temperature > STOP_TEMPERATURE_FRACTION * Math.max(0.1 * Math.max(bestCost, 1e-6), 1e-6)
            && iterations < MAX_ITERATIONS) {
            for (int step = 0; step < perEpoch && iterations < MAX_ITERATIONS; step++, iterations++) {
                Move move = rng.nextDouble() < SWAP_MOVE_PROBABILITY && flexibleIdx.size() > 1
                    ? proposeSwap(rng, flexibleIdx, current, candidates, usedPerSlot, jobs, input)
                    : proposeReassign(rng, flexibleIdx, current, candidates, usedPerSlot, jobs, input);
                if (move == null) {
                    continue;
                }
                double delta = move.deltaCost();
                if (delta <= 0 || rng.nextDouble() < Math.exp(-delta / temperature)) {
                    move.apply(current, usedPerSlot);
                    currentCost += delta;
                    if (currentCost < bestCost - 1e-12) {
                        bestCost = currentCost;
                        best = current.clone();
                    }
                }
            }
            temperature *= COOLING_RATE;
        }

        return toResult(best, flexibleIdx, jobs);
    }

    // ---------------------------------------------------------------- moves

    private record Move(
        int k1, JobSpec job1, LocalDateTime oldSlot1, LocalDateTime newSlot1,
        Integer k2, JobSpec job2, LocalDateTime oldSlot2, LocalDateTime newSlot2,
        double deltaCost
    ) {
        /** Updates both the state array and the running per-slot usage in lockstep - every
         *  later feasibility check depends on {@code usedPerSlot} staying accurate. */
        void apply(LocalDateTime[] current, java.util.Map<LocalDateTime, double[]> usedPerSlot) {
            release(usedPerSlot, oldSlot1, job1);
            if (newSlot1 != null) {
                claim(usedPerSlot, newSlot1, job1);
            }
            current[k1] = newSlot1;
            if (k2 != null) {
                release(usedPerSlot, oldSlot2, job2);
                if (newSlot2 != null) {
                    claim(usedPerSlot, newSlot2, job2);
                }
                current[k2] = newSlot2;
            }
        }
    }

    private static Move proposeReassign(
        Random rng, List<Integer> flexibleIdx, LocalDateTime[] current, List<List<LocalDateTime>> candidates,
        java.util.Map<LocalDateTime, double[]> usedPerSlot, List<JobSpec> jobs, SolverInput input
    ) {
        int k = rng.nextInt(flexibleIdx.size());
        JobSpec job = jobs.get(flexibleIdx.get(k));
        List<LocalDateTime> options = candidates.get(k);
        // +1 slot for "unplaced" (represented as null), chosen with the same probability as
        // any real candidate.
        int choice = rng.nextInt(options.size() + 1);
        LocalDateTime proposed = choice == options.size() ? null : options.get(choice);
        if (java.util.Objects.equals(proposed, current[k])) {
            return null;
        }
        if (proposed != null && !fitsAfterRelease(usedPerSlot, current[k], proposed, job, input.capacity())) {
            return null;
        }
        double before = rawCostAt(job, current[k], input);
        double after = rawCostAt(job, proposed, input);
        return new Move(k, job, current[k], proposed, null, null, null, null, after - before);
    }

    private static Move proposeSwap(
        Random rng, List<Integer> flexibleIdx, LocalDateTime[] current, List<List<LocalDateTime>> candidates,
        java.util.Map<LocalDateTime, double[]> usedPerSlot, List<JobSpec> jobs, SolverInput input
    ) {
        int k1 = rng.nextInt(flexibleIdx.size());
        int k2 = rng.nextInt(flexibleIdx.size());
        if (k1 == k2) {
            return null;
        }
        JobSpec job1 = jobs.get(flexibleIdx.get(k1));
        JobSpec job2 = jobs.get(flexibleIdx.get(k2));
        LocalDateTime slot1 = current[k2]; // job1 takes job2's slot
        LocalDateTime slot2 = current[k1]; // job2 takes job1's slot
        if (!candidates.get(k1).contains(slot1) && slot1 != null) {
            return null;
        }
        if (!candidates.get(k2).contains(slot2) && slot2 != null) {
            return null;
        }
        // Release both, then check each new placement fits (independent slots in the common
        // case; when slot1 == slot2 this correctly checks the combined demand once).
        java.util.Map<LocalDateTime, double[]> trial = new TreeMap<>();
        for (var e : usedPerSlot.entrySet()) {
            trial.put(e.getKey(), e.getValue().clone());
        }
        release(trial, current[k1], job1);
        release(trial, current[k2], job2);
        if (slot1 != null && !fits(trial, slot1, job1, input.capacity())) {
            return null;
        }
        if (slot1 != null) {
            claim(trial, slot1, job1);
        }
        if (slot2 != null && !fits(trial, slot2, job2, input.capacity())) {
            return null;
        }

        double before = rawCostAt(job1, current[k1], input) + rawCostAt(job2, current[k2], input);
        double after = rawCostAt(job1, slot1, input) + rawCostAt(job2, slot2, input);
        return new Move(k1, job1, current[k1], slot1, k2, job2, current[k2], slot2, after - before);
    }

    /**
     * Would {@code job} fit at {@code newSlot} if it moved there from {@code oldSlot}
     * (possibly {@code null}, meaning it was unplaced and so held no capacity anywhere)?
     * Excludes the job's own existing contribution at {@code newSlot} first, for the (rare,
     * already filtered by the caller in the common case) edge where {@code newSlot} equals
     * {@code oldSlot}.
     */
    private static boolean fitsAfterRelease(
        java.util.Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime oldSlot, LocalDateTime newSlot,
        JobSpec job, ClusterCapacity capacity
    ) {
        double[] used = usedPerSlot.getOrDefault(newSlot, new double[] {0.0, 0.0});
        double cpu = used[0] - (newSlot.equals(oldSlot) ? job.cpuHours() : 0.0);
        double gpu = used[1] - (newSlot.equals(oldSlot) ? job.gpuHours() : 0.0);
        return cpu + job.cpuHours() <= capacity.maxConcurrentCpuHours()
            && gpu + job.gpuHours() <= capacity.maxConcurrentGpuHours();
    }

    // ---------------------------------------------------------------- bookkeeping

    private static double rawCostAt(JobSpec job, LocalDateTime slot, SolverInput input) {
        LocalDateTime priced = slot == null ? job.baselineSlot() : slot;
        double price = input.prices().priceAt(priced).orElse(0.0);
        return CostModel.cost(job.energyKwh(), price);
    }

    private static double totalRawCost(
        LocalDateTime[] state, List<Integer> flexibleIdx, List<JobSpec> jobs, SolverInput input
    ) {
        double total = 0.0;
        for (int k = 0; k < flexibleIdx.size(); k++) {
            total += rawCostAt(jobs.get(flexibleIdx.get(k)), state[k], input);
        }
        return total;
    }

    private static void claim(java.util.Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime slot, JobSpec job) {
        double[] used = usedPerSlot.computeIfAbsent(slot, k -> new double[] {0.0, 0.0});
        used[0] += job.cpuHours();
        used[1] += job.gpuHours();
    }

    private static void release(java.util.Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime slot, JobSpec job) {
        if (slot == null) {
            return;
        }
        double[] used = usedPerSlot.get(slot);
        if (used != null) {
            used[0] -= job.cpuHours();
            used[1] -= job.gpuHours();
        }
    }

    private static boolean fits(
        java.util.Map<LocalDateTime, double[]> usedPerSlot, LocalDateTime slot, JobSpec job, ClusterCapacity capacity
    ) {
        double[] used = usedPerSlot.getOrDefault(slot, new double[] {0.0, 0.0});
        return used[0] + job.cpuHours() <= capacity.maxConcurrentCpuHours()
            && used[1] + job.gpuHours() <= capacity.maxConcurrentGpuHours();
    }

    private static LocalDateTime[] initialStateFrom(SolverResult seed, List<Integer> flexibleIdx) {
        LocalDateTime[] state = new LocalDateTime[flexibleIdx.size()];
        for (int k = 0; k < flexibleIdx.size(); k++) {
            SolverAssignment a = seed.assignments().get(flexibleIdx.get(k));
            state[k] = a.status() == PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT ? null : a.slotStart();
        }
        return state;
    }

    private static SolverResult toResult(LocalDateTime[] state, List<Integer> flexibleIdx, List<JobSpec> jobs) {
        int n = jobs.size();
        SolverAssignment[] assignments = new SolverAssignment[n];
        for (int i = 0; i < n; i++) {
            JobSpec job = jobs.get(i);
            if (job.exempt()) {
                assignments[i] = new SolverAssignment(job, job.baselineSlot(), PerJobStatus.UNCHANGED_EXEMPT);
            }
        }
        for (int k = 0; k < flexibleIdx.size(); k++) {
            int i = flexibleIdx.get(k);
            JobSpec job = jobs.get(i);
            LocalDateTime slot = state[k];
            if (slot == null) {
                assignments[i] = new SolverAssignment(job, job.baselineSlot(), PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT);
            } else {
                PerJobStatus status = slot.equals(job.baselineSlot())
                    ? PerJobStatus.UNCHANGED_ALREADY_OPTIMAL : PerJobStatus.RESCHEDULED;
                assignments[i] = new SolverAssignment(job, slot, status);
            }
        }
        return new SolverResult(List.of(assignments), false);
    }

    /** Stable across JVM runs: folds JDK-spec'd value-based hashCodes, never a wall clock. */
    private static long seedFor(SolverInput input) {
        List<JobSpec> sorted = new ArrayList<>(input.jobs());
        sorted.sort(Comparator.comparing((JobSpec j) -> j.id() == null ? Long.MIN_VALUE : j.id())
            .thenComparing(JobSpec::name, Comparator.nullsLast(Comparator.naturalOrder())));

        long h = 17;
        for (JobSpec job : sorted) {
            h = 31 * h + java.util.Objects.hashCode(job.id());
            h = 31 * h + java.util.Objects.hashCode(job.name());
            h = 31 * h + Double.hashCode(job.cpuHours());
            h = 31 * h + Double.hashCode(job.gpuHours());
            h = 31 * h + job.originalStartTime().hashCode();
            h = 31 * h + job.deadline().hashCode();
            h = 31 * h + job.flexibilityWindowHours();
            h = 31 * h + Boolean.hashCode(job.exempt());
        }
        h = 31 * h + Double.hashCode(input.capacity().maxConcurrentCpuHours());
        h = 31 * h + Double.hashCode(input.capacity().maxConcurrentGpuHours());
        h = 31 * h + input.horizonStart().hashCode();
        h = 31 * h + input.horizonHours();
        return h;
    }
}
