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
 * @param timeout   hard per-call budget, enforced explicitly. This runs synchronously inside the
 *                  optimise request - there is no job queue yet - so it is also dead time the
 *                  operator waits through. Raised from 8s because a full-horizon plan with many
 *                  jobs was routinely losing the race and falling back to the deterministic text.
 *                  The frontend sets no fetch timeout of its own today, so the browser default is
 *                  the only ceiling above this; if one is ever added, it has to exceed this budget
 *                  or the request is abandoned client-side while the model is still answering.
 */
@ConfigurationProperties(prefix = "gacs.optimiser.explanation")
public record ExplanationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("claude-haiku-4-5-20251001") String modelName,
        @DefaultValue("30s") Duration timeout) {
}
