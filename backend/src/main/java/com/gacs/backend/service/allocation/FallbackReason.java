package com.gacs.backend.service.allocation;

/**
 * Why {@code ScheduleExplanation.source} is {@code DETERMINISTIC_FALLBACK} rather than {@code LLM}
 * (docs/schedule-explanation-design.md sections 1.4, 4, 5). {@code null} on the persisted row means
 * the LLM succeeded; one of these otherwise.
 */
public enum FallbackReason {
    /** No {@link LlmExplanationClient} bean exists - e.g. no API key configured. */
    NOT_CONFIGURED,
    /** A client exists, but {@code gacs.optimiser.explanation.enabled} is false. */
    DISABLED_BY_CONFIG,
    /** No response within the configured budget. */
    TIMEOUT,
    /** Invalid JSON, or the provider signalled a non-success completion (refusal, truncation). */
    PARSE_FAILURE,
    /** Parsed cleanly, but failed {@link ExplanationValidator}'s referential/shape checks. */
    VALIDATION_FAILURE,
    /** Transport failure or non-2xx response from the provider. */
    PROVIDER_ERROR
}
