package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Per (model kind, target, target hour-of-day UTC) RMSE, recomputed periodically from real recent
 * prediction residuals by data_scripts/recompute_reference_rmse.py - replaces
 * predictions.REFERENCE_RMSE's hand-maintained dict, which was frozen at whatever
 * model/evaluate.py measured the day someone last remembered to update it
 * (model/model.md's continuous-learning section, point 4).
 *
 * <p>Feeds the live {@code confidence_score} written to {@code predicted_price}: a recent
 * (decayed) run of real errors for a slot is compared against this reference to produce the
 * bounded score (data_scripts/predictions.confidence_scores).
 *
 * <p>Upsert-style, not append-only - one row per (modelKind, target, hourUtc), overwritten in
 * place by the recompute script. It feeds a live score and has to run anyway; it doesn't need
 * its own history the way {@link ModelArtifact} deliberately keeps old snapshots around for.
 */
@Entity
@Table(name = "rmse_reference")
@IdClass(RmseReferenceKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class RmseReference {

    /** {@code xgb}, {@code lstm}, or {@code lear}. xgb is retired from serving but
     *  model/evaluate.py still compares against it, so its reference values are kept too. */
    @Id
    @Column(name = "model_kind", nullable = false, length = 10)
    private String modelKind;

    /** {@code y0} or {@code y1} - model/model.md section 9. */
    @Id
    @Column(nullable = false, length = 10)
    private String target;

    /** Hour of the TARGET hour, UTC, 0-23. */
    @Id
    @Column(name = "hour_utc", nullable = false)
    private Integer hourUtc;

    @Column(nullable = false)
    private double rmse;

    @Column(name = "n_samples", nullable = false)
    private int nSamples;

    /** Latest settled actual this value was computed from. */
    @Column(name = "computed_through", nullable = false)
    private LocalDateTime computedThrough;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void onSave() {
        updatedAt = LocalDateTime.now();
    }
}
