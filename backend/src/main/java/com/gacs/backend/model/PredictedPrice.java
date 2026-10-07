package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * A model-generated price forecast for one target hour. Keyed on
 * (intervalStartUtc, location, modelVersion, generatedAt) rather than just
 * (intervalStartUtc, location) so that a day-ahead prediction and a later
 * same-day revision for the same target hour, or predictions from different
 * model versions, don't overwrite each other - needed to track prediction
 * accuracy over time per model iteration.
 */
@Entity
@Table(name = "predicted_price", indexes = {
    @Index(name = "idx_predicted_price_location", columnList = "location"),
    @Index(name = "idx_predicted_price_model_version", columnList = "modelVersion")
})
@IdClass(PredictedPriceKey.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PredictedPrice {

    @Id
    @Column(name = "interval_start_utc", nullable = false)
    private LocalDateTime intervalStartUtc; // which hour this prediction is FOR

    @Id
    @Column(nullable = false)
    private String location;

    @Id
    @Column(name = "model_version", nullable = false)
    private String modelVersion;

    @Id
    @Column(name = "generated_at", nullable = false)
    private LocalDateTime generatedAt; // when this prediction was made (not the target hour)

    @Column(name = "predicted_price")
    private Double predictedPrice;
}
