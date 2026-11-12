package com.gacs.backend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gacs.backend.backtest.BacktestRunner.DayRow;
import com.gacs.backend.backtest.BacktestRunner.Result;
import com.gacs.backend.backtest.BacktestRunner.Skip;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.service.allocation.OptimizerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** Ticket 1: the backtest harness (docs/backtest-plan.md sections 3.3-3.5). */
class BacktestHarnessTest {

    private static final OptimizerProperties PROPERTIES = OptimizerProperties.DEFAULTS;
    private static final LocalDate DAY1 = LocalDate.of(2025, 6, 1);
    private static final CapacityLimit FACILITY = new CapacityLimit(256.0, 64.0);

    @TempDir
    Path dir;

    private Path data;
    private Path oneJobProfile;

    /**
     * Four days of prices. Day 1 is the hand-checked day; day 3's forecast misses an hour; day 4 has no actual
     * prices at all.
     */
    @BeforeEach
    void writeInputs() throws IOException {
        data = Files.createDirectories(dir.resolve("data"));
        TreeMap<LocalDateTime, Double> actual = new TreeMap<>();
        TreeMap<LocalDateTime, Double> forecast = new TreeMap<>();
        for (int d = 0; d < 4; d++) {
            for (int h = 0; h < 24; h++) {
                LocalDateTime hour = DAY1.plusDays(d).atStartOfDay().plusHours(h);
                actual.put(hour, 40.0 + h);
                forecast.put(hour, 45.0 + h);
            }
        }
        // Day 1, 10:00-12:00 - actual says 11:00 is cheapest, the forecast wrongly says 12:00.
        LocalDateTime ten = DAY1.atTime(10, 0);
        actual.put(ten, 50.0);
        actual.put(ten.plusHours(1), 10.0);
        actual.put(ten.plusHours(2), 30.0);
        forecast.put(ten, 50.0);
        forecast.put(ten.plusHours(1), 40.0);
        forecast.put(ten.plusHours(2), 20.0);
        forecast.remove(DAY1.plusDays(2).atTime(5, 0));
        actual.keySet().removeIf(hour -> hour.toLocalDate().equals(DAY1.plusDays(3)));
        writePrices(data.resolve("actual_prices.csv"), actual);
        writePrices(data.resolve("forecast_test.csv"), forecast);

        // One 20 CPU-hour job (1 kWh) at 10:00 that may wait 2 hours: deadline 13:00.
        oneJobProfile = dir.resolve("one-job.json");
        Files.writeString(oneJobProfile, """
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

    private BacktestConfig config(List<Path> profiles, LocalDate to, int threads) {
        return new BacktestConfig(data, profiles, List.of("test"), DAY1, to, FACILITY, PlanSolver.DEFAULT_EXACT_ITERATIONS, threads);
    }

    private static BacktestRunner runner() {
        return new BacktestRunner(PROPERTIES, PlanSolver.repeatable(new EnergyCostModel(PROPERTIES), PlanSolver.DEFAULT_EXACT_ITERATIONS));
    }

    private static DayRow row(Result result, LocalDate day, String source) {
        return result.days().stream().filter(r -> r.date().equals(day) && r.source().equals(source))
                .findFirst().orElseThrow();
    }

    @Test
    @DisplayName("A hand-checked day: baseline $50, forecast plan pays $30 (promised $20), perfect foresight $10")
    void handCheckedDay() {
        Result result = runner().run(config(List.of(oneJobProfile), DAY1, 1));

        DayRow planned = row(result, DAY1, "test");
        assertThat(planned.jobs()).isEqualTo(1);
        assertThat(planned.scheduled()).isEqualTo(1);
        assertThat(planned.energyKwh()).isCloseTo(1.0, within(1e-12));
        assertThat(planned.baselineCostActual()).isCloseTo(50.0 / 1000, within(1e-9));
        assertThat(planned.optimisedCostActual()).isCloseTo(30.0 / 1000, within(1e-6));
        assertThat(planned.optimisedCostPlanned()).isCloseTo(20.0 / 1000, within(1e-6));
        assertThat(planned.baselineCostPlanned()).isCloseTo(50.0 / 1000, within(1e-9));
        assertThat(planned.provenOptimal()).isTrue();
        assertThat(planned.violations()).isZero();

        DayRow perfect = row(result, DAY1, BacktestRunner.PERFECT);
        assertThat(perfect.optimisedCostActual()).isCloseTo(10.0 / 1000, within(1e-6));
        assertThat(perfect.optimisedCostPlanned()).isCloseTo(perfect.optimisedCostActual(), within(1e-12));

        assertThat(result.jobs()).filteredOn(j -> j.source().equals("test") && j.date().equals(DAY1))
                .singleElement()
                .satisfies(job -> {
                    assertThat(job.scheduledStart()).isEqualTo(DAY1.atTime(12, 0));
                    assertThat(job.status()).isEqualTo("OPTIMISED");
                    assertThat(job.jobClass()).isEqualTo("fixed");
                });
    }

    @Test
    @DisplayName("No look-ahead: when planning on a forecast, the solver only ever sees the forecast prices")
    void solverNeverSeesActualPrices() {
        List<OptimizationProblem> seen = Collections.synchronizedList(new ArrayList<>());
        PlanSolver spy = problem -> {
            seen.add(problem);
            return PlanSolver.repeatable(new EnergyCostModel(PROPERTIES), PlanSolver.DEFAULT_EXACT_ITERATIONS).solve(problem);
        };
        new BacktestRunner(PROPERTIES, spy).run(config(List.of(oneJobProfile), DAY1, 1));

        HourlyPrices actual = HourlyPrices.load("actual", data.resolve("actual_prices.csv"));
        HourlyPrices forecast = HourlyPrices.load("test", data.resolve("forecast_test.csv"));
        assertThat(seen).hasSize(2); // perfect foresight + the forecast source
        assertThat(seen).extracting(p -> p.prices().asMap())
                .containsExactlyInAnyOrder(actual.day(DAY1).orElseThrow().asMap(), forecast.day(DAY1).orElseThrow().asMap());
        // The forecast run's problem holds the forecast's 11:00 price (40), never the actual one (10).
        assertThat(seen).anySatisfy(p -> assertThat(p.prices().priceAt(DAY1.atTime(11, 0))).contains(40.0));
    }

    @Test
    @DisplayName("Days with missing prices are skipped and counted, never filled in")
    void missingPricesAreSkipped() {
        Result result = runner().run(config(List.of(oneJobProfile), DAY1.plusDays(3), 1));

        assertThat(result.skipped()).containsExactly(
                new Skip("one-job@v1", DAY1.plusDays(2), "test", "forecast prices incomplete"),
                new Skip("one-job@v1", DAY1.plusDays(3), "*", "actual prices incomplete"));
        // Day 3 still has its perfect-foresight row; day 4 has no rows at all.
        assertThat(result.days()).extracting(DayRow::date, DayRow::source).containsExactly(
                org.assertj.core.groups.Tuple.tuple(DAY1, "perfect"), org.assertj.core.groups.Tuple.tuple(DAY1, "test"),
                org.assertj.core.groups.Tuple.tuple(DAY1.plusDays(1), "perfect"),
                org.assertj.core.groups.Tuple.tuple(DAY1.plusDays(1), "test"),
                org.assertj.core.groups.Tuple.tuple(DAY1.plusDays(2), "perfect"));
    }

    @Test
    @DisplayName("Same inputs give byte-identical results, whatever the thread count; the manifest records it all")
    void repeatable() throws IOException {
        List<Path> profiles = List.of(oneJobProfile, Path.of("src/test/resources/backtest/profiles/mixed.json"));
        Path first = dir.resolve("out/first");
        Path second = dir.resolve("out/second");
        BacktestOutput.write(runner().run(config(profiles, DAY1.plusDays(2), 1)), config(profiles, DAY1.plusDays(2), 1),
                PROPERTIES, first, dir);
        JsonNode manifest = BacktestOutput.write(runner().run(config(profiles, DAY1.plusDays(2), 4)),
                config(profiles, DAY1.plusDays(2), 4), PROPERTIES, second, dir);

        for (String file : BacktestOutput.REPEATABLE_FILES) {
            assertThat(Files.readString(second.resolve(file))).as(file).isEqualTo(Files.readString(first.resolve(file)));
            assertThat(manifest.path("outputs").path(file).asText()).isEqualTo(BacktestOutput.sha256(second.resolve(file)));
        }
        assertThat(BacktestOutput.inputHashes(manifest)).containsOnlyKeys("actual_prices.csv", "forecast_test.csv");
        assertThat(manifest.path("profiles")).hasSize(2);
        assertThat(manifest.path("profiles").get(1).path("id").asText()).isEqualTo("mixed@v1");
        assertThat(manifest.path("summary").path("violations").asInt()).isZero();
        assertThat(Files.readAllLines(second.resolve("days.csv")).getFirst()).startsWith("profile,date,source,jobs");
    }

    @Test
    @DisplayName("Realistic profiles: every plan follows every contract rule and every job is scheduled")
    void realisticProfilesFollowTheRules() {
        List<Path> profiles = List.of(Path.of("src/test/resources/backtest/profiles/mixed.json"),
                Path.of("src/test/resources/backtest/profiles/nightly-batch.json"));
        Result result = runner().run(config(profiles, DAY1.plusDays(2), 2));

        assertThat(result.days()).isNotEmpty().allSatisfy(row -> {
            assertThat(row.violations()).isZero();
            // D2: the fixed schedule fits, so placing every job is always possible.
            assertThat(row.scheduled()).isEqualTo(row.jobs());
        });
    }
}
