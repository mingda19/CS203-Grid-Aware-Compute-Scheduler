package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One saved snapshot of a trained LZ_NORTH price-forecast model - LSTM or LEAR
 * (model/lstm_model.py / model/lear_model.py), served by data_scripts/predict_lstm.py /
 * predict_lear.py. One table for both kinds: from here they're both just a blob plus metadata
 * about when it's valid and how it scored - nothing in this entity looks inside {@code blob},
 * only the matching Python module does.
 *
 * <p>Render Cron containers are ephemeral (render_cron.sh: nothing on local disk survives
 * between runs), so this table is this project's only durable copy of a trained model.
 * predict_lstm.py/predict_lear.py load whichever row is {@link #isActive()} for their
 * (modelKind, target) at the start of every run; data_scripts/update.py's weekly tune cycle
 * inserts a new candidate row and only flips it active once validated to be no worse than the
 * row it would replace (model/model.md's continuous-learning section).
 *
 * <p>At most one active row per (modelKind, target) - enforced by a partial unique index
 * ({@code idx_model_artifacts_one_active}, db/migration/V6__model_artifacts_one_active_index.sql
 * - ddl-auto=update can create this table but can't express a partial index, so that one index
 * needs applying by hand, same as V4/V5), not just trusted application logic.
 */
@Entity
@Table(name = "model_artifacts", indexes = {
    @Index(name = "idx_model_artifacts_lookup", columnList = "model_kind, target, created_at")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelArtifact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** {@code lstm} or {@code lear}. */
    @Column(name = "model_kind", nullable = false, length = 10)
    private String modelKind;

    /** {@code y0} (48h ahead) or {@code y1} (72h ahead) - model/model.md section 9. */
    @Column(nullable = false, length = 10)
    private String target;

    /** Human-readable, e.g. {@code lstm_y0_2026-11-09} - written to
     *  {@code PredictedPrice.modelVersion} so a prediction always traces back to the exact
     *  artifact that produced it. */
    @Column(name = "model_version", nullable = false, length = 100)
    private String modelVersion;

    /** {@code torch} (model/lstm_model.py) or {@code joblib} (model/lear_model.py) - how
     *  {@code blob} deserializes. */
    @Column(nullable = false, length = 10)
    private String format;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] blob;

    /** Last price hour the fit actually saw. */
    @Column(name = "trained_through", nullable = false)
    private LocalDateTime trainedThrough;

    /** The validation window (data_scripts' ValidationState cursor through yesterday) this
     *  artifact was scored against before being promoted. */
    @Column(name = "valid_from_date", nullable = false)
    private LocalDate validFromDate;

    @Column(name = "valid_to_date", nullable = false)
    private LocalDate validToDate;

    /** JSON text, e.g. {@code {"mae": 7.07, "rmse": 17.64}}, measured on the window above. */
    @Column(columnDefinition = "TEXT")
    private String metrics;

    /** The one predict_lstm.py/predict_lear.py serve for (modelKind, target). See the class-level
     *  note on the partial unique index this relies on. */
    @Column(name = "is_active", nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
