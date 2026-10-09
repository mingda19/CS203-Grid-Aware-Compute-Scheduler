package com.gacs.backend.dto;

import java.time.LocalDateTime;
import java.util.List;

public record PriceForecastResponse(List<ForecastPoint> points, int horizonHours) {
    public record ForecastPoint(LocalDateTime intervalStartUtc, Double predictedPrice,
                                String modelVersion, LocalDateTime generatedAt) {}
}
