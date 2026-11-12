package com.gacs.backend.backtest;

import com.gacs.backend.backtest.WorkloadGenerator.GeneratedDay;
import com.gacs.backend.backtest.WorkloadProfile.JobClass;
import com.gacs.backend.backtest.WorkloadProfile.Range;
import com.gacs.backend.service.allocation.BaselineCalculator;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.service.allocation.JobPlan;
import com.gacs.backend.service.allocation.OptimizerJob;
import com.gacs.backend.service.allocation.SlotAllocation;
import com.gacs.backend.service.allocation.SlotCapacity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Ticket 1: workload profiles and the generator (docs/backtest-plan.md sections 3.1 and 3.5). */
class WorkloadGeneratorTest {

    private static final Path PROFILES = Path.of("src/test/resources/backtest/profiles");
    private static final CapacityLimit FACILITY = new CapacityLimit(256.0, 64.0);
    private static final WorkloadGenerator GENERATOR = new WorkloadGenerator(SlotCapacity.uniform(FACILITY));
    private static final LocalDate DAY = LocalDate.of(2025, 3, 14);

    private static List<WorkloadProfile> committedProfiles() throws IOException {
        try (Stream<Path> files = Files.list(PROFILES)) {
            return files.filter(f -> f.toString().endsWith(".json")).sorted().map(WorkloadProfile::load).toList();
        }
    }

    @Test
    @DisplayName("Every committed profile loads and passes validation")
    void committedProfilesLoad() throws IOException {
        assertThat(committedProfiles()).extracting(WorkloadProfile::id)
                .containsExactly("business-hours-training@v1", "mixed@v1", "nightly-batch@v1", "tight@v1");
    }

    @Test
    @DisplayName("The same profile and day always give the same jobs; another day gives different ones")
    void deterministicPerDay() throws IOException {
        for (WorkloadProfile profile : committedProfiles()) {
            GeneratedDay first = GENERATOR.generate(profile, DAY);
            GeneratedDay again = GENERATOR.generate(profile, DAY);
            assertThat(again).isEqualTo(first);
            assertThat(GENERATOR.generate(profile, DAY.plusDays(1)).jobs()).isNotEqualTo(first.jobs());
        }
    }

    @Test
    @DisplayName("On every day, the fixed schedule fits capacity and every job fits inside the day (D2, D5)")
    void fixedScheduleAlwaysFits() throws IOException {
        BaselineCalculator baselines = new BaselineCalculator(SlotCapacity.uniform(FACILITY));
        for (WorkloadProfile profile : committedProfiles()) {
            for (int d = 0; d < 400; d++) {
                LocalDate day = DAY.plusDays(d);
                GeneratedDay generated = GENERATOR.generate(profile, day);
                assertThat(generated.jobs()).as("%s %s has jobs", profile.id(), day).isNotEmpty();

                Map<LocalDateTime, double[]> used = new HashMap<>();
                for (OptimizerJob job : generated.jobs()) {
                    JobPlan baseline = baselines.baseline(job).plan()
                            .orElseThrow(() -> new AssertionError(job.label() + " cannot finish alone"));
                    assertThat(job.originalStartTime().toLocalDate()).isEqualTo(day);
                    assertThat(job.deadline()).isBeforeOrEqualTo(day.plusDays(1).atStartOfDay());
                    assertThat(baseline.end()).isBeforeOrEqualTo(job.deadline());
                    if (job.exempt()) {
                        assertThat(job.flexibilityWindowHours()).isZero();
                    }
                    for (SlotAllocation slot : baseline.allocations()) {
                        double[] hours = used.computeIfAbsent(slot.slotStart(), s -> new double[2]);
                        hours[0] += slot.cpuHours(job);
                        hours[1] += slot.gpuHours(job);
                    }
                }
                used.forEach((hour, hours) -> {
                    assertThat(hours[0]).as("%s %s CPU at %s", profile.id(), day, hour).isLessThanOrEqualTo(256.0 + 1e-6);
                    assertThat(hours[1]).as("%s %s GPU at %s", profile.id(), day, hour).isLessThanOrEqualTo(64.0 + 1e-6);
                });
                assertThat(generated.jobs()).extracting(OptimizerJob::id)
                        .containsExactlyElementsOf(Stream.iterate(1L, i -> i + 1).limit(generated.jobs().size()).toList());
            }
        }
    }

    @Test
    @DisplayName("A job that can never fit the facility is given up on and counted, not looped on forever")
    void impossibleJobsAreCounted() {
        JobClass huge = new JobClass("huge", 1.0, new Range(100_000, 100_000), new Range(0, 0), List.of(0),
                new Range(0, 0), new Range(0, 0), 0.0);
        WorkloadProfile profile = new WorkloadProfile("huge", null, 1, 7, new Range(3, 3), List.of(huge));

        GeneratedDay generated = GENERATOR.generate(profile, DAY);
        assertThat(generated.jobs()).isEmpty();
        assertThat(generated.jobsNotPlaced()).isEqualTo(3);
        assertThat(generated.rejectedDraws()).isEqualTo(3 * WorkloadGenerator.MAX_DRAWS_PER_JOB);
    }

    @Test
    @DisplayName("Invalid profiles are rejected with the field named")
    void invalidProfilesRejected() {
        JobClass ok = new JobClass("ok", 1.0, new Range(1, 2), new Range(0, 0), List.of(5), new Range(0, 1),
                new Range(0, 1), 0.0);
        assertThatThrownBy(() -> new WorkloadProfile("p", null, 1, 1, new Range(0, 2), List.of(ok)))
                .hasMessageContaining("jobsPerDay.min");
        assertThatThrownBy(() -> new WorkloadProfile("p", null, 1, 1, new Range(1, 2), List.of(
                new JobClass("bad", 1.0, new Range(1, 2), new Range(0, 0), List.of(24), new Range(0, 1),
                        new Range(0, 1), 0.0))))
                .hasMessageContaining("startHours");
        assertThatThrownBy(() -> new Range(3, 1)).hasMessageContaining("above max");
    }
}
