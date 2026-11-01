package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.repository.ConstraintImpactRepository;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.repository.ScheduleItemRepository;
import com.gacs.backend.repository.ScheduleRunRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence tests for {@link ScheduleRunRecorder} (docs/schedule-explanation-design.md sections
 * 1, 2.7, 3-5): what actually lands in the database, both for the deterministic-fallback path
 * (Phase A, still the default with no API key configured) and the LLM path (Phase C). Real H2
 * database, matching the {@code JobRepositoryTest} convention - not mocked repositories, since the
 * thing actually worth proving here is that rows land correctly, with correct keys and constraints.
 *
 * <p>{@code gacs.optimiser.explanation.enabled=true} is set for this whole class - safe for the
 * Phase A tests below, which never configure a client, since {@link ExplanationGenerator} checks
 * for a client before it ever checks {@code enabled} (so they still see {@code NOT_CONFIGURED}
 * either way); it is what lets the Phase C tests actually reach their hand-set fake client.
 */
@SpringBootTest
@TestPropertySource(properties = "gacs.optimiser.explanation.enabled=true")
@Transactional
class ScheduleRunRecorderTest {

    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 20, 10, 0);
    private static final String OWNER = "operator@example.com";

    @Autowired
    private ScheduleRunRecorder recorder;
    @Autowired
    private ScheduleRunRepository runs;
    @Autowired
    private ScheduleItemRepository items;
    @Autowired
    private ConstraintImpactRepository constraintImpacts;
    @Autowired
    private ScheduleExplanationRepository explanations;
    @Autowired
    private ExplanationGenerator explanationGenerator;

    /** The generator bean is a shared singleton across test methods; never let one test's fake
     *  client leak into the next. */
    @AfterEach
    void resetExplanationClient() {
        explanationGenerator.setClient(null);
    }

    @Test
    @DisplayName("an OPTIMISED job persists a run, an item, and both DEADLINE + FLEXIBILITY_WINDOW impacts")
    void optimisedJobPersistsRunItemAndBothConstraintImpacts() {
        OptimizerJob job = job(1L, T0, T0.plusHours(10), 6);
        JobPlan plan = singleSlotPlan(job, T0.plusHours(3));
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        String runId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(runId, List.of(outcome), List.of(dtoItem(outcome, 1.0, 0.5)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var savedRun = runs.findByRunId(runId).orElseThrow();
        assertThat(savedRun.getOwnerEmail()).isEqualTo(OWNER);
        assertThat(savedRun.getStatus()).isEqualTo("SCHEDULED");
        assertThat(savedRun.isProvenOptimal()).isTrue();
        assertThat(savedRun.getBaselineCostUsd()).isEqualTo(1.0);
        assertThat(savedRun.getOptimizedCostUsd()).isEqualTo(0.5);

        var savedItems = items.findByScheduleRunIdOrderByIdAsc(savedRun.getId());
        assertThat(savedItems).hasSize(1);
        var item = savedItems.get(0);
        assertThat(item.getJobId()).isEqualTo(1L);
        assertThat(item.getPerJobStatus()).isEqualTo("OPTIMISED");
        assertThat(item.getBaselineCostUsd()).isEqualTo(1.0);
        assertThat(item.getOptimizedCostUsd()).isEqualTo(0.5);
        assertThat(item.getWhyThisPlacement()).isEqualTo("Placed on its cheapest feasible plan.");

        var impacts = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId());
        assertThat(impacts).hasSize(2);
        assertThat(impacts).extracting(ConstraintImpact::getConstraintType)
                .containsExactlyInAnyOrder("DEADLINE", "FLEXIBILITY_WINDOW");
        assertThat(impacts).allSatisfy(i -> assertThat(i.getRelatedJobIds())
                .as("Phase A only has single-job constraint types - relatedJobIds is always empty").isNull());
    }

    @Test
    @DisplayName("an exempt job gets a DEADLINE impact only, never FLEXIBILITY_WINDOW")
    void exemptJobGetsDeadlineImpactOnlyNotFlexibilityWindow() {
        OptimizerJob job = exemptJob(2L, T0, T0.plusHours(5));
        JobPlan plan = singleSlotPlan(job, T0);
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.UNCHANGED_EXEMPT, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        assertThat(item.getWhyThisPlacement()).isEqualTo("Kept at its original time because it is exempt.");
        var impacts = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId());
        assertThat(impacts).hasSize(1);
        assertThat(impacts.get(0).getConstraintType()).isEqualTo("DEADLINE");
    }

    @Test
    @DisplayName("a NO_FEASIBLE_SLOT job gets zero constraint impacts, and its reason becomes whyThisPlacement verbatim")
    void noFeasibleSlotJobGetsNoConstraintImpactsAndReasonAsWhyThisPlacement() {
        OptimizerJob job = job(3L, T0, T0.plusHours(2), 0);
        String reason = "Cluster full between 10:00 and 12:00 UTC: other jobs use the capacity this job needs.";
        JobOutcome outcome = new JobOutcome(job, null, PerJobStatus.NO_FEASIBLE_SLOT, reason);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItemUnscheduled(outcome)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        assertThat(item.getScheduledStart()).isNull();
        assertThat(item.getScheduledEnd()).isNull();
        assertThat(item.getWhyThisPlacement()).isEqualTo(reason);
        assertThat(constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId())).isEmpty();
    }

    @Test
    @DisplayName("the explanation is the deterministic fallback, reusing plainEnglishExplanation verbatim")
    void scheduleExplanationUsesDeterministicFallbackReusingPlainEnglishExplanation() {
        OptimizerJob job = job(4L, T0, T0.plusHours(5), 2);
        JobPlan plan = singleSlotPlan(job, T0);
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        String runId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(runId, List.of(outcome), List.of(dtoItem(outcome, 2.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var run = runs.findByRunId(runId).orElseThrow();
        var explanation = explanations.findByScheduleRunId(run.getId()).orElseThrow();
        assertThat(explanation.getSource()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(explanation.getFallbackReason()).isEqualTo("NOT_CONFIGURED");
        assertThat(explanation.getModelName()).isNull();
        assertThat(explanation.getRawResponseJson()).isNull();
        assertThat(explanation.getNarrative()).isEqualTo(response.plainEnglishExplanation());
        assertThat(explanation.getHeadline()).isNotBlank();
    }

    @Test
    @DisplayName("DEADLINE slack is the hours between scheduledEnd and the job's deadline")
    void deadlineSlackHoursIsComputedCorrectly() {
        // Deadline is 5h after the job's single-hour slot ends.
        OptimizerJob job = job(5L, T0, T0.plusHours(1).plusHours(5), 0);
        JobPlan plan = singleSlotPlan(job, T0); // ends at T0+1h
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        var deadlineImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId()).stream()
                .filter(i -> i.getConstraintType().equals("DEADLINE")).findFirst().orElseThrow();
        assertThat(deadlineImpact.getSlackHours()).isEqualTo(5L);
        assertThat(deadlineImpact.getNarrativeFragment()).contains("5h before its deadline");
    }

    @Test
    @DisplayName("finishing exactly at the deadline is worded as zero slack, not '0h before'")
    void exactlyAtDeadlineFragmentUsesNoSlackWording() {
        OptimizerJob job = job(6L, T0, T0.plusHours(1), 0); // deadline == scheduled end exactly
        JobPlan plan = singleSlotPlan(job, T0);
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        var deadlineImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId()).stream()
                .filter(i -> i.getConstraintType().equals("DEADLINE")).findFirst().orElseThrow();
        assertThat(deadlineImpact.getSlackHours()).isZero();
        assertThat(deadlineImpact.getNarrativeFragment()).isEqualTo("Finishes exactly at its deadline - no slack remaining.");
    }

    @Test
    @DisplayName("FLEXIBILITY_WINDOW slack is the hours of the window left UNUSED, not the shift itself")
    void flexibilityWindowSlackIsUnusedWindowNotShift() {
        // 6h window, job actually moved 3h later -> 3h of the window went unused.
        OptimizerJob job = job(7L, T0, T0.plusHours(20), 6);
        JobPlan plan = singleSlotPlan(job, T0.plusHours(3));
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        var windowImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId()).stream()
                .filter(i -> i.getConstraintType().equals("FLEXIBILITY_WINDOW")).findFirst().orElseThrow();
        assertThat(windowImpact.getSlackHours()).isEqualTo(3L); // 6h window - 3h used = 3h unused
        assertThat(windowImpact.getNarrativeFragment())
                .contains("Moved 3h later than its original start, using 3 of its 6h allowed window");
    }

    @Test
    @DisplayName("staying at the original start is worded as not needing the window, not 'moved 0h'")
    void zeroShiftFlexibilityWindowFragmentUsesOriginalTimeWording() {
        OptimizerJob job = job(8L, T0, T0.plusHours(20), 6);
        JobPlan plan = singleSlotPlan(job, T0); // no shift at all
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        var windowImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId()).stream()
                .filter(i -> i.getConstraintType().equals("FLEXIBILITY_WINDOW")).findFirst().orElseThrow();
        assertThat(windowImpact.getSlackHours()).isEqualTo(6L); // the whole window is unused
        assertThat(windowImpact.getNarrativeFragment())
                .isEqualTo("Started at its original time; none of its 6h allowed window was needed.");
    }

    @Test
    @DisplayName("a negative savingsUsd persists as-is - the true optimum can raise one job's cost")
    void negativeSavingsItemPersistsCorrectly() {
        OptimizerJob job = job(9L, T0, T0.plusHours(5), 2);
        JobPlan plan = singleSlotPlan(job, T0);
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.5))); // optimized cost higher than baseline

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var item = onlyItemFor(response);
        assertThat(item.getSavingsUsd()).isEqualTo(-0.5);
    }

    @Test
    @DisplayName("items preserve request order and are each keyed to the right job id")
    void itemsPreserveRequestOrderAndKeying() {
        OptimizerJob jobA = job(11L, T0, T0.plusHours(10), 2);
        OptimizerJob jobB = job(10L, T0, T0.plusHours(10), 2);
        JobOutcome outcomeA = new JobOutcome(jobA, singleSlotPlan(jobA, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome outcomeB = new JobOutcome(jobB, singleSlotPlan(jobB, T0), PerJobStatus.OPTIMISED, null);
        List<JobOutcome> outcomes = List.of(outcomeA, outcomeB); // deliberately not id-sorted
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(outcomeA, 1.0, 1.0), dtoItem(outcomeB, 1.0, 1.0)));

        recorder.record(new AllocationResult(outcomes, true), List.of(), response, OWNER);

        var run = runs.findByRunId(response.runId()).orElseThrow();
        var savedItems = items.findByScheduleRunIdOrderByIdAsc(run.getId());
        assertThat(savedItems).extracting(i -> i.getJobId()).containsExactly(11L, 10L);
    }

    @Test
    @DisplayName("two separate runs persist as two independent ScheduleRun rows, not merged")
    void runIdIsUniqueAcrossSeparateRecordCalls() {
        OptimizerJob job1 = job(12L, T0, T0.plusHours(5), 0);
        OptimizerJob job2 = job(13L, T0, T0.plusHours(5), 0);
        JobOutcome outcome1 = new JobOutcome(job1, singleSlotPlan(job1, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome outcome2 = new JobOutcome(job2, singleSlotPlan(job2, T0), PerJobStatus.OPTIMISED, null);
        String runId1 = UUID.randomUUID().toString();
        String runId2 = UUID.randomUUID().toString();

        recorder.record(new AllocationResult(List.of(outcome1), true), List.of(),
                response(runId1, List.of(outcome1), List.of(dtoItem(outcome1, 1.0, 1.0))), OWNER);
        recorder.record(new AllocationResult(List.of(outcome2), true), List.of(),
                response(runId2, List.of(outcome2), List.of(dtoItem(outcome2, 1.0, 1.0))), OWNER);

        assertThat(runs.findByRunId(runId1)).isPresent();
        assertThat(runs.findByRunId(runId2)).isPresent();
        assertThat(runs.findByRunId(runId1).get().getId()).isNotEqualTo(runs.findByRunId(runId2).get().getId());
    }

    @Test
    @DisplayName("the run_id unique constraint is real, not just documented")
    void duplicateRunIdViolatesUniqueConstraint() {
        OptimizerJob job = job(14L, T0, T0.plusHours(5), 0);
        JobOutcome outcome = new JobOutcome(job, singleSlotPlan(job, T0), PerJobStatus.OPTIMISED, null);
        String sharedRunId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(sharedRunId, List.of(outcome), List.of(dtoItem(outcome, 1.0, 1.0)));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        assertThatThrownBy(() ->
                recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("LLM success: ScheduleExplanation.source=LLM, and item/impact text is overwritten with the LLM's")
    void llmSuccessOverwritesTextAndPersistsLlmSource() {
        OptimizerJob job = job(15L, T0, T0.plusHours(10), 6);
        JobPlan plan = singleSlotPlan(job, T0.plusHours(3));
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        String runId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(runId, List.of(outcome), List.of(dtoItem(outcome, 1.0, 0.5)));

        LlmExplanationResponse llmResponse = new LlmExplanationResponse("LLM headline", "LLM narrative",
                List.of(new LlmExplanationResponse.JobExplanation(15L, "LLM: this job's own explanation")),
                List.of(
                        new LlmExplanationResponse.ConstraintExplanation(15L, "DEADLINE", List.of(), "LLM: deadline"),
                        new LlmExplanationResponse.ConstraintExplanation(15L, "FLEXIBILITY_WINDOW", List.of(),
                                "LLM: flex window")));
        explanationGenerator.setClient((run, savedItems, savedImpacts, timeout) ->
                ExplanationGenerationResult.success(llmResponse, "{\"raw\":true}"));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var run = runs.findByRunId(runId).orElseThrow();
        var explanation = explanations.findByScheduleRunId(run.getId()).orElseThrow();
        assertThat(explanation.getSource()).isEqualTo("LLM");
        assertThat(explanation.getFallbackReason()).isNull();
        assertThat(explanation.getModelName()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(explanation.getRawResponseJson()).isEqualTo("{\"raw\":true}");
        assertThat(explanation.getHeadline()).isEqualTo("LLM headline");
        assertThat(explanation.getNarrative()).isEqualTo("LLM narrative");

        var item = onlyItemFor(response);
        assertThat(item.getWhyThisPlacement()).isEqualTo("LLM: this job's own explanation");
        var impacts = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId());
        assertThat(impacts).extracting(ConstraintImpact::getNarrativeFragment)
                .containsExactlyInAnyOrder("LLM: deadline", "LLM: flex window");
    }

    @Test
    @DisplayName("LLM failure: falls back to DETERMINISTIC_FALLBACK with the real reason, text unchanged from Phase A")
    void llmFailureFallsBackToDeterministicTextUnchanged() {
        OptimizerJob job = job(16L, T0, T0.plusHours(10), 6);
        JobPlan plan = singleSlotPlan(job, T0.plusHours(3));
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        String runId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(runId, List.of(outcome), List.of(dtoItem(outcome, 1.0, 0.5)));

        explanationGenerator.setClient((run, savedItems, savedImpacts, timeout) ->
                ExplanationGenerationResult.failure(FallbackReason.TIMEOUT, null));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(), response, OWNER);

        var run = runs.findByRunId(runId).orElseThrow();
        var explanation = explanations.findByScheduleRunId(run.getId()).orElseThrow();
        assertThat(explanation.getSource()).isEqualTo("DETERMINISTIC_FALLBACK");
        assertThat(explanation.getFallbackReason()).isEqualTo("TIMEOUT");
        assertThat(explanation.getModelName()).isNull();
        assertThat(explanation.getNarrative()).isEqualTo(response.plainEnglishExplanation());

        // Exactly Phase A's wording - the LLM attempt having failed must not change a single word.
        var item = onlyItemFor(response);
        assertThat(item.getWhyThisPlacement()).isEqualTo("Placed on its cheapest feasible plan.");
        var impacts = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId());
        assertThat(impacts).extracting(ConstraintImpact::getNarrativeFragment)
                .containsExactlyInAnyOrder("Finishes 6h before its deadline.",
                        "Moved 3h later than its original start, using 3 of its 6h allowed window.");
    }

    @Test
    @DisplayName("ABSOLUTE_TIME_WINDOW: a declared constraint persists its own impact row alongside the intrinsic ones")
    void absoluteTimeWindowConstraintPersistsImpact() {
        OptimizerJob job = job(20L, T0, T0.plusHours(5), 2);
        JobOutcome outcome = new JobOutcome(job, singleSlotPlan(job, T0.plusHours(1)), PerJobStatus.OPTIMISED, null);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItem(outcome, 1.0, 1.0)));
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(20L, LocalTime.of(10, 0),
                LocalTime.of(18, 0));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(constraint), response, OWNER);

        var item = onlyItemFor(response);
        var impacts = constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId());
        assertThat(impacts).extracting(ConstraintImpact::getConstraintType)
                .containsExactlyInAnyOrder("DEADLINE", "FLEXIBILITY_WINDOW", "ABSOLUTE_TIME_WINDOW");
        var windowImpact = impacts.stream().filter(i -> i.getConstraintType().equals("ABSOLUTE_TIME_WINDOW"))
                .findFirst().orElseThrow();
        assertThat(windowImpact.getSlackHours()).isNull();
        assertThat(windowImpact.getNarrativeFragment()).contains("10:00").contains("18:00");
    }

    @Test
    @DisplayName("PRECEDENCE: only the after-job gets an impact row, with the real gap as slack")
    void precedenceConstraintPersistsImpactOnAfterJobOnly() {
        OptimizerJob before = job(21L, T0, T0.plusHours(5), 0);
        OptimizerJob after = job(22L, T0, T0.plusHours(10), 5);
        JobOutcome beforeOutcome = new JobOutcome(before, singleSlotPlan(before, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome afterOutcome = new JobOutcome(after, singleSlotPlan(after, T0.plusHours(3)), PerJobStatus.OPTIMISED,
                null);
        List<JobOutcome> outcomes = List.of(beforeOutcome, afterOutcome);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(beforeOutcome, 1.0, 1.0), dtoItem(afterOutcome, 1.0, 1.0)));
        PrecedenceConstraint constraint = new PrecedenceConstraint(21L, 22L);

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var beforeItem = itemFor(response, 21L);
        assertThat(constraintImpacts.findByScheduleItemIdOrderByIdAsc(beforeItem.getId()))
                .extracting(ConstraintImpact::getConstraintType).doesNotContain("PRECEDENCE");
        var afterItem = itemFor(response, 22L);
        var precedenceImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(afterItem.getId()).stream()
                .filter(i -> i.getConstraintType().equals("PRECEDENCE")).findFirst().orElseThrow();
        assertThat(precedenceImpact.getSlackHours()).isEqualTo(2L); // before ends T0+1h, after starts T0+3h
        assertThat(precedenceImpact.getRelatedJobIds()).isEqualTo("21");
        assertThat(precedenceImpact.getNarrativeFragment()).contains("21");
    }

    @Test
    @DisplayName("PRECEDENCE: before-job unscheduled -> after-job's impact has null slack and explains why")
    void precedenceConstraintWhenBeforeJobNotScheduled() {
        OptimizerJob before = job(23L, T0, T0.plusHours(5), 0);
        OptimizerJob after = job(24L, T0, T0.plusHours(10), 5);
        JobOutcome beforeOutcome = new JobOutcome(before, null, PerJobStatus.NO_FEASIBLE_SLOT, "no room");
        JobOutcome afterOutcome = new JobOutcome(after, singleSlotPlan(after, T0), PerJobStatus.OPTIMISED, null);
        List<JobOutcome> outcomes = List.of(beforeOutcome, afterOutcome);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItemUnscheduled(beforeOutcome), dtoItem(afterOutcome, 1.0, 1.0)));
        PrecedenceConstraint constraint = new PrecedenceConstraint(23L, 24L);

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var afterItem = itemFor(response, 24L);
        var precedenceImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(afterItem.getId()).stream()
                .filter(i -> i.getConstraintType().equals("PRECEDENCE")).findFirst().orElseThrow();
        assertThat(precedenceImpact.getSlackHours()).isNull();
        assertThat(precedenceImpact.getNarrativeFragment()).contains("not scheduled");
    }

    @Test
    @DisplayName("MUTUAL_EXCLUSION: both jobs get an impact row, each pointing at the other, with the same gap")
    void mutualExclusionConstraintPersistsImpactOnBothJobs() {
        OptimizerJob jobA = job(25L, T0, T0.plusHours(5), 0);
        OptimizerJob jobB = job(26L, T0, T0.plusHours(10), 5);
        JobOutcome outcomeA = new JobOutcome(jobA, singleSlotPlan(jobA, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome outcomeB = new JobOutcome(jobB, singleSlotPlan(jobB, T0.plusHours(4)), PerJobStatus.OPTIMISED, null);
        List<JobOutcome> outcomes = List.of(outcomeA, outcomeB);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(outcomeA, 1.0, 1.0), dtoItem(outcomeB, 1.0, 1.0)));
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(25L, 26L);

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var impactA = constraintImpacts.findByScheduleItemIdOrderByIdAsc(itemFor(response, 25L).getId()).stream()
                .filter(i -> i.getConstraintType().equals("MUTUAL_EXCLUSION")).findFirst().orElseThrow();
        var impactB = constraintImpacts.findByScheduleItemIdOrderByIdAsc(itemFor(response, 26L).getId()).stream()
                .filter(i -> i.getConstraintType().equals("MUTUAL_EXCLUSION")).findFirst().orElseThrow();
        assertThat(impactA.getSlackHours()).isEqualTo(3L); // A ends T0+1h, B starts T0+4h
        assertThat(impactB.getSlackHours()).isEqualTo(3L);
        assertThat(impactA.getRelatedJobIds()).isEqualTo("26");
        assertThat(impactB.getRelatedJobIds()).isEqualTo("25");
    }

    @Test
    @DisplayName("MUTUAL_EXCLUSION: other job unscheduled -> this job's impact has null slack")
    void mutualExclusionConstraintWhenOtherJobNotScheduled() {
        OptimizerJob jobA = job(27L, T0, T0.plusHours(5), 0);
        OptimizerJob jobB = job(28L, T0, T0.plusHours(10), 5);
        JobOutcome outcomeA = new JobOutcome(jobA, singleSlotPlan(jobA, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome outcomeB = new JobOutcome(jobB, null, PerJobStatus.NO_FEASIBLE_SLOT, "no room");
        List<JobOutcome> outcomes = List.of(outcomeA, outcomeB);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(outcomeA, 1.0, 1.0), dtoItemUnscheduled(outcomeB)));
        MutualExclusionConstraint constraint = new MutualExclusionConstraint(27L, 28L);

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var impactA = constraintImpacts.findByScheduleItemIdOrderByIdAsc(itemFor(response, 27L).getId()).stream()
                .filter(i -> i.getConstraintType().equals("MUTUAL_EXCLUSION")).findFirst().orElseThrow();
        assertThat(impactA.getSlackHours()).isNull();
        assertThat(impactA.getNarrativeFragment()).contains("not scheduled");
    }

    @Test
    @DisplayName("SYNCHRONIZED_FINISH: every member gets an impact row listing the others")
    void synchronizedFinishConstraintPersistsImpactOnEveryMember() {
        OptimizerJob jobA = job(29L, T0, T0.plusHours(5), 0);
        OptimizerJob jobB = job(30L, T0, T0.plusHours(5), 0);
        JobOutcome outcomeA = new JobOutcome(jobA, singleSlotPlan(jobA, T0), PerJobStatus.OPTIMISED, null);
        JobOutcome outcomeB = new JobOutcome(jobB, singleSlotPlan(jobB, T0), PerJobStatus.OPTIMISED, null);
        List<JobOutcome> outcomes = List.of(outcomeA, outcomeB);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(outcomeA, 1.0, 1.0), dtoItem(outcomeB, 1.0, 1.0)));
        SynchronizedFinishConstraint constraint = new SynchronizedFinishConstraint(List.of(29L, 30L));

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var impactA = constraintImpacts.findByScheduleItemIdOrderByIdAsc(itemFor(response, 29L).getId()).stream()
                .filter(i -> i.getConstraintType().equals("SYNCHRONIZED_FINISH")).findFirst().orElseThrow();
        var impactB = constraintImpacts.findByScheduleItemIdOrderByIdAsc(itemFor(response, 30L).getId()).stream()
                .filter(i -> i.getConstraintType().equals("SYNCHRONIZED_FINISH")).findFirst().orElseThrow();
        assertThat(impactA.getRelatedJobIds()).isEqualTo("30");
        assertThat(impactB.getRelatedJobIds()).isEqualTo("29");
    }

    @Test
    @DisplayName("SYNCHRONIZED_FINISH no-op edge case: a group with an exempt member gets no impact row at all")
    void synchronizedFinishConstraintNoOpWhenGroupHasAnExemptMember() {
        // SynchronizedGroupPlacer treats a group with fewer than 2 flexible members as a no-op, so the
        // flexible member here was placed with no regard to the exempt member's end time. The recorder
        // must check this empirically (same end time) rather than assume the constraint applied.
        OptimizerJob flexible = job(31L, T0, T0.plusHours(5), 2);
        OptimizerJob exempt = exemptJob(32L, T0, T0.plusHours(1));
        JobOutcome flexibleOutcome = new JobOutcome(flexible, singleSlotPlan(flexible, T0.plusHours(2)),
                PerJobStatus.OPTIMISED, null);
        JobOutcome exemptOutcome = new JobOutcome(exempt, singleSlotPlan(exempt, T0), PerJobStatus.UNCHANGED_EXEMPT,
                null);
        List<JobOutcome> outcomes = List.of(flexibleOutcome, exemptOutcome);
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), outcomes,
                List.of(dtoItem(flexibleOutcome, 1.0, 1.0), dtoItem(exemptOutcome, 1.0, 1.0)));
        SynchronizedFinishConstraint constraint = new SynchronizedFinishConstraint(List.of(31L, 32L));

        recorder.record(new AllocationResult(outcomes, true), List.of(constraint), response, OWNER);

        var flexibleItem = itemFor(response, 31L);
        assertThat(constraintImpacts.findByScheduleItemIdOrderByIdAsc(flexibleItem.getId()))
                .extracting(ConstraintImpact::getConstraintType).doesNotContain("SYNCHRONIZED_FINISH");
    }

    @Test
    @DisplayName("NO_FEASIBLE_SLOT with a declared constraint attached still gets zero constraint impacts")
    void noFeasibleSlotJobWithDeclaredConstraintGetsNoImpacts() {
        OptimizerJob job = job(33L, T0, T0.plusHours(2), 0);
        JobOutcome outcome = new JobOutcome(job, null, PerJobStatus.NO_FEASIBLE_SLOT, "no room");
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), List.of(outcome),
                List.of(dtoItemUnscheduled(outcome)));
        AbsoluteTimeWindowConstraint constraint = new AbsoluteTimeWindowConstraint(33L, LocalTime.of(0, 0),
                LocalTime.of(23, 0));

        recorder.record(new AllocationResult(List.of(outcome), true), List.of(constraint), response, OWNER);

        var item = onlyItemFor(response);
        assertThat(constraintImpacts.findByScheduleItemIdOrderByIdAsc(item.getId())).isEmpty();
    }

    @Test
    @DisplayName("End-to-end: a real solver's precedence-respecting placement persists the correct PRECEDENCE impact")
    void realSolverPrecedenceResultPersistsCorrectImpact() {
        OptimizerJob before = job(34L, T0, T0.plusHours(2), 2);
        OptimizerJob after = job(35L, T0, T0.plusHours(4), 3);
        PrecedenceConstraint constraint = new PrecedenceConstraint(34L, 35L);
        OptimizationProblem problem = Fixtures.problem(List.of(before, after), Fixtures.prices(1, 1, 1, 1, 1, 1),
                Fixtures.cpuCapacity(256.0), List.of(constraint));
        AllocationResult result = new HybridAllocationSolver(Fixtures.COSTS).solve(problem);
        assertThat(result.scheduledCount()).isEqualTo(2); // sanity: both actually got placed, not dropped

        List<OptimizeResponseDto.ScheduleItem> dtoItems = result.outcomes().stream()
                .map(outcome -> dtoItem(outcome, 1.0, 1.0)).toList();
        OptimizeResponseDto response = response(UUID.randomUUID().toString(), result.outcomes(), dtoItems);

        recorder.record(result, List.of(constraint), response, OWNER);

        var afterItem = itemFor(response, 35L);
        var precedenceImpact = constraintImpacts.findByScheduleItemIdOrderByIdAsc(afterItem.getId()).stream()
                .filter(i -> i.getConstraintType().equals("PRECEDENCE")).findFirst().orElseThrow();
        assertThat(precedenceImpact.getSlackHours()).isGreaterThanOrEqualTo(0L);
    }

    // ---------------------------------------------------------------- fixtures

    private static OptimizerJob job(long id, LocalDateTime start, LocalDateTime deadline, int windowHours) {
        return new OptimizerJob(id, "job-" + id, 10.0, 0.0, start, deadline, windowHours, false);
    }

    private static OptimizerJob exemptJob(long id, LocalDateTime start, LocalDateTime deadline) {
        return new OptimizerJob(id, "job-" + id, 10.0, 0.0, start, deadline, 0, true);
    }

    private static JobPlan singleSlotPlan(OptimizerJob job, LocalDateTime start) {
        return new JobPlan(job, List.of(new SlotAllocation(start, 1.0)));
    }

    private static ScheduleItem dtoItem(JobOutcome outcome, double baselineCost, double optimizedCost) {
        var job = outcome.job();
        var plan = outcome.plan();
        return new ScheduleItem(job.id(), job.name(), job.originalStartTime(), plan.start(), plan.end(),
                job.cpuHours(), job.gpuHours(), List.of(), outcome.status().name(), null,
                baselineCost, optimizedCost, baselineCost - optimizedCost);
    }

    private static ScheduleItem dtoItemUnscheduled(JobOutcome outcome) {
        var job = outcome.job();
        return new ScheduleItem(job.id(), job.name(), job.originalStartTime(), null, null, 0.0, 0.0, List.of(),
                outcome.status().name(), outcome.reason(), null, null, null);
    }

    private static OptimizeResponseDto response(String runId, List<JobOutcome> outcomes, List<ScheduleItem> dtoItems) {
        double baseline = dtoItems.stream().mapToDouble(i -> i.baselineCostUsd() == null ? 0.0 : i.baselineCostUsd()).sum();
        double optimized = dtoItems.stream().mapToDouble(i -> i.optimizedCostUsd() == null ? 0.0 : i.optimizedCostUsd()).sum();
        boolean anyLeftOut = outcomes.stream().anyMatch(o -> o.status() == PerJobStatus.NO_FEASIBLE_SLOT);
        return new OptimizeResponseDto(runId,
                anyLeftOut ? OptimizeResponseDto.STATUS_NEEDS_ATTENTION : OptimizeResponseDto.STATUS_SCHEDULED,
                true, baseline, optimized, baseline - optimized,
                baseline == 0.0 ? 0.0 : (baseline - optimized) / baseline * 100.0,
                "test plain-english explanation for run " + runId, false, dtoItems);
    }

    private com.gacs.backend.model.ScheduleItem onlyItemFor(OptimizeResponseDto response) {
        assertThat(response.items()).hasSize(1);
        var run = runs.findByRunId(response.runId()).orElseThrow();
        var savedItems = items.findByScheduleRunIdOrderByIdAsc(run.getId());
        assertThat(savedItems).hasSize(1);
        return savedItems.get(0);
    }

    private com.gacs.backend.model.ScheduleItem itemFor(OptimizeResponseDto response, long jobId) {
        var run = runs.findByRunId(response.runId()).orElseThrow();
        return items.findByScheduleRunIdOrderByIdAsc(run.getId()).stream()
                .filter(item -> item.getJobId() == jobId)
                .findFirst()
                .orElseThrow();
    }
}
