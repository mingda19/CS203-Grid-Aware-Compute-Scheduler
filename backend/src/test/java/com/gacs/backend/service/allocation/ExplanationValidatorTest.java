package com.gacs.backend.service.allocation;

import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Stage 3 of the guardrail pipeline: referential coverage and string shape, nothing numeric. */
class ExplanationValidatorTest {

    private static ScheduleItem item(long id, long jobId) {
        return ScheduleItem.builder().id(id).scheduleRunId(1L).jobId(jobId).jobName("job-" + jobId)
                .baselineStart(LocalDateTime.now()).perJobStatus("OPTIMISED").build();
    }

    private static ConstraintImpact impact(long id, long scheduleItemId, ConstraintType type, String relatedJobIds) {
        return ConstraintImpact.builder().id(id).scheduleItemId(scheduleItemId).constraintType(type.name())
                .code(type.name() + "_CODE").relatedJobIds(relatedJobIds).narrativeFragment("fragment").build();
    }

    private static LlmExplanationResponse validResponse() {
        return new LlmExplanationResponse("Headline", "Narrative",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "Job 101 explanation")),
                List.of(new LlmExplanationResponse.ConstraintExplanation(101L, "DEADLINE", List.of(), "Deadline fragment")));
    }

    @Test
    @DisplayName("A fully valid response, matching every item and impact, has zero errors")
    void validResponseHasNoErrors() {
        List<ScheduleItem> items = List.of(item(1, 101));
        List<ConstraintImpact> impacts = List.of(impact(1, 1, ConstraintType.DEADLINE, null));

        assertThat(ExplanationValidator.validate(validResponse(), items, impacts)).isEmpty();
    }

    @Test
    @DisplayName("Missing a required job's explanation is an error")
    void missingJobExplanation() {
        List<ScheduleItem> items = List.of(item(1, 101), item(2, 102));
        LlmExplanationResponse response = new LlmExplanationResponse("H", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "only 101")), List.of());

        assertThat(ExplanationValidator.validate(response, items, List.of()))
                .anySatisfy(error -> assertThat(error).contains("102"));
    }

    @Test
    @DisplayName("An extra job explanation for a job id that doesn't exist in the run is an error")
    void fabricatedJobExplanation() {
        List<ScheduleItem> items = List.of(item(1, 101));
        LlmExplanationResponse response = new LlmExplanationResponse("H", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "real"),
                        new LlmExplanationResponse.JobExplanation(999L, "fabricated")),
                List.of());

        assertThat(ExplanationValidator.validate(response, items, List.of()))
                .anySatisfy(error -> assertThat(error).contains("999"));
    }

    @Test
    @DisplayName("Missing a required constraint impact's explanation is an error")
    void missingConstraintExplanation() {
        List<ScheduleItem> items = List.of(item(1, 101));
        List<ConstraintImpact> impacts = List.of(impact(1, 1, ConstraintType.DEADLINE, null));
        LlmExplanationResponse response = new LlmExplanationResponse("H", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "ok")), List.of());

        assertThat(ExplanationValidator.validate(response, items, impacts))
                .anySatisfy(error -> assertThat(error).contains("DEADLINE"));
    }

    @Test
    @DisplayName("An extra constraint explanation matching no real impact is an error")
    void fabricatedConstraintExplanation() {
        List<ScheduleItem> items = List.of(item(1, 101));
        List<ConstraintImpact> impacts = List.of(impact(1, 1, ConstraintType.DEADLINE, null));
        LlmExplanationResponse response = new LlmExplanationResponse("H", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "ok")),
                List.of(new LlmExplanationResponse.ConstraintExplanation(101L, "DEADLINE", List.of(), "real"),
                        new LlmExplanationResponse.ConstraintExplanation(101L, "PRECEDENCE", List.of(202L), "fabricated")));

        assertThat(ExplanationValidator.validate(response, items, impacts))
                .anySatisfy(error -> assertThat(error).contains("PRECEDENCE"));
    }

    @Test
    @DisplayName("Blank headline, narrative, or explanation strings are each errors")
    void blankStringsAreErrors() {
        List<ScheduleItem> items = List.of(item(1, 101));
        LlmExplanationResponse response = new LlmExplanationResponse("", "   ",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "")), List.of());

        List<String> errors = ExplanationValidator.validate(response, items, List.of());
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("headline"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("narrative"));
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("explanation"));
    }

    @Test
    @DisplayName("A string over the length ceiling is an error")
    void overLengthStringIsAnError() {
        List<ScheduleItem> items = List.of(item(1, 101));
        String tooLong = "x".repeat(ExplanationValidator.HEADLINE_MAX_LENGTH + 1);
        LlmExplanationResponse response = new LlmExplanationResponse(tooLong, "fine",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "fine")), List.of());

        assertThat(ExplanationValidator.validate(response, items, List.of()))
                .anySatisfy(e -> assertThat(e).contains("headline"));
    }

    @Test
    @DisplayName("relatedJobIds disambiguates two constraints of the same type on the same job")
    void relatedJobIdsDisambiguatesSameTypeConstraints() {
        List<ScheduleItem> items = List.of(item(1, 101));
        List<ConstraintImpact> impacts = List.of(
                impact(1, 1, ConstraintType.PRECEDENCE, "102"),
                impact(2, 1, ConstraintType.PRECEDENCE, "103"));
        LlmExplanationResponse response = new LlmExplanationResponse("H", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "ok")),
                List.of(new LlmExplanationResponse.ConstraintExplanation(101L, "PRECEDENCE", List.of(102L), "vs 102"),
                        // Wrong relatedJobIds for the second one: claims 102 again instead of 103.
                        new LlmExplanationResponse.ConstraintExplanation(101L, "PRECEDENCE", List.of(102L), "vs 102 again")));

        List<String> errors = ExplanationValidator.validate(response, items, impacts);
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("103")); // missing: the real impact vs 103
        assertThat(errors).anySatisfy(e -> assertThat(e).contains("more than one entry")); // duplicate vs 102
    }

    @Test
    @DisplayName("Multiple simultaneous problems are all reported together, not short-circuited")
    void multipleProblemsAllReported() {
        List<ScheduleItem> items = List.of(item(1, 101), item(2, 102));
        LlmExplanationResponse response = new LlmExplanationResponse("", "N",
                List.of(new LlmExplanationResponse.JobExplanation(101L, "ok")), List.of());

        List<String> errors = ExplanationValidator.validate(response, items, List.of());
        assertThat(errors).hasSizeGreaterThanOrEqualTo(2); // blank headline AND missing job 102
    }
}
