package com.gacs.backend.service.allocation;

import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Orchestrates stages 0-3: availability, the client's own transport/parse attempt, then validation. */
class ExplanationGeneratorTest {

    private static final ScheduleRun RUN = ScheduleRun.builder().id(1L).runId("run-1")
            .ownerEmail("operator@example.com").requestedAt(LocalDateTime.now()).status("SCHEDULED").build();
    private static final ScheduleItem ITEM = ScheduleItem.builder().id(1L).scheduleRunId(1L).jobId(101L)
            .jobName("job-101").baselineStart(LocalDateTime.now()).perJobStatus("OPTIMISED").build();
    private static final LlmExplanationResponse VALID_RESPONSE = new LlmExplanationResponse("H", "N",
            List.of(new LlmExplanationResponse.JobExplanation(101L, "ok")), List.of());

    private static ExplanationGenerator generator(boolean enabled) {
        return new ExplanationGenerator(new ExplanationProperties(enabled, "claude-haiku-4-5-20251001",
                Duration.ofSeconds(8)));
    }

    @Test
    @DisplayName("Disabled by config: falls back without ever calling the client")
    void disabledByConfig() {
        ExplanationGenerator generator = generator(false);
        LlmExplanationClient client = (run, items, impacts, timeout) -> {
            throw new AssertionError("client must not be called when disabled");
        };
        generator.setClient(client);

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.DISABLED_BY_CONFIG);
    }

    @Test
    @DisplayName("No client bean configured: falls back as not configured")
    void noClientConfigured() {
        ExplanationOutcome outcome = generator(true).generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.NOT_CONFIGURED);
    }

    @Test
    @DisplayName("Client reports a timeout: falls back with that exact reason")
    void clientTimeout() {
        ExplanationGenerator generator = generator(true);
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.failure(FallbackReason.TIMEOUT, null));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.TIMEOUT);
    }

    @Test
    @DisplayName("Client reports a parse failure: falls back with that exact reason")
    void clientParseFailure() {
        ExplanationGenerator generator = generator(true);
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.failure(FallbackReason.PARSE_FAILURE, "not json"));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.PARSE_FAILURE);
        assertThat(outcome.rawResponseJson()).isEqualTo("not json");
    }

    @Test
    @DisplayName("Client reports a provider error: falls back with that exact reason")
    void clientProviderError() {
        ExplanationGenerator generator = generator(true);
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.failure(FallbackReason.PROVIDER_ERROR, null));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("Client succeeds but the response fails referential validation: falls back, errors carried through")
    void clientSucceedsButValidationFails() {
        ExplanationGenerator generator = generator(true);
        LlmExplanationResponse invalid = new LlmExplanationResponse("H", "N", List.of(), List.of()); // missing job 101
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.success(invalid, "{raw}"));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.VALIDATION_FAILURE);
        assertThat(outcome.validationErrors()).isNotEmpty();
        assertThat(outcome.rawResponseJson()).isEqualTo("{raw}");
    }

    @Test
    @DisplayName("Client succeeds and the response passes validation: used as-is")
    void clientSucceedsAndValidates() {
        ExplanationGenerator generator = generator(true);
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.success(VALID_RESPONSE, "{raw}"));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isTrue();
        assertThat(outcome.fallbackReason()).isNull();
        assertThat(outcome.modelName()).isEqualTo("claude-haiku-4-5-20251001");
        assertThat(outcome.response()).isEqualTo(VALID_RESPONSE);
        assertThat(outcome.rawResponseJson()).isEqualTo("{raw}");
    }

    @Test
    @DisplayName("Disabled takes priority: even a client that would succeed is never consulted")
    void disabledTakesPriorityOverAWorkingClient() {
        ExplanationGenerator generator = generator(false);
        generator.setClient((run, items, impacts, timeout) ->
                ExplanationGenerationResult.success(VALID_RESPONSE, "{raw}"));

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());
        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.DISABLED_BY_CONFIG);
    }

    @Test
    @DisplayName("Client throws an unexpected exception: caught and treated as a fallback, never propagated")
    void unexpectedClientExceptionIsCaughtNotPropagated() {
        ExplanationGenerator generator = generator(true);
        generator.setClient((run, items, impacts, timeout) -> {
            throw new RuntimeException("boom - a bug, not an expected failure mode");
        });

        ExplanationOutcome outcome = generator.generate(RUN, List.of(ITEM), List.of());

        assertThat(outcome.usedLlm()).isFalse();
        assertThat(outcome.fallbackReason()).isEqualTo(FallbackReason.PROVIDER_ERROR);
    }

    @Test
    @DisplayName("Impacts list is passed through to the client untouched")
    void impactsPassedThroughToClient() {
        ExplanationGenerator generator = generator(true);
        ConstraintImpact impact = ConstraintImpact.builder().id(1L).scheduleItemId(1L)
                .constraintType("DEADLINE").code("DEADLINE_SLACK").narrativeFragment("f").build();
        List<ConstraintImpact> captured = new java.util.ArrayList<>();
        generator.setClient((run, items, impacts, timeout) -> {
            captured.addAll(impacts);
            return ExplanationGenerationResult.success(VALID_RESPONSE, null);
        });

        generator.generate(RUN, List.of(ITEM), List.of(impact));
        assertThat(captured).containsExactly(impact);
    }
}
