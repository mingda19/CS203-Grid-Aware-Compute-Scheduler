package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * The run-level explanation for one {@link ScheduleRun} (docs/schedule-explanation-design.md
 * section 1.4) - a separate 1:1 entity rather than columns on {@code ScheduleRun}, so run-history
 * list queries stay cheap and the explanation can be regenerated independently later.
 *
 * <p>Deliberately holds no {@code structuredPayloadJson}: every number the "structured payload"
 * would contain already lives, normalized, in {@code ScheduleItem}/{@code ConstraintImpact} rows
 * - it's assembled at read time, never persisted twice.
 *
 * <p><b>Security contract for {@code headline}/{@code narrative}:</b> both are LLM-generated text
 * that narrates data including a user-supplied job name (see
 * {@code AnthropicLlmExplanationClient}'s prompt-injection hardening - a mitigation, not a proof).
 * Treat both as untrusted when consumed: any future reader that renders them in a web view MUST
 * HTML-encode them (e.g. normal JSX text interpolation, never {@code dangerouslySetInnerHTML} or
 * equivalent raw-HTML insertion on unencoded text) - the same rule any other free-text field in
 * this system follows, applied here because the value happens to come from a model rather than a
 * person.
 */
@Entity
@Table(name = "schedule_explanations")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleExplanation {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_run_id", nullable = false, unique = true)
    private Long scheduleRunId;

    /** {@code LLM} or {@code DETERMINISTIC_FALLBACK}. */
    @Column(nullable = false, length = 30)
    private String source;

    /** Null means the LLM succeeded. Otherwise one of NOT_CONFIGURED, DISABLED_BY_CONFIG,
     *  TIMEOUT, PARSE_FAILURE, VALIDATION_FAILURE, PROVIDER_ERROR
     *  ({@code com.gacs.backend.service.allocation.FallbackReason}). */
    @Column(name = "fallback_reason", length = 40)
    private String fallbackReason;

    /** e.g. {@code claude-haiku-4-5-20251001}. Null when {@code fallbackReason} is non-null. */
    @Column(name = "model_name", length = 60)
    private String modelName;

    /** Raw unvalidated provider response, retained only for debugging a failure - deliberately
     *  never exposed by any read-side DTO/endpoint, and never rendered in any view. If a response
     *  DTO is ever built for this entity, it must be an explicit allowlist of fields that excludes
     *  this one by construction, not a blocklist someone has to remember to apply. */
    @Column(name = "raw_response_json", columnDefinition = "TEXT")
    private String rawResponseJson;

    /** See the class-level security contract above. */
    @Column(nullable = false, length = 200)
    private String headline;

    /** See the class-level security contract above. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String narrative;

    /** Short list of the specific checks that failed, when they did. */
    @Column(name = "validation_errors_json", columnDefinition = "TEXT")
    private String validationErrorsJson;

    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt;

    @PrePersist
    protected void onCreate() {
        if (generatedAt == null) {
            generatedAt = LocalDateTime.now();
        }
    }
}
