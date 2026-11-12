package com.gacs.backend.service;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the hourly price curve the optimizer runs against.
 *
 * <p>Prefers the latest stored forecast from one configured model kind
 * ({@code gacs.optimiser.forecast-model}, default {@code lear}) for the requested location and
 * window. When that model covers fewer hours of the window than the fallback model
 * ({@code gacs.optimiser.forecast-fallback-model}, default {@code lstm}; blank turns it off) - its
 * daily run failed or has not landed - the fallback model prices the whole run instead.
 * One run always reads a single model: LSTM and LEAR run as separate jobs, and stitching both
 * into one curve would make the optimizer shift jobs on model disagreement rather than price.
 * When no forecast rows cover that window at all, falls back to a 24-hour curve built from
 * stored settlement prices (see {@link #fallbackCurve}) so an optimization run still succeeds
 * instead of failing on absent model output (GRID-124 AC5.2).
 *
 * <p>Partial coverage is filled from the same fallback curve, and the filled hours are recorded as
 * estimated on the returned {@link PriceSeries}. Leaving them unpriced instead - the earlier
 * behaviour - made the gap fatal rather than merely uncertain: a job whose window reaches past the
 * forecast could not have its baseline priced, so {@code OptimizationProblemFactory} rejected the
 * whole request with 422 and the operator got no plan at all. The day-ahead market clears about a day
 * out while a job may be scheduled days ahead, so that was the normal case, not an edge one. Filling
 * the gap keeps every slot in the horizon priced, which is what makes a baseline comparable to its
 * alternatives, and the estimated-slot set is what lets the response mark such a plan provisional
 * instead of presenting an estimate as a forecast (contract FR-13 and FR-04).
 */
@Service
public class PriceSeriesProvider {

    /**
     * Fallback diurnal curve in $/MWh, indexed by UTC hour-of-day.
     *
     * <p>Shaped like a typical ERCOT weekday: cheap overnight, a modest morning rise,
     * and a late-afternoon peak. Last resort, used for an hour only when neither the
     * seasonal nor the recent history curve has data for it.
     */
    public static final double[] SEEDED_CURVE_USD_PER_MWH = {
        18.0, 16.5, 15.0, 14.5, 15.5, 18.0,   // 00:00-05:00 overnight trough
        24.0, 32.0, 38.0, 41.0, 43.0, 45.0,   // 06:00-11:00 morning ramp
        48.0, 52.0, 61.0, 74.0, 88.0, 95.0,   // 12:00-17:00 afternoon peak
        82.0, 64.0, 48.0, 36.0, 27.0, 21.0    // 18:00-23:00 evening decline
    };

    private static final Logger log = LoggerFactory.getLogger(PriceSeriesProvider.class);
    private static final int RECENT_WINDOW_DAYS = 30;
    private static final int RECENT_MAX_STALENESS_DAYS = 7;

    private final PredictedPriceRepository predictions;
    private final ElectricalPriceRepository history;
    private final double recentWeight;
    private final String forecastModel;
    private final String fallbackModel; // null when there is no fallback model

    public PriceSeriesProvider(
        PredictedPriceRepository predictions,
        ElectricalPriceRepository history,
        @Value("${gacs.optimiser.fallback-recent-weight:0.5}") double recentWeight,
        @Value("${gacs.optimiser.forecast-model:lear}") String forecastModel,
        @Value("${gacs.optimiser.forecast-fallback-model:lstm}") String fallbackModel
    ) {
        requireModelKind("gacs.optimiser.forecast-model", forecastModel);
        boolean noFallback = fallbackModel == null || fallbackModel.isBlank() || fallbackModel.equals(forecastModel);
        if (!noFallback) requireModelKind("gacs.optimiser.forecast-fallback-model", fallbackModel);
        this.predictions = predictions;
        this.history = history;
        this.recentWeight = recentWeight;
        this.forecastModel = forecastModel;
        this.fallbackModel = noFallback ? null : fallbackModel;
    }

    private static void requireModelKind(String property, String model) {
        if (!PredictedPriceRepository.MODEL_KINDS.contains(model)) {
            throw new IllegalArgumentException(property + " must be one of "
                + PredictedPriceRepository.MODEL_KINDS + ", got: " + model);
        }
    }

    /**
     * Loads the price curve covering {@code [horizonStart, horizonStart + horizonHours)}.
     */
    public PriceSeries load(String location, LocalDateTime horizonStart, int horizonHours) {
        LocalDateTime horizonEnd = horizonStart.plusHours(horizonHours);
        Map<LocalDateTime, Double> forecast = forecast(forecastModel, location, horizonStart, horizonEnd);
        // Only look at the fallback when the preferred model leaves hours uncovered.
        if (fallbackModel != null && forecast.size() < horizonHours) {
            Map<LocalDateTime, Double> fallback = forecast(fallbackModel, location, horizonStart, horizonEnd);
            if (fallback.size() > forecast.size()) {
                log.warn("Forecast model {} covers {}/{} hours of {} from {}; pricing this run with {} ({} hours)",
                    forecastModel, forecast.size(), horizonHours, location, horizonStart, fallbackModel, fallback.size());
                forecast = fallback;
            }
        }

        if (forecast.isEmpty()) {
            return new PriceSeries(
                fallbackSlots(fallbackCurve(location, horizonStart), horizonStart, horizonHours), true);
        }
        return withGapsFilled(forecast, location, horizonStart, horizonHours);
    }

    /** One model's latest forecast for the window, keyed by hour. */
    private Map<LocalDateTime, Double> forecast(String model, String location,
                                                LocalDateTime horizonStart, LocalDateTime horizonEnd) {
        List<PredictedPrice> rows = predictions.findLatestForecastForModel(
            location, model, horizonStart, horizonEnd);

        Map<LocalDateTime, Double> forecast = new LinkedHashMap<>();
        for (PredictedPrice row : rows) {
            if (row.getIntervalStartUtc() == null || row.getPredictedPrice() == null) {
                continue;
            }
            if (!Double.isFinite(row.getPredictedPrice())) {
                continue;
            }
            LocalDateTime slot = row.getIntervalStartUtc().truncatedTo(ChronoUnit.HOURS);
            // findLatestForecastForModel already narrows to the newest generatedAt per interval, but
            // two model versions (e.g. y0 and y1) can share that timestamp. Keep the first and ignore
            // the duplicate so the curve stays single-valued and the run stays deterministic.
            forecast.putIfAbsent(slot, row.getPredictedPrice());
        }
        return forecast;
    }

    /**
     * Returns the stored forecast with every uncovered hour of the horizon filled from the fallback
     * curve, those hours marked estimated. The fallback curve is only computed when there is actually
     * a gap, so a fully covered horizon costs no extra queries.
     */
    private PriceSeries withGapsFilled(Map<LocalDateTime, Double> forecast, String location,
                                       LocalDateTime horizonStart, int horizonHours) {
        Map<LocalDateTime, Double> missing = new LinkedHashMap<>();
        for (int offset = 0; offset < horizonHours; offset++) {
            LocalDateTime slot = horizonStart.plusHours(offset);
            if (!forecast.containsKey(slot)) {
                missing.put(slot, null);
            }
        }
        if (missing.isEmpty()) {
            return new PriceSeries(forecast, false);
        }

        double[] curve = fallbackCurve(location, horizonStart);
        Map<LocalDateTime, Double> combined = new LinkedHashMap<>(forecast);
        for (LocalDateTime slot : missing.keySet()) {
            combined.put(slot, curve[slot.getHour()]);
        }
        // seeded stays false: a stored forecast did cover part of this window, so the curve as a whole
        // is not the fallback. usedEstimatedPrices() is what callers check to mark a plan provisional.
        return new PriceSeries(combined, false, missing.keySet());
    }

    /**
     * Hour-of-day fallback curve: {@code w * recent + (1 - w) * seasonal}, where seasonal is the same
     * calendar month one year back and recent is the trailing {@value #RECENT_WINDOW_DAYS} days.
     * Recent is skipped unless the location's data is fresh, so a stale feed cannot leak old prices
     * into the blend. An hour with neither falls back to {@link #SEEDED_CURVE_USD_PER_MWH}.
     */
    private double[] fallbackCurve(String location, LocalDateTime horizonStart) {
        LocalDateTime seasonStart = horizonStart.minusYears(1).toLocalDate().withDayOfMonth(1).atStartOfDay();
        double[] seasonal = hourlyAverages(location, seasonStart, seasonStart.plusMonths(1));

        LocalDateTime latest = history.findLatestIntervalStartUtcForLocation(location);
        boolean fresh = latest != null && !latest.isBefore(horizonStart.minusDays(RECENT_MAX_STALENESS_DAYS));
        double[] recent = fresh
            ? hourlyAverages(location, horizonStart.minusDays(RECENT_WINDOW_DAYS), horizonStart)
            : noData();

        double[] curve = new double[24];
        for (int h = 0; h < 24; h++) {
            boolean hasSeasonal = !Double.isNaN(seasonal[h]);
            boolean hasRecent = !Double.isNaN(recent[h]);
            if (hasSeasonal && hasRecent) {
                curve[h] = recentWeight * recent[h] + (1 - recentWeight) * seasonal[h];
            } else if (hasSeasonal) {
                curve[h] = seasonal[h];
            } else if (hasRecent) {
                curve[h] = recent[h];
            } else {
                curve[h] = SEEDED_CURVE_USD_PER_MWH[h];
            }
        }
        return curve;
    }

    private static double[] noData() {
        double[] none = new double[24];
        Arrays.fill(none, Double.NaN);
        return none;
    }

    private double[] hourlyAverages(String location, LocalDateTime start, LocalDateTime end) {
        double[] averages = noData();
        for (Object[] row : history.averageByHourOfDay(location, start, end)) {
            averages[((Number) row[0]).intValue()] = ((Number) row[1]).doubleValue();
        }
        return averages;
    }

    /**
     * Expands the hour-of-day curve across the horizon, so every slot in the
     * window is priced and the baseline of every job is comparable to its alternatives.
     */
    private static Map<LocalDateTime, Double> fallbackSlots(double[] curve, LocalDateTime horizonStart, int horizonHours) {
        Map<LocalDateTime, Double> slots = new LinkedHashMap<>();
        for (int offset = 0; offset < horizonHours; offset++) {
            LocalDateTime slot = horizonStart.plusHours(offset);
            slots.put(slot, curve[slot.getHour()]);
        }
        return slots;
    }
}
