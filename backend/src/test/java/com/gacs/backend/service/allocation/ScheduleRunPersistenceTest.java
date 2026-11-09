package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.repository.ConstraintImpactRepository;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.repository.ScheduleItemRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link ScheduleRunPersistence} called directly, as two genuinely separate steps - the one usage
 * pattern {@link ScheduleRunRecorderTest} never exercises, since it only ever calls the combined
 * {@code ScheduleRunRecorder.record(...)}. This is what actually proves
 * {@code finalizeExplanation} re-fetches live entities correctly rather than relying on the
 * {@code Persisted} instances {@code persist} returned (the detail the transaction split made
 * necessary - see both classes' javadoc).
 */
@SpringBootTest
@Transactional
class ScheduleRunPersistenceTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);
    private static final String OWNER = "operator@example.com";

    @Autowired
    private ScheduleRunPersistence persistence;
    @Autowired
    private ScheduleItemRepository items;
    @Autowired
    private ConstraintImpactRepository constraintImpacts;
    @Autowired
    private ScheduleExplanationRepository explanations;

    @Test
    @DisplayName("persist() alone returns the run, item and impacts with deterministic text already filled in")
    void persistAloneWritesDeterministicTextImmediately() {
        OptimizerJob job = job(40L);
        JobOutcome outcome = new JobOutcome(job, singleSlotPlan(job, T0), PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(List.of(outcome), List.of(dtoItem(outcome)));

        ScheduleRunPersistence.Persisted persisted = persistence.persist(
                new AllocationResult(List.of(outcome), true), List.of(), List.of(), response, OWNER);

        assertThat(persisted.run().getRunId()).isEqualTo(response.runId());
        assertThat(persisted.items()).hasSize(1);
        assertThat(persisted.items().get(0).getWhyThisPlacement()).isEqualTo("Placed on its cheapest feasible plan.");
        assertThat(persisted.impacts()).hasSize(2); // DEADLINE + FLEXIBILITY_WINDOW
        var details = persistence.jobExplanations(persisted.run().getId());
        assertThat(details).hasSize(1);
        assertThat(details.get(0).jobId()).isEqualTo(40L);
        assertThat(details.get(0).whyThisPlacement()).isEqualTo("Placed on its cheapest feasible plan.");
        assertThat(details.get(0).constraints()).extracting(c -> c.constraintType())
                .containsExactly("DEADLINE", "FLEXIBILITY_WINDOW");
        assertThat(details.get(0).constraints()).allSatisfy(c -> {
            assertThat(c.explanation()).isNotBlank();
            assertThat(c.relatedJobIds()).isEmpty();
        });
        // No explanation row yet - that is finalizeExplanation's job, not persist's.
        assertThat(explanations.findByScheduleRunId(persisted.run().getId())).isEmpty();
    }


    @Test
    @DisplayName("finalizeExplanation() stores the run's deterministic summary and leaves item text alone")
    void finalizeExplanationStoresDeterministicSummary() {
        OptimizerJob job = job(42L);
        JobOutcome outcome = new JobOutcome(job, singleSlotPlan(job, T0), PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(List.of(outcome), List.of(dtoItem(outcome)));
        ScheduleRunPersistence.Persisted persisted = persistence.persist(
                new AllocationResult(List.of(outcome), true), List.of(), List.of(), response, OWNER);

        persistence.finalizeExplanation(persisted.run().getId(), response, ignored -> { });

        var savedItem = items.findByScheduleRunIdOrderByIdAsc(persisted.run().getId()).get(0);
        assertThat(savedItem.getWhyThisPlacement()).isEqualTo("Placed on its cheapest feasible plan.");
        var savedExplanation = explanations.findByScheduleRunId(persisted.run().getId()).orElseThrow();
        assertThat(savedExplanation.getNarrative()).isEqualTo(response.plainEnglishExplanation());
    }

    // ---------------------------------------------------------------- fixtures

    private static OptimizerJob job(long id) {
        return new OptimizerJob(id, "job-" + id, 10.0, 0.0, T0, T0.plusHours(10), 6, false);
    }

    private static JobPlan singleSlotPlan(OptimizerJob job, LocalDateTime start) {
        return new JobPlan(job, List.of(new SlotAllocation(start, 1.0)));
    }

    private static OptimizeResponseDto.ScheduleItem dtoItem(JobOutcome outcome) {
        var job = outcome.job();
        var plan = outcome.plan();
        return new OptimizeResponseDto.ScheduleItem(job.id(), job.name(), job.originalStartTime(), plan.start(),
                plan.end(), job.cpuHours(), job.gpuHours(), List.of(), outcome.status().name(), null, 1.0, 1.0, 0.0);
    }

    private static OptimizeResponseDto response(List<JobOutcome> outcomes, List<OptimizeResponseDto.ScheduleItem> dtoItems) {
        boolean anyLeftOut = outcomes.stream().anyMatch(o -> o.status() == PerJobStatus.NO_FEASIBLE_SLOT);
        return new OptimizeResponseDto(UUID.randomUUID().toString(),
                anyLeftOut ? OptimizeResponseDto.STATUS_NEEDS_ATTENTION : OptimizeResponseDto.STATUS_SCHEDULED,
                true, 1.0, 1.0, 0.0, 0.0, "test plain-english explanation", false, dtoItems);
    }
}
