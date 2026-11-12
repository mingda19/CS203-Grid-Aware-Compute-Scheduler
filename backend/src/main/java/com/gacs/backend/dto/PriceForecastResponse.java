package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

public record PriceForecastResponse(List<ForecastPoint> points, int horizonHours, String modelKind) {
    // confidenceScore is normalized from the stored forecast error ratio and
    // omitted while unavailable. typicalErrorUsdMwh is the model's reference RMSE
    // for this point's target (y0/y1) and UTC hour, omitted when there is none.
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ForecastPoint(LocalDateTime intervalStartUtc, Double predictedPrice,
                                String modelVersion, LocalDateTime generatedAt, Double confidenceScore,
                                Double typicalErrorUsdMwh) {}
}
