package com.gacs.backend.service.allocation;

import java.util.List;

/**
 * What {@link ExplanationGenerator#generate} returns: everything {@code ScheduleRunRecorder} needs
 * to decide what to persist on {@code ScheduleExplanation}, and whether to overwrite the
 * already-deterministic text on each {@code ScheduleItem}/{@code ConstraintImpact} with the LLM's.
 */
record ExplanationOutcome(
        boolean usedLlm,
        FallbackReason fallbackReason,
        String modelName,
        String rawResponseJson,
        List<String> validationErrors,
        LlmExplanationResponse response) {

    static ExplanationOutcome success(LlmExplanationResponse response, String modelName, String rawResponseJson) {
        return new ExplanationOutcome(true, null, modelName, rawResponseJson, List.of(), response);
    }

    static ExplanationOutcome fallback(FallbackReason reason, String rawResponseJson, List<String> validationErrors) {
        return new ExplanationOutcome(false, reason, null, rawResponseJson, validationErrors, null);
    }

    static ExplanationOutcome fallback(FallbackReason reason) {
        return fallback(reason, null, List.of());
    }
}
