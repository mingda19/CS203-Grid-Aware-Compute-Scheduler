package com.gacs.backend.service.allocation;

import com.gacs.backend.model.ConstraintImpact;
import com.gacs.backend.model.ScheduleItem;
import com.gacs.backend.model.ScheduleRun;

import java.time.Duration;
import java.util.List;

/**
 * Generates a plain-language explanation for one already-persisted optimisation run
 * (docs/schedule-explanation-design.md section 3.1). Implementations must enforce {@code timeout}
 * themselves - an SDK/HTTP-client default is not an acceptable substitute.
 *
 * <p>Registered {@code @Autowired(required = false)} by {@link ExplanationGenerator}, exactly like
 * {@code EmailService}'s {@code JavaMailSender}: when no implementation bean exists (no API key
 * configured), callers fall back to the deterministic explanation.
 */
public interface LlmExplanationClient {

    ExplanationGenerationResult generate(ScheduleRun run, List<ScheduleItem> items, List<ConstraintImpact> impacts,
                                         Duration timeout);
}
