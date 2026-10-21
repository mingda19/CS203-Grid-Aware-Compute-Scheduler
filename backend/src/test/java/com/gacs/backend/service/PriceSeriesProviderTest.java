package com.gacs.backend.service;

import com.gacs.backend.model.PredictedPrice;
import com.gacs.backend.repository.PredictedPriceRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/** GRID-126 AC2: forecast rows are mapped to hourly slots, with a seeded fallback when absent. */
@ExtendWith(MockitoExtension.class)
class PriceSeriesProviderTest {

    private static final String LOCATION = "LZ_NORTH";
    private static final LocalDateTime START = LocalDateTime.of(2026, 10, 20, 0, 0);
    private static final int HOURS = 4;

    @Mock
    private PredictedPriceRepository predictions;

    private PriceSeries load(PredictedPrice... rows) {
        when(predictions.findLatestForecast(LOCATION, START, START.plusHours(HOURS)))
            .thenReturn(List.of(rows));
        return new PriceSeriesProvider(predictions).load(LOCATION, START, HOURS);
    }

    private static PredictedPrice row(LocalDateTime interval, Double price) {
        return PredictedPrice.builder()
            .intervalStartUtc(interval).location(LOCATION).modelVersion("v1")
            .generatedAt(START.minusDays(1)).predictedPrice(price).build();
    }

    @Test
    @DisplayName("forecast rows map to their hourly slots and the series is not flagged seeded")
    void forecastRowsMapToSlots() {
        PriceSeries series = load(row(START, 30.0), row(START.plusHours(1), 40.0));

        assertThat(series.isSeeded()).isFalse();
        assertThat(series.priceAt(START)).contains(30.0);
        assertThat(series.priceAt(START.plusHours(1))).contains(40.0);
    }

    @Test
    @DisplayName("sub-hour interval starts are truncated to the hour")
    void intervalsAreTruncatedToTheHour() {
        PriceSeries series = load(row(START.plusMinutes(15), 33.0));

        assertThat(series.priceAt(START)).contains(33.0);
    }

    @Test
    @DisplayName("duplicate rows for one slot keep the first, so the curve stays single-valued")
    void duplicateSlotKeepsFirst() {
        PriceSeries series = load(row(START, 30.0), row(START, 99.0));

        assertThat(series.priceAt(START)).contains(30.0);
    }

    @Test
    @DisplayName("null and non-finite prices are skipped")
    void invalidPricesAreSkipped() {
        PriceSeries series = load(
            row(START, null),
            row(START.plusHours(1), Double.NaN),
            row(START.plusHours(2), Double.POSITIVE_INFINITY),
            row(START.plusHours(3), 55.0));

        assertThat(series.asMap()).containsOnlyKeys(START.plusHours(3));
    }

    @Test
    @DisplayName("no forecast rows falls back to the seeded curve for every slot in the horizon")
    void noForecastFallsBackToSeededCurve() {
        PriceSeries series = load();

        assertThat(series.isSeeded()).isTrue();
        assertThat(series.asMap()).hasSize(HOURS);
        for (int h = 0; h < HOURS; h++) {
            assertThat(series.priceAt(START.plusHours(h)))
                .contains(PriceSeriesProvider.SEEDED_CURVE_USD_PER_MWH[h]);
        }
    }

    @Test
    @DisplayName("only unusable rows counts as no forecast and falls back to seeded")
    void onlyInvalidRowsFallBackToSeeded() {
        PriceSeries series = load(row(START, null));

        assertThat(series.isSeeded()).isTrue();
    }

    @Test
    @DisplayName("partial coverage is not seeded: uncovered hours are simply absent")
    void partialCoverageLeavesGaps() {
        PriceSeries series = load(row(START.plusHours(1), 40.0));

        assertThat(series.isSeeded()).isFalse();
        assertThat(series.priceAt(START)).isEmpty();
        assertThat(series.priceAt(START.plusHours(1))).contains(40.0);
    }
}
