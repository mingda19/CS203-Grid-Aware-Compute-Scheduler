package com.gacs.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Past hours with both a forecast and a recorded price, for comparing the two. */
public record ForecastAccuracyResponse(List<AccuracyPoint> points, int days, String modelKind) {
    public record AccuracyPoint(LocalDateTime intervalStartUtc, double predictedPrice, double actualPrice,
                                String modelVersion, LocalDateTime generatedAt) {}
}
