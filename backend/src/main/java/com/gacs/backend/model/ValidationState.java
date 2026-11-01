package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * Tracks the sliding boundary between "training may use this day" and "this day is still held
 * out for validation" (model/model.md's continuous-learning section, point 1). One row per
 * target, not per (modelKind, target): LSTM and LEAR are always scored on the SAME validation
 * window (model/evaluate.py's keep_common_days / "models were scored on different timestamps"
 * assertion exists precisely so comparisons stay apples-to-apples) - data_scripts/update.py only
 * advances {@link #validationStartDate} when every actively-served model kind for that target
 * agrees the oldest held-out day is safe to fold into training, so the two kinds never end up
 * validated on different windows.
 *
 * <p>{@code validationStartDate} is the cursor: training data may run through
 * {@code validationStartDate - 1 day}; {@code validationStartDate..yesterday} is the current
 * held-out window. It only ever moves forward, one (or more, after a missed cron run) days at a
 * time, and only when update.py's fold-in experiment shows doing so doesn't hurt validation score
 * on the remaining window. {@code originalStartDate} never changes - kept for reference/audit
 * (how much of the original holdout has been absorbed into training over the model's lifetime).
 */
@Entity
@Table(name = "validation_state")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ValidationState {

    /** {@code y0} or {@code y1} - model/model.md section 9. */
    @Id
    @Column(length = 10)
    private String target;

    @Column(name = "validation_start_date", nullable = false)
    private LocalDate validationStartDate;

    @Column(name = "original_start_date", nullable = false)
    private LocalDate originalStartDate;

    /** Last day update.py actually ran the fold-in check - skips re-checking the same day if the
     *  cron fires more than once. Null until the first check ever runs. */
    @Column(name = "last_checked_date")
    private LocalDate lastCheckedDate;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
