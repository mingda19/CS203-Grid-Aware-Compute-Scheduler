package com.gacs.backend.service;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the hourly price curve the optimizer runs against.
 *
 * <p>Prefers the latest stored model forecast for the requested location and window.
 * When no forecast rows cover that window at all, falls back to {@link #SEEDED_CURVE_USD_PER_MWH}
 * so an optimization run still succeeds instead of failing on absent model output
 * (GRID-124 AC5.2). Partial coverage is not treated as absence: hours that are missing
 * simply are not candidate slots.
 */
@Service
public class PriceSeriesProvider {

    /**
     * Fallback diurnal curve in $/MWh, indexed by UTC hour-of-day.
     *
     * <p>Shaped like a typical ERCOT weekday: cheap overnight, a modest morning rise,
     * and a late-afternoon peak. Used only when stored forecasts are unavailable, and
     * flagged on the response so callers can tell a seeded run from a forecast run.
     */
    public static final double[] SEEDED_CURVE_USD_PER_MWH = {
        18.0, 16.5, 15.0, 14.5, 15.5, 18.0,   // 00:00-05:00 overnight trough
        24.0, 32.0, 38.0, 41.0, 43.0, 45.0,   // 06:00-11:00 morning ramp
        48.0, 52.0, 61.0, 74.0, 88.0, 95.0,   // 12:00-17:00 afternoon peak
        82.0, 64.0, 48.0, 36.0, 27.0, 21.0    // 18:00-23:00 evening decline
    };

    private final PredictedPriceRepository predictions;

    public PriceSeriesProvider(PredictedPriceRepository predictions) {
        this.predictions = predictions;
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
        return new PriceSeries(seededCurve(horizonStart, horizonHours), true);
    }

    /**
     * Expands the seeded hour-of-day curve across the horizon, so every slot in the
     * window is priced and the baseline of every job is comparable to its alternatives.
     */
    private static Map<LocalDateTime, Double> seededCurve(LocalDateTime horizonStart, int horizonHours) {
        Map<LocalDateTime, Double> curve = new LinkedHashMap<>();
        for (int offset = 0; offset < horizonHours; offset++) {
            LocalDateTime slot = horizonStart.plusHours(offset);
            curve.put(slot, SEEDED_CURVE_USD_PER_MWH[slot.getHour()]);
        }
        return curve;
    }
}
