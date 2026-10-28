package com.gacs.backend.service.allocation;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.repository.ConstraintImpactRepository;
import com.gacs.backend.repository.ScheduleExplanationRepository;
import com.gacs.backend.repository.ScheduleItemRepository;
import com.gacs.backend.repository.ScheduleRunRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Persistence tests for {@link ScheduleRunRecorder} (docs/schedule-explanation-design.md section
 * 1 and 2.7): what Phase A actually writes to the database, for the two constraint types the
 * solver enforces today (DEADLINE, FLEXIBILITY_WINDOW). Real H2 database, matching the
 * {@code JobRepositoryTest} convention - not mocked repositories, since the thing actually worth
 * proving here is that rows land correctly, with correct keys and constraints.
 */
@SpringBootTest
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

    @Test
    @DisplayName("an OPTIMISED job persists a run, an item, and both DEADLINE + FLEXIBILITY_WINDOW impacts")
    void optimisedJobPersistsRunItemAndBothConstraintImpacts() {
        OptimizerJob job = job(1L, T0, T0.plusHours(10), 6);
        JobPlan plan = singleSlotPlan(job, T0.plusHours(3));
        JobOutcome outcome = new JobOutcome(job, plan, PerJobStatus.OPTIMISED, null);
        String runId = UUID.randomUUID().toString();
        OptimizeResponseDto response = response(runId, List.of(outcome), List.of(dtoItem(outcome, 1.0, 0.5)));

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

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

        recorder.record(new AllocationResult(outcomes, true), response, OWNER);

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

        recorder.record(new AllocationResult(List.of(outcome1), true),
                response(runId1, List.of(outcome1), List.of(dtoItem(outcome1, 1.0, 1.0))), OWNER);
        recorder.record(new AllocationResult(List.of(outcome2), true),
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

        recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER);

        assertThatThrownBy(() ->
                recorder.record(new AllocationResult(List.of(outcome), true), response, OWNER))
                .isInstanceOf(DataIntegrityViolationException.class);
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
}
