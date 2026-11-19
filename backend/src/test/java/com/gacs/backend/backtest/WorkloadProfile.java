package com.gacs.backend.backtest;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * A named, versioned description of one day's workload (docs/backtest-plan.md section 3.1). There is no record
 * of real jobs, so the backtest generates them from profiles like this one, with fixed seeds.
 *
 * <p>All times are hours of a UTC day (0-23). Jobs start and finish inside the day (decision D5).
 *
 * @param description what the profile represents, for people reading results
 * @param version  bump whenever a profile changes, so old results are never mistaken for new ones
 * @param seed     base seed; each day's seed is derived from it and the date
 * @param jobsPerDay how many jobs to try to place each day
 * @param classes  kinds of job, picked at random by weight
 */
public record WorkloadProfile(String name, String description, int version, long seed, Range jobsPerDay,
                              List<JobClass> classes) {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An inclusive range of whole numbers. */
    public record Range(int min, int max) {
        public Range {
            if (min > max) {
                throw new IllegalArgumentException("range min " + min + " is above max " + max);
            }
        }
    }

    /**
     * One kind of job.
     *
     * @param startHours         hours the job normally starts at in the fixed schedule
     * @param flexibilityHours   how many hours the job may be delayed
     * @param deadlineSlackHours spare hours between the latest possible finish and the deadline
     * @param exemptShare        share of these jobs marked exempt (never moved), 0 to 1
     */
    public record JobClass(String name, double weight, Range cpuHours, Range gpuHours, List<Integer> startHours,
                           Range flexibilityHours, Range deadlineSlackHours, double exemptShare) {
    }

    public WorkloadProfile {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("profile name is required");
        }
        if (jobsPerDay == null || jobsPerDay.min() < 1) {
            throw new IllegalArgumentException(name + ": jobsPerDay.min must be at least 1");
        }
        if (classes == null || classes.isEmpty()) {
            throw new IllegalArgumentException(name + ": at least one job class is required");
        }
        classes = List.copyOf(classes);
        for (JobClass c : classes) {
            String where = name + "." + c.name();
            if (!(c.weight() > 0)) {
                throw new IllegalArgumentException(where + ": weight must be above 0");
            }
            if (c.cpuHours().min() < 0 || c.gpuHours().min() < 0 || c.cpuHours().max() + c.gpuHours().max() <= 0) {
                throw new IllegalArgumentException(where + ": work must be non-negative and not always zero");
            }
            if (c.startHours() == null || c.startHours().isEmpty()
                    || c.startHours().stream().anyMatch(h -> h < 0 || h > 23)) {
                throw new IllegalArgumentException(where + ": startHours must be hours 0-23");
            }
            if (c.flexibilityHours().min() < 0 || c.deadlineSlackHours().min() < 0) {
                throw new IllegalArgumentException(where + ": flexibility and slack must be 0 or more");
            }
            if (c.exemptShare() < 0 || c.exemptShare() > 1) {
                throw new IllegalArgumentException(where + ": exemptShare must be between 0 and 1");
            }
        }
    }

    public static WorkloadProfile load(Path file) {
        try {
            return JSON.readValue(file.toFile(), WorkloadProfile.class);
        } catch (IOException e) {
            throw new IllegalArgumentException("cannot read workload profile " + file + ": " + e.getMessage(), e);
        }
    }

    /** Name plus version, e.g. {@code mixed@v1}: how results refer to the profile. */
    public String id() {
        return name + "@v" + version;
    }
}
