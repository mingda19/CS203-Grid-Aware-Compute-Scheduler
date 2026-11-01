package com.gacs.backend.service.allocation;

import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Orchestrates stages 0-3 of the guardrail pipeline (docs/schedule-explanation-design.md section
 * 5): availability, transport/timeout + schema parsing (both inside {@link LlmExplanationClient}),
 * then referential validation ({@link ExplanationValidator}). All-or-nothing: any failure at any
 * stage returns a fallback outcome, never a partially-accepted one.
 *
 * <p>{@link LlmExplanationClient} is {@code @Autowired(required = false)}, exactly like
 * {@code EmailService}'s {@code JavaMailSender} - when no implementation bean exists (no API key
 * configured), {@code client} is simply null here, and every run falls back cleanly.
 */
@Service
public class ExplanationGenerator {

    private static final Logger logger = LoggerFactory.getLogger(ExplanationGenerator.class);

    private final ExplanationProperties properties;

    @Autowired(required = false)
    private LlmExplanationClient client;

    public ExplanationGenerator(ExplanationProperties properties) {
        this.properties = properties;
    }

    /** For tests: inject a client without relying on Spring's optional-autowiring. */
    void setClient(LlmExplanationClient client) {
        this.client = client;
    }

    ExplanationOutcome generate(ScheduleRun run, List<ScheduleItem> items, List<ConstraintImpact> impacts) {
        // Checked in this order deliberately: whether a client exists at all (NOT_CONFIGURED) is the
        // more fundamental fact, so it wins over DISABLED_BY_CONFIG in the common case of neither
        // being true (no API key, feature also off by default) - both are equally true then, but
        // NOT_CONFIGURED is the more informative one to report.
        if (client == null) {
            return ExplanationOutcome.fallback(FallbackReason.NOT_CONFIGURED);
        }
        if (!properties.enabled()) {
            return ExplanationOutcome.fallback(FallbackReason.DISABLED_BY_CONFIG);
        }

        // PRD section 11: a failure to generate text must never block the numerical recommendation.
        // AnthropicLlmExplanationClient already catches its own known failure modes, but nothing
        // upstream of this guarantees every current or future LlmExplanationClient does the same -
        // so this catches anything neither it nor ExplanationValidator anticipated, rather than
        // letting it propagate through two @Transactional boundaries into a 500 on /optimize.
        try {
            ExplanationGenerationResult attempt = client.generate(run, items, impacts, properties.timeout());
            if (!attempt.succeeded()) {
                logger.warn("LLM explanation attempt for run {} failed: {}", run.getRunId(), attempt.failureReason());
                return ExplanationOutcome.fallback(attempt.failureReason(), attempt.rawResponseJson(), List.of());
            }

            List<String> errors = ExplanationValidator.validate(attempt.response(), items, impacts);
            if (!errors.isEmpty()) {
                logger.warn("LLM explanation for run {} failed validation: {}", run.getRunId(), errors);
                return ExplanationOutcome.fallback(FallbackReason.VALIDATION_FAILURE, attempt.rawResponseJson(),
                        errors);
            }

            return ExplanationOutcome.success(attempt.response(), properties.modelName(), attempt.rawResponseJson());
        } catch (RuntimeException e) {
            logger.warn("LLM explanation attempt for run {} threw an unexpected exception", run.getRunId(), e);
            return ExplanationOutcome.fallback(FallbackReason.PROVIDER_ERROR);
        }
    }
}
