package com.gacs.backend.backtest;

import com.gacs.backend.backtest.WorkloadProfile.JobClass;
import com.gacs.backend.backtest.WorkloadProfile.Range;
import com.gacs.backend.service.allocation.BaselineCalculator;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.service.allocation.JobPlan;
import com.gacs.backend.service.allocation.OptimizerJob;
import com.gacs.backend.service.allocation.SlotAllocation;
import com.gacs.backend.service.allocation.SlotCapacity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;

/**
 * Turns (profile, day) into that day's jobs, the same way every time (docs/backtest-plan.md section 3.1).
 *
 * <p>Decision D2: the fixed schedule (every job at its original time) must fit the facility's capacity, because a
 * real fixed schedule has to. Each drawn job is admitted only if its baseline still fits alongside the jobs already
 * admitted; otherwise it is drawn again, and draws that never fit are counted. With every accepted day fitting, the
 * fixed schedule and the optimiser's baseline are the same, so savings are measured against a schedule that could
 * really run.
 */
public final class WorkloadGenerator {

    /** Attempts per job before giving up on it. */
    static final int MAX_DRAWS_PER_JOB = 50;

    private final SlotCapacity capacity;
    private final BaselineCalculator baselines;

    public WorkloadGenerator(SlotCapacity capacity) {
        this.capacity = capacity;
        this.baselines = new BaselineCalculator(capacity);
    }

    /**
     * @param jobs             the admitted jobs, ids 1..n in draw order
     * @param jobClasses       each job's class name, by job id
     * @param rejectedDraws    draws thrown away because they did not fit the day or the capacity
     * @param jobsNotPlaced    jobs given up on after {@link #MAX_DRAWS_PER_JOB} draws
     */
    public record GeneratedDay(LocalDate day, List<OptimizerJob> jobs, Map<Long, String> jobClasses,
                               int rejectedDraws, int jobsNotPlaced) {
        public GeneratedDay {
            jobs = List.copyOf(jobs);
            jobClasses = Map.copyOf(jobClasses);
        }
    }

    public GeneratedDay generate(WorkloadProfile profile, LocalDate day) {
        Random random = new Random(daySeed(profile, day));
        LocalDateTime dayStart = day.atStartOfDay();
        int wanted = between(random, profile.jobsPerDay());

        List<OptimizerJob> jobs = new ArrayList<>();
        Map<Long, String> classes = new HashMap<>();
        Map<LocalDateTime, double[]> used = new HashMap<>();
        int rejected = 0;
        int givenUp = 0;

        for (int n = 0; n < wanted; n++) {
            long id = jobs.size() + 1L;
            Optional<Draw> admitted = Optional.empty();
            for (int attempt = 0; attempt < MAX_DRAWS_PER_JOB && admitted.isEmpty(); attempt++) {
                Draw draw = draw(profile, random, id, dayStart);
                if (draw != null && fits(draw.baseline(), used)) {
                    admitted = Optional.of(draw);
                } else {
                    rejected++;
                }
            }
            if (admitted.isPresent()) {
                Draw draw = admitted.get();
                jobs.add(draw.job());
                classes.put(id, draw.jobClass());
                reserve(draw.baseline(), used);
            } else {
                givenUp++;
            }
        }
        return new GeneratedDay(day, jobs, classes, rejected, givenUp);
    }

    /** Stable across runs and JVMs: built only from the profile seed and the date. */
    static long daySeed(WorkloadProfile profile, LocalDate day) {
        return profile.seed() * 1_000_003L + day.toEpochDay();
    }

    private record Draw(OptimizerJob job, JobPlan baseline, String jobClass) {
    }

    /** One random job, or null when it cannot start and finish inside the day. */
    private Draw draw(WorkloadProfile profile, Random random, long id, LocalDateTime dayStart) {
        JobClass jobClass = pick(profile.classes(), random);
        int cpu = between(random, jobClass.cpuHours());
        int gpu = between(random, jobClass.gpuHours());
        int start = jobClass.startHours().get(random.nextInt(jobClass.startHours().size()));
        int flexibility = between(random, jobClass.flexibilityHours());
        int slack = between(random, jobClass.deadlineSlackHours());
        boolean exempt = random.nextDouble() < jobClass.exemptShare();
        if (cpu == 0 && gpu == 0) {
            return null;
        }

        LocalDateTime dayEnd = dayStart.plusDays(1);
        LocalDateTime startTime = dayStart.plusHours(start);
        // How long the job takes alone on the cluster, with the whole rest of the day as its deadline.
        OptimizerJob probe = new OptimizerJob(id, jobClass.name() + "-" + id, cpu, gpu, startTime, dayEnd, 0, exempt);
        Optional<JobPlan> alone = baselines.baseline(probe).plan();
        if (alone.isEmpty()) {
            return null; // cannot finish inside the day even alone
        }
        int hours = alone.get().slotCount();
        int roomAfterStart = 24 - start - hours;
        int flex = exempt ? 0 : Math.min(flexibility, roomAfterStart);
        int deadlineHour = Math.min(24, start + flex + hours + slack);

        OptimizerJob job = new OptimizerJob(id, jobClass.name() + "-" + id, cpu, gpu, startTime,
                dayStart.plusHours(deadlineHour), flex, exempt);
        JobPlan baseline = baselines.baseline(job).plan().orElseThrow();
        return new Draw(job, baseline, jobClass.name());
    }

    private boolean fits(JobPlan baseline, Map<LocalDateTime, double[]> used) {
        for (SlotAllocation slot : baseline.allocations()) {
            double[] hours = used.getOrDefault(slot.slotStart(), new double[2]);
            CapacityLimit limit = capacity.limitAt(slot.slotStart());
            if (hours[0] + slot.cpuHours(baseline.job()) > limit.cpuHours() + 1e-9
                    || hours[1] + slot.gpuHours(baseline.job()) > limit.gpuHours() + 1e-9) {
                return false;
            }
        }
        return true;
    }

    private static void reserve(JobPlan baseline, Map<LocalDateTime, double[]> used) {
        for (SlotAllocation slot : baseline.allocations()) {
            double[] hours = used.computeIfAbsent(slot.slotStart(), s -> new double[2]);
            hours[0] += slot.cpuHours(baseline.job());
            hours[1] += slot.gpuHours(baseline.job());
        }
    }

    private static JobClass pick(List<JobClass> classes, Random random) {
        double total = classes.stream().mapToDouble(JobClass::weight).sum();
        double point = random.nextDouble() * total;
        for (JobClass c : classes) {
            point -= c.weight();
            if (point < 0) {
                return c;
            }
        }
        return classes.getLast();
    }

    private static int between(Random random, Range range) {
        return range.min() + random.nextInt(range.max() - range.min() + 1);
    }
}
