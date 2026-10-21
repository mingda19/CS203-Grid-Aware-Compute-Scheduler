package com.gacs.backend.service;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.ElectricalPriceRepository;
import com.gacs.backend.repository.PredictedPriceRepository;
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
 * <p>Prefers the latest stored model forecast for the requested location and window.
 * When no forecast rows cover that window at all, falls back to a 24-hour curve built from
 * stored settlement prices (see {@link #fallbackCurve}) so an optimization run still succeeds
 * instead of failing on absent model output (GRID-124 AC5.2). Partial coverage is not treated
 * as absence: hours that are missing simply are not candidate slots.
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

    private static final int RECENT_WINDOW_DAYS = 30;
    private static final int RECENT_MAX_STALENESS_DAYS = 7;

    private final PredictedPriceRepository predictions;
    private final ElectricalPriceRepository history;
    private final double recentWeight;

    public PriceSeriesProvider(
        PredictedPriceRepository predictions,
        ElectricalPriceRepository history,
        @Value("${gacs.optimiser.fallback-recent-weight:0.5}") double recentWeight
    ) {
        this.predictions = predictions;
        this.history = history;
        this.recentWeight = recentWeight;
    }

    /**
     * Loads the price curve covering {@code [horizonStart, horizonStart + horizonHours)}.
     */
    public PriceSeries load(String location, LocalDateTime horizonStart, int horizonHours) {
        LocalDateTime horizonEnd = horizonStart.plusHours(horizonHours);
        List<PredictedPrice> rows = predictions.findLatestForecast(location, horizonStart, horizonEnd);

        Map<LocalDateTime, Double> forecast = new LinkedHashMap<>();
        for (PredictedPrice row : rows) {
            if (row.getIntervalStartUtc() == null || row.getPredictedPrice() == null) {
                continue;
            }
            if (!Double.isFinite(row.getPredictedPrice())) {
                continue;
            }
            LocalDateTime slot = row.getIntervalStartUtc().truncatedTo(ChronoUnit.HOURS);
            // findLatestForecast already narrows to the newest generatedAt per interval, but two
            // model versions can share that timestamp. Keep the first and ignore the duplicate so
            // the curve stays single-valued and the run stays deterministic.
            forecast.putIfAbsent(slot, row.getPredictedPrice());
        }

        if (!forecast.isEmpty()) {
            return new PriceSeries(forecast, false);
        }
        return new PriceSeries(fallbackSlots(fallbackCurve(location, horizonStart), horizonStart, horizonHours), true);
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
