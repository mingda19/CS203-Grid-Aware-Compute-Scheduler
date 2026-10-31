package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

/**
 * How one constraint applied to one {@link ScheduleItem} (docs/schedule-explanation-design.md
 * section 2.7). One row per (item, constraint type that applied) - a job can have more than one
 * (e.g. both DEADLINE and FLEXIBILITY_WINDOW).
 *
 * <p>A generic envelope rather than one forced numeric field: different constraint types have
 * fundamentally different notions of "how close to violating" - {@code slackHours} is null for
 * any type that doesn't have a natural margin.
 */
@Entity
@Table(name = "constraint_impacts", indexes = @Index(name = "idx_constraint_impacts_item", columnList = "schedule_item_id"))
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ConstraintImpact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "schedule_item_id", nullable = false)
    private Long scheduleItemId;

    /** {@code ConstraintType.name()} - stored as a plain string, matching how PerJobStatus is
     *  stored elsewhere in this codebase (a real enum on the solver side, a plain string once
     *  persisted/serialized). */
    @Column(name = "constraint_type", nullable = false, length = 30)
    private String constraintType;

    /** Short machine-readable code, e.g. {@code DEADLINE_SLACK}. */
    @Column(nullable = false, length = 60)
    private String code;

    /** The margin before this constraint would bind, in hours, for the types that have one
     *  (null for the pair/group types that don't - see the design doc's table). */
    @Column(name = "slack_hours")
    private Long slackHours;

    /** Comma-separated job ids this constraint also references; null/empty for the two
     *  single-job types (DEADLINE, FLEXIBILITY_WINDOW). Always null in Phase A, since only
     *  those two types are enforced today. */
    @Column(name = "related_job_ids", length = 255)
    private String relatedJobIds;

    /** The plain-language fragment - filled by either the LLM or the deterministic fallback,
     *  same column either way.
     *
     *  <p>Security contract: may be LLM-generated text - treat as untrusted on display, same as
     *  {@code ScheduleItem.whyThisPlacement}. HTML-encode on render; never insert unencoded. */
    @Column(name = "narrative_fragment", columnDefinition = "TEXT", nullable = false)
    private String narrativeFragment;
}
