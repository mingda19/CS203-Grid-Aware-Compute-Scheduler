package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

public record PriceForecastResponse(List<ForecastPoint> points, int horizonHours) {
    // confidenceScore: reserved for GRID-68; omitted from the JSON while null
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForecastPoint(LocalDateTime intervalStartUtc, Double predictedPrice,
                                String modelVersion, LocalDateTime generatedAt, Double confidenceScore) {}
}
