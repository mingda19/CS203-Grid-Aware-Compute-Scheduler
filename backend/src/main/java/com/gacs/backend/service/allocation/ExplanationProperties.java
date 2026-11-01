package com.gacs.backend.service.allocation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Configurable constants for LLM-generated explanations, bound from
 * {@code gacs.optimiser.explanation.*} (docs/schedule-explanation-design.md section 8.1 - nested
 * under the optimiser's own prefix, since this is a sub-feature of it, not a standalone one).
 *
 * @param enabled   master switch; false short-circuits to the deterministic fallback with
 *                  {@link FallbackReason#DISABLED_BY_CONFIG} without even checking for a client
 * @param modelName the exact Anthropic model id to request
 * @param timeout   hard per-call budget, enforced explicitly - left short deliberately (no
 *                  job-queue infrastructure exists yet, so this runs synchronously inside the
 *                  optimise request); tune empirically, not guessed precisely here
 */
@ConfigurationProperties(prefix = "gacs.optimiser.explanation")
public record ExplanationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("claude-haiku-4-5-20251001") String modelName,
        @DefaultValue("8s") Duration timeout) {
}
