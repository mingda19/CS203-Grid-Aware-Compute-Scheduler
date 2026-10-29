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
     *  TIMEOUT, PARSE_FAILURE, VALIDATION_FAILURE, PROVIDER_ERROR - always NOT_CONFIGURED in
     *  Phase A, since no LLM client exists yet. */
    @Column(name = "fallback_reason", length = 40)
    private String fallbackReason;

    /** e.g. {@code claude-haiku-4-5}. Null when {@code fallbackReason} is non-null. */
    @Column(name = "model_name", length = 60)
    private String modelName;

    /** Raw unvalidated LLM output, retained only for debugging a failure - never shown to the
     *  operator. Always null in Phase A. */
    @Column(name = "raw_response_json", columnDefinition = "TEXT")
    private String rawResponseJson;

    @Column(nullable = false, length = 200)
    private String headline;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String narrative;

    /** Short list of the specific checks that failed, when they did. Always null in Phase A. */
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
