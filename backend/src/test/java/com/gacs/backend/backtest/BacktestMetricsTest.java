package com.gacs.backend.backtest;

import com.gacs.backend.backtest.BacktestMetrics.Daily;
import com.gacs.backend.backtest.BacktestMetrics.Summary;
import com.gacs.backend.backtest.BacktestRunner.DayRow;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.OptimizerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Ticket 2: backtest metrics (docs/backtest-plan.md sections 4.2 and 4.3). */
class BacktestMetricsTest {

    private static final OptimizerProperties PROPERTIES = OptimizerProperties.DEFAULTS;
    private static final LocalDate DAY1 = LocalDate.of(2025, 6, 1);
    private static final LocalDate DAY2 = DAY1.plusDays(1);
    private static final double EPS = 1e-6;

    @TempDir
    Path dir;

    private Path data;
    private Path profile;

    /**
     * Two days, one 1 kWh job a day at 10:00 that may wait 2 hours. Actual prices are 40 + hour, forecast 45 + hour,
     * except day 1 10:00-12:00: actual 50 / 10 / 30, forecast 50 / 40 / 20 (the forecast wrongly picks 12:00).
     */
    @BeforeEach
    void writeInputs() throws IOException {
        data = Files.createDirectories(dir.resolve("data"));
        TreeMap<LocalDateTime, Double> actual = new TreeMap<>();
        TreeMap<LocalDateTime, Double> forecast = new TreeMap<>();
        for (LocalDate day : List.of(DAY1, DAY2)) {
            for (int h = 0; h < 24; h++) {
                actual.put(day.atTime(h, 0), 40.0 + h);
                forecast.put(day.atTime(h, 0), 45.0 + h);
            }
        }
        actual.put(DAY1.atTime(10, 0), 50.0);
        actual.put(DAY1.atTime(11, 0), 10.0);
        actual.put(DAY1.atTime(12, 0), 30.0);
        forecast.put(DAY1.atTime(10, 0), 50.0);
        forecast.put(DAY1.atTime(11, 0), 40.0);
        forecast.put(DAY1.atTime(12, 0), 20.0);
        writePrices(data.resolve("actual_prices.csv"), actual);
        writePrices(data.resolve("forecast_test.csv"), forecast);

        profile = dir.resolve("one-job.json");
        Files.writeString(profile, """
            {"name": "one-job", "version": 1, "seed": 1, "jobsPerDay": {"min": 1, "max": 1},
             "classes": [{"name": "fixed", "weight": 1, "cpuHours": {"min": 20, "max": 20},
               "gpuHours": {"min": 0, "max": 0}, "startHours": [10], "flexibilityHours": {"min": 2, "max": 2},
               "deadlineSlackHours": {"min": 0, "max": 0}, "exemptShare": 0}]}
            """);
    }

    private static void writePrices(Path file, Map<LocalDateTime, Double> prices) throws IOException {
        List<String> lines = new ArrayList<>(List.of("interval_start_utc,price"));
        prices.forEach((hour, price) -> lines.add(hour + "," + price));
        Files.write(file, lines);
    }

    /** Runs the real harness, writes the run, then computes the metrics from the files. */
    private BacktestMetrics.Result runAndMeasure(Path runDir) {
        BacktestConfig config = new BacktestConfig(data, List.of(profile), List.of("test"), DAY1, DAY2,
                new CapacityLimit(256, 64), Duration.ofSeconds(30), 1);
        BacktestRunner runner = new BacktestRunner(PROPERTIES,
                PlanSolver.repeatable(new EnergyCostModel(PROPERTIES), Duration.ofSeconds(30)));
        BacktestOutput.write(runner.run(config), config, PROPERTIES, runDir, dir);
        return MetricsFiles.write(runDir, dir);
    }

