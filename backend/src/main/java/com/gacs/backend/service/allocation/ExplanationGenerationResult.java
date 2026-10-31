package com.gacs.backend.service.allocation;

import java.util.Objects;

/**
 * What {@link LlmExplanationClient#generate} returns: either a cleanly-parsed
 * {@link LlmExplanationResponse} (stages 1-2 of the guardrail pipeline passed - transport and
 * schema/completion-reason), or a failure with one of the three reasons a client call itself can
 * produce. Referential validation (stage 3) is a separate, later step
 * ({@link ExplanationValidator}) - this type does not know about it.
 */
public final class ExplanationGenerationResult {

    private final LlmExplanationResponse response;
    private final FallbackReason failureReason;
    private final String rawResponseJson;

    private ExplanationGenerationResult(LlmExplanationResponse response, FallbackReason failureReason,
                                        String rawResponseJson) {
        this.response = response;
        this.failureReason = failureReason;
        this.rawResponseJson = rawResponseJson;
    }

    public static ExplanationGenerationResult success(LlmExplanationResponse response, String rawResponseJson) {
        return new ExplanationGenerationResult(Objects.requireNonNull(response), null, rawResponseJson);
    }

    /** @param reason one of {@link FallbackReason#TIMEOUT}, {@link FallbackReason#PARSE_FAILURE},
     *                {@link FallbackReason#PROVIDER_ERROR} - the three a transport/parse attempt can produce */
    public static ExplanationGenerationResult failure(FallbackReason reason, String rawResponseJson) {
        return new ExplanationGenerationResult(null, Objects.requireNonNull(reason), rawResponseJson);
    }

    public boolean succeeded() {
        return response != null;
    }

    /** @throws IllegalStateException if {@link #succeeded()} is false */
    public LlmExplanationResponse response() {
        if (response == null) {
            throw new IllegalStateException("no response: this attempt failed with " + failureReason);
        }
        return response;
    }

    /** @throws IllegalStateException if {@link #succeeded()} is true */
    public FallbackReason failureReason() {
        if (response != null) {
            throw new IllegalStateException("this attempt succeeded; there is no failure reason");
        }
        return failureReason;
    }

    public String rawResponseJson() {
        return rawResponseJson;
    }
}
