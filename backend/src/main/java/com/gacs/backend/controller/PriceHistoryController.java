package com.gacs.backend.controller;

import com.gacs.backend.dto.ApiResponse;
import com.gacs.backend.dto.ForecastAccuracyResponse;
import com.gacs.backend.dto.PriceHistoryResponse;
import com.gacs.backend.dto.PriceForecastResponse;
import com.gacs.backend.model.ElectricalPrice;
import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.model.RmseReference;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import com.gacs.backend.repository.RmseReferenceRepository;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

@RestController
@RequestMapping("/api/prices")
public class PriceHistoryController {

    private static final int MAX_LIMIT = 5000;
    private static final int MAX_ACCURACY_DAYS = 30;
    private final ElectricalPriceRepository prices;
    private final PredictedPriceRepository predictions;
    private final RmseReferenceRepository rmse;

    public PriceHistoryController(ElectricalPriceRepository prices, PredictedPriceRepository predictions,
                                  RmseReferenceRepository rmse) {
        this.prices = prices;
        this.predictions = predictions;
        this.rmse = rmse;
    }

    @GetMapping("/forecast")
    public ResponseEntity<ApiResponse<PriceForecastResponse>> getForecast(
        @RequestParam(defaultValue = "LZ_NORTH") String location,
        @RequestParam(defaultValue = "48") int hours,
        @RequestParam(defaultValue = "lstm") String model
    ) {
        requireLocation(location);
        if (hours < 1 || hours > 168) {
            throw new IllegalArgumentException("hours must be between 1 and 168");
        }
        String modelKind = requireModelKind(model);
        // Reference RMSE per (target, UTC hour). model_version is "<kind>_<target>_<date>".
        Map<String, Double> typicalError = new HashMap<>();
        for (RmseReference ref : rmse.findByModelKind(modelKind)) {
            typicalError.put(ref.getTarget() + ":" + ref.getHourUtc(), ref.getRmse());
        }
        LocalDateTime start = LocalDateTime.now(ZoneOffset.UTC);
        var points = predictions.findLatestForecastForModel(location, modelKind, start, start.plusHours(hours)).stream()
            .map(record -> {
                String[] parts = record.getModelVersion().split("_");
                Double error = parts.length < 2 ? null
                    : typicalError.get(parts[1] + ":" + record.getIntervalStartUtc().getHour());
                return new PriceForecastResponse.ForecastPoint(record.getIntervalStartUtc(),
                    record.getPredictedPrice(), record.getModelVersion(), record.getGeneratedAt(),
                    record.getConfidenceScore(), error);
            })
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Price forecast retrieved",
            new PriceForecastResponse(points, hours, modelKind)));
    }

    /**
     * Past hours that have both a recorded price and a forecast from one model. For each hour the
     * forecast is the latest run made before the hour started: the one a plan would have used.
     */
    @GetMapping("/forecast-accuracy")
    public ResponseEntity<ApiResponse<ForecastAccuracyResponse>> getForecastAccuracy(
        @RequestParam(defaultValue = "LZ_NORTH") String location,
        @RequestParam(defaultValue = "lear") String model,
        @RequestParam(defaultValue = "7") int days
    ) {
        requireLocation(location);
        if (days < 1 || days > MAX_ACCURACY_DAYS) {
            throw new IllegalArgumentException("days must be between 1 and " + MAX_ACCURACY_DAYS);
        }
        String modelKind = requireModelKind(model);
        LocalDateTime end = LocalDateTime.now(ZoneOffset.UTC);
        LocalDateTime start = end.minusDays(days);

        Map<LocalDateTime, Double> actual = new HashMap<>();
        for (ElectricalPrice p : prices.findByLocationAndIntervalStartUtcBetweenOrderByIntervalStartUtcAsc(location, start, end)) {
            if (p.getSppUsdMwh() != null) actual.put(p.getIntervalStartUtc(), p.getSppUsdMwh());
        }

        Map<LocalDateTime, PredictedPrice> chosen = new TreeMap<>();
        for (PredictedPrice p : predictions.findPastForecastsForModel(location, modelKind, start, end)) {
            chosen.merge(p.getIntervalStartUtc(), p, (a, b) -> b.getGeneratedAt().isAfter(a.getGeneratedAt()) ? b : a);
        }

        var points = chosen.values().stream()
            .filter(p -> actual.containsKey(p.getIntervalStartUtc()))
            .map(p -> new ForecastAccuracyResponse.AccuracyPoint(p.getIntervalStartUtc(), p.getPredictedPrice(),
                actual.get(p.getIntervalStartUtc()), p.getModelVersion(), p.getGeneratedAt()))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Forecast accuracy retrieved",
            new ForecastAccuracyResponse(points, days, modelKind)));
    }

    private static void requireLocation(String location) {
        if (location.isBlank() || location.length() > 100) {
            throw new IllegalArgumentException("location must be a non-empty settlement point name");
        }
    }

    private static String requireModelKind(String model) {
        String modelKind = model.toLowerCase(Locale.ROOT);
        if (!PredictedPriceRepository.MODEL_KINDS.contains(modelKind)) {
            throw new IllegalArgumentException("model must be one of: lstm, lear");
        }
        return modelKind;
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PriceHistoryResponse>> getHistory(
        @RequestParam(defaultValue = "LZ_NORTH") String location,
        @RequestParam LocalDate startDate,
        @RequestParam LocalDate endDate,
        @RequestParam(defaultValue = "5000") int limit,
        @RequestParam(defaultValue = "0") int offset
    ) {
        requireLocation(location);
        if (endDate.isBefore(startDate)) {
            throw new IllegalArgumentException("endDate must be on or after startDate");
        }
        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException("limit must be between 1 and " + MAX_LIMIT);
        }
        if (offset < 0) {
            throw new IllegalArgumentException("offset must be zero or greater");
        }
        if (offset % limit != 0) {
            throw new IllegalArgumentException("offset must be a multiple of limit");
        }

        // Date filters are UTC calendar dates. The exclusive upper bound includes the full endDate.
        var start = startDate.atStartOfDay();
        var endExclusive = endDate.plusDays(1).atStartOfDay();
        List<ElectricalPrice> records = prices
            .findByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThanOrderByIntervalStartUtcAsc(
                location, start, endExclusive, PageRequest.of(offset / limit, limit)
            );
        long total = prices.countByLocationAndIntervalStartUtcGreaterThanEqualAndIntervalStartUtcLessThan(
            location, start, endExclusive
        );
        var points = records.stream()
            .map(record -> new PriceHistoryResponse.PricePoint(
                record.getIntervalStartUtc(), record.getSppUsdMwh()
            ))
            .toList();
        return ResponseEntity.ok(ApiResponse.ok("Price history retrieved", new PriceHistoryResponse(points, total, limit, offset)));
    }
}
