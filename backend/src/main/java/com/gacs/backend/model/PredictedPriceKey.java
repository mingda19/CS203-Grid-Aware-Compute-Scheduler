package com.gacs.backend.model;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class PredictedPriceKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDateTime intervalStartUtc; // matches @Id field name 'intervalStartUtc' in PredictedPrice
    private String location;                // matches @Id field name 'location' in PredictedPrice
    private String modelVersion;            // matches @Id field name 'modelVersion' in PredictedPrice
    private LocalDateTime generatedAt;       // matches @Id field name 'generatedAt' in PredictedPrice
}