    private static Summary summary(BacktestMetrics.Result result, String source) {
        return result.summaries().stream().filter(s -> s.source().equals(source)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("Every daily metric on the hand-checked day")
    void handCheckedDailyMetrics() {
        BacktestMetrics.Result result = runAndMeasure(dir.resolve("run"));
        Daily day1 = BacktestMetrics.find(result.daily(), DAY1, "test").orElseThrow();

        assertThat(day1.baselineCost()).isCloseTo(0.050, within(EPS));         // $50/MWh x 1 kWh
        assertThat(day1.realisedCost()).isCloseTo(0.030, within(EPS));         // planned 12:00, paid $30
        assertThat(day1.projectedCost()).isCloseTo(0.020, within(EPS));        // the app expected $20
        assertThat(day1.projectedBaselineCost()).isCloseTo(0.050, within(EPS));
        assertThat(day1.perfectCost()).isCloseTo(0.010, within(EPS));          // 11:00 at $10
        assertThat(day1.realisedSavingsUsd()).isCloseTo(0.020, within(EPS));
        assertThat(day1.realisedSavingsPct()).isCloseTo(40.0, within(1e-4));
        assertThat(day1.projectedSavingsPct()).isCloseTo(60.0, within(1e-4));
        assertThat(day1.projectionGapPct()).isCloseTo(20.0, within(1e-4));     // promised 20 points too much
        assertThat(day1.forecastErrorCostUsd()).isCloseTo(0.020, within(EPS));
        assertThat(day1.perfectSavingsPct()).isCloseTo(80.0, within(1e-4));
        assertThat(day1.energyKwhBaseline()).isCloseTo(1.0, within(1e-12));
        assertThat(day1.energyKwhOptimised()).isCloseTo(1.0, within(1e-12));
        assertThat(day1.deadlineAdherence()).isEqualTo(1.0);
        assertThat(day1.countsForSavings()).isTrue();
        // Forecast is 5 high on 21 hours and off by 0 / 30 / 10 at 10:00 / 11:00 / 12:00.
        assertThat(day1.forecastMaeAll()).isCloseTo(145.0 / 24, within(1e-9));
        assertThat(day1.forecastRmseAll()).isCloseTo(Math.sqrt(1525.0 / 24), within(1e-9));
        assertThat(day1.usedHours()).isEqualTo(1);
        assertThat(day1.forecastMaeUsed()).isCloseTo(10.0, within(1e-9)); // the plan used 12:00 only

        Daily perfect = BacktestMetrics.find(result.daily(), DAY1, BacktestRunner.PERFECT).orElseThrow();
        assertThat(perfect.forecastMaeAll()).isZero();
        assertThat(perfect.realisedSavingsPct()).isCloseTo(80.0, within(1e-4));
    }

    @Test
    @DisplayName("Whole-period summary: ratio of totals, spread, captured share, forecast error")
    void handCheckedSummary() {
        Summary s = summary(runAndMeasure(dir.resolve("run")), "test");

        assertThat(s.days()).isEqualTo(2);
        assertThat(s.daysInSavings()).isEqualTo(2);
        assertThat(s.baselineCost()).isCloseTo(0.100, within(EPS));
        assertThat(s.realisedCost()).isCloseTo(0.080, within(EPS));
        assertThat(s.realisedSavingsPct()).isCloseTo(20.0, within(1e-4));
        assertThat(s.projectedSavingsPct()).isCloseTo(100.0 * 0.030 / 0.105, within(1e-4));
        assertThat(s.perfectSavingsPct()).isCloseTo(40.0, within(1e-4));
        assertThat(s.shareOfPossibleSavingsCaptured()).isCloseTo(0.5, within(1e-4));
        assertThat(s.dailySavingsMeanPct()).isCloseTo(20.0, within(1e-4));      // days: 40% and 0%
        assertThat(s.dailySavingsMedianPct()).isCloseTo(20.0, within(1e-4));
        assertThat(s.dailySavingsStdPct()).isCloseTo(Math.sqrt(800), within(1e-3));
        assertThat(s.dailySavingsP10Pct()).isCloseTo(4.0, within(1e-4));
        assertThat(s.dailySavingsP90Pct()).isCloseTo(36.0, within(1e-4));
        assertThat(s.negativeDaysShare()).isZero();
        // Two days fit in one 7-day block, so every bootstrap draw is the whole period.
        assertThat(s.realisedSavingsCiLow()).isCloseTo(20.0, within(1e-4));
        assertThat(s.realisedSavingsCiHigh()).isCloseTo(20.0, within(1e-4));
        assertThat(s.forecastMaeAll()).isCloseTo((145.0 + 120.0) / 48, within(1e-9));
        assertThat(s.forecastMaeUsed()).isCloseTo(7.5, within(1e-9));          // 10 on day 1, 5 on day 2
        assertThat(s.jobs()).isEqualTo(2);
        assertThat(s.scheduled()).isEqualTo(2);
        assertThat(s.violations()).isZero();
        assertThat(s.provenOptimalShare()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("Totals equal the sum of the days, and energy is the same for the baseline and the plan")
    void totalsAndEnergy() {
        BacktestMetrics.Result result = runAndMeasure(dir.resolve("run"));
        for (Summary s : result.summaries()) {
            List<Daily> days = result.daily().stream().filter(d -> d.source().equals(s.source())).toList();
            assertThat(s.baselineCost()).isCloseTo(days.stream().mapToDouble(Daily::baselineCost).sum(), within(1e-12));
            assertThat(s.realisedCost()).isCloseTo(days.stream().mapToDouble(Daily::realisedCost).sum(), within(1e-12));
            assertThat(s.projectedCost()).isCloseTo(days.stream().mapToDouble(Daily::projectedCost).sum(), within(1e-12));
            assertThat(s.energyKwhOptimised()).isCloseTo(s.energyKwhBaseline(), within(1e-12));
        }
    }

    @Test
    @DisplayName("Metric files are repeatable: computing them twice gives byte-identical files")
    void metricFilesRepeatable() throws IOException {
        Path run = dir.resolve("run");
        runAndMeasure(run);
        String daily = Files.readString(run.resolve("metrics_daily.csv"));
        String summary = Files.readString(run.resolve("metrics_summary.csv"));
        MetricsFiles.write(run, dir);
        assertThat(Files.readString(run.resolve("metrics_daily.csv"))).isEqualTo(daily);
        assertThat(Files.readString(run.resolve("metrics_summary.csv"))).isEqualTo(summary);
        assertThat(BacktestOutput.read(run.resolve("metrics.json")).path("outputs").path("metrics_daily.csv").asText())
                .isEqualTo(BacktestOutput.sha256(run.resolve("metrics_daily.csv")));
    }

    // ------------------------------------------------------------------ rules on constructed rows

    private static HourlyPrices flat(String name, LocalDate from, int days, double price) {
        TreeMap<LocalDateTime, Double> prices = new TreeMap<>();
        for (int d = 0; d < days; d++) {
            for (int h = 0; h < 24; h++) {
                prices.put(from.plusDays(d).atTime(h, 0), price);
            }
        }
        return new HourlyPrices(name, null, prices);
    }

    private static DayRow day(LocalDate date, String source, int jobs, int scheduled, double baseline, double realised) {
        return new DayRow("p@v1", date, source, jobs, scheduled, 0, 1.0, baseline, realised, baseline, realised,
                true, 0, 0, 0);
    }

    @Test
    @DisplayName("Savings rule: a day with an unscheduled job is reported but left out of savings figures")
    void daysWithUnscheduledJobsAreExcluded() {
        List<DayRow> rows = List.of(
                day(DAY1, "perfect", 2, 2, 10.0, 8.0), day(DAY1, "f", 2, 2, 10.0, 9.0),
                day(DAY2, "perfect", 2, 1, 10.0, 1.0), day(DAY2, "f", 2, 1, 10.0, 1.0));
        BacktestMetrics.Result result = BacktestMetrics.compute(rows, List.of(), List.of(),
                flat("actual", DAY1, 2, 30), Map.of("f", flat("f", DAY1, 2, 30)));

        Summary s = summary(result, "f");
        assertThat(s.days()).isEqualTo(2);
        assertThat(s.daysInSavings()).isEqualTo(1);
        assertThat(s.daysExcluded()).isEqualTo(1);
        assertThat(s.realisedSavingsPct()).isCloseTo(10.0, within(1e-9)); // day 1 only, not the 90% of day 2
        assertThat(s.jobs()).isEqualTo(4);
        assertThat(s.scheduled()).isEqualTo(3);
        assertThat(BacktestMetrics.find(result.daily(), DAY2, "f").orElseThrow().countsForSavings()).isFalse();
    }

    @Test
    @DisplayName("Negative days are counted; the bootstrap is seeded and brackets the point estimate")
    void negativeDaysAndBootstrap() {
        List<DayRow> rows = new ArrayList<>();
        for (int d = 0; d < 60; d++) {
            double realised = d % 5 == 0 ? 11.0 : 9.0 - (d % 3); // every 5th day costs more than the baseline
            rows.add(day(DAY1.plusDays(d), "perfect", 1, 1, 10.0, 7.0));
            rows.add(day(DAY1.plusDays(d), "f", 1, 1, 10.0, realised));
        }
        Map<String, HourlyPrices> forecasts = Map.of("f", flat("f", DAY1, 60, 30));
        Summary s = summary(BacktestMetrics.compute(rows, List.of(), List.of(), flat("actual", DAY1, 60, 30),
                forecasts), "f");
        Summary again = summary(BacktestMetrics.compute(rows, List.of(), List.of(), flat("actual", DAY1, 60, 30),
                forecasts), "f");

        assertThat(s.negativeDaysShare()).isCloseTo(12.0 / 60, within(1e-12));
        assertThat(s.realisedSavingsCiLow()).isEqualTo(again.realisedSavingsCiLow());
        assertThat(s.realisedSavingsCiHigh()).isEqualTo(again.realisedSavingsCiHigh());
        assertThat(s.realisedSavingsCiLow()).isLessThan(s.realisedSavingsPct());
        assertThat(s.realisedSavingsCiHigh()).isGreaterThan(s.realisedSavingsPct());
    }

    @Test
    @DisplayName("Constant daily savings give a zero-width interval")
    void constantSavingsGiveExactInterval() {
        List<Daily> days = new ArrayList<>();
        for (int d = 0; d < 30; d++) {
            days.add(new Daily("p", DAY1.plusDays(d), "f", 1, 1, 1, 1, 10.0, 9.0, 10.0, 9.0, 8.0, 1.0, 10.0, 1.0,
                    10.0, 0.0, 1.0, 20.0, 1.0, 0, true, 0, 0, null, null, 0, true));
        }
        double[] ci = BacktestMetrics.bootstrapSavingsPct(days);
        assertThat(ci[0]).isCloseTo(10.0, within(1e-9));
        assertThat(ci[1]).isCloseTo(10.0, within(1e-9));
    }

    @Test
    @DisplayName("Quantiles interpolate linearly, as numpy does")
    void quantilesMatchNumpy() {
        double[] values = {1, 2, 3, 4};
        assertThat(BacktestMetrics.quantile(values, 0.1)).isCloseTo(1.3, within(1e-12));   // numpy: 1.3
        assertThat(BacktestMetrics.quantile(values, 0.5)).isCloseTo(2.5, within(1e-12));
        assertThat(BacktestMetrics.quantile(values, 0.975)).isCloseTo(3.925, within(1e-12));
    }
}
