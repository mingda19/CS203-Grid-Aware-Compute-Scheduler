package com.gacs.backend.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.List;

@Schema(description = "Hourly price forecasts for one settlement point")
public record ForecastResponse(
    @Schema(example = "LZ_NORTH") String location,
    @Schema(description = "One entry per target hour, ascending. Empty if no forecast has been generated for the range.")
    List<ForecastPoint> points
) {
    @Schema(description = "Forecast for one target hour (the latest run that covers it)")
    public record ForecastPoint(
        @Schema(description = "Start of the target hour (UTC)", example = "2026-10-09T14:00:00")
        LocalDateTime intervalStartUtc,
        @Schema(description = "Predicted day-ahead settlement price, USD/MWh", example = "31.75")
        Double predictedPriceUsdMwh,
        @Schema(example = "xgboost_baseline_full") String modelVersion,
        @Schema(description = "When the prediction was made (UTC)", example = "2026-10-08T12:00:00")
        LocalDateTime generatedAt,
        @Schema(description = "Forecast confidence, 0-1 (higher is more confident). Reserved for GRID-68; omitted until the model provides it.",
                nullable = true)
        @JsonInclude(JsonInclude.Include.NON_NULL)
        Double confidenceScore
    ) {}
}
