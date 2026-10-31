package com.gacs.backend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.gacs.backend.service.allocation.CapacityLimit;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.OptimizerProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs the full backtest. Skipped in normal builds; run it from {@code backend/} with:
 * <pre>
 *   mvn test -Dtest=BacktestRun -Dbacktest.run=true
 * </pre>
 * Optional settings (system properties):
 * <ul>
 *   <li>{@code backtest.dataDir} (default {@code ../backtest/data}) and {@code backtest.outDir}
 *       (default {@code ../backtest/output})</li>
 *   <li>{@code backtest.from} / {@code backtest.to} (default 2024-01-01 / 2026-08-29)</li>
 *   <li>{@code backtest.profiles}: comma-separated profile files (default: every file in
 *       {@code src/test/resources/backtest/profiles})</li>
 *   <li>{@code backtest.sources}: comma-separated forecast sources (default: every {@code forecast_*.csv})</li>
 *   <li>{@code backtest.exactBudgetSeconds} (default 30), {@code backtest.threads} (default: CPU count),
 *       {@code backtest.runId} (default: the current UTC time)</li>
 *   <li>Sensitivity runs (docs/backtest-plan.md section 5.2): {@code backtest.capacityCpu} /
 *       {@code backtest.capacityGpu} (default 256 / 64 per hour), {@code backtest.cpuKwhPerHour} /
 *       {@code backtest.gpuKwhPerHour} (default: the contract's 0.05 / 0.40), and {@code backtest.solver}:
 *       {@code repeatable} (default) or {@code heuristic} (the heuristic alone, to measure its gap)</li>
 *   <li>{@code backtest.reproduce}: path to an earlier run's manifest.json. Reruns it with the same settings,
 *       refuses if any input file changed, and fails unless the results are byte-identical.</li>
 * </ul>
 */
@EnabledIfSystemProperty(named = "backtest.run", matches = "true")
class BacktestRun {

    private static final Path BACKEND = Path.of("").toAbsolutePath();
    private static final Path REPO = BACKEND.getParent();
    private static final Path DEFAULT_PROFILES = BACKEND.resolve("src/test/resources/backtest/profiles");

    @Test
    void run() {
        String reproduce = System.getProperty("backtest.reproduce");
        JsonNode original = reproduce == null ? null : BacktestOutput.read(Path.of(reproduce));
        BacktestConfig config = original == null ? configFromProperties() : configFromManifest(Path.of(reproduce));
        OptimizerProperties properties = original == null ? propertiesFromProperties() : propertiesFromManifest(original);
        String solverName = original == null ? System.getProperty("backtest.solver", PlanSolver.REPEATABLE)
                : original.path("config").path("solver").asText(PlanSolver.REPEATABLE);
        String runId = System.getProperty("backtest.runId",
                LocalDateTime.now(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")));
        Path runDir = path("backtest.outDir", REPO.resolve("backtest/output")).resolve(runId);

        long started = System.nanoTime();
        PlanSolver solver = PlanSolver.named(solverName, new EnergyCostModel(properties), config.exactBudget());
        BacktestRunner.Result result = new BacktestRunner(properties, solver).run(config);
        JsonNode manifest = BacktestOutput.write(result, config, properties, solverName, runDir, REPO);
        BacktestMetrics.Result metrics = MetricsFiles.write(runDir, REPO);
        long seconds = (System.nanoTime() - started) / 1_000_000_000;

        System.out.printf("Backtest %s: %d day rows, %d skipped, %d violations, %.1f%% proven optimal, %d s. Output: %s%n",
                runId, result.days().size(), result.skipped().size(), manifest.path("summary").path("violations").asInt(),
                100 * manifest.path("summary").path("provenOptimalShare").asDouble(), seconds, runDir);

        printHeadline(metrics);

        if (reproduce != null) {
            Map<String, String> expected = BacktestOutput.outputHashes(BacktestOutput.read(Path.of(reproduce)));
            assertThat(BacktestOutput.outputHashes(manifest))
                    .as("results of the rerun must be byte-identical to %s", reproduce)
                    .isEqualTo(expected);
            Path originalMetrics = Path.of(reproduce).resolveSibling("metrics.json");
            if (Files.exists(originalMetrics)) {
                assertThat(BacktestOutput.read(runDir.resolve("metrics.json")).path("outputs"))
                        .as("metrics of the rerun must be byte-identical to %s", originalMetrics)
                        .isEqualTo(BacktestOutput.read(originalMetrics).path("outputs"));
            }
        }
    }

    /** Realised savings per profile and source, with the 95% interval, on the console. */
    static void printHeadline(BacktestMetrics.Result metrics) {
        System.out.println("Realised savings vs the fixed schedule (95% block-bootstrap interval):");
        for (BacktestMetrics.Summary s : metrics.summaries()) {
            System.out.printf(java.util.Locale.ROOT, "  %-28s %-9s %7.2f%%  [%6.2f%%, %6.2f%%]  perfect %6.2f%%  "
                            + "projected %6.2f%%  negative days %4.1f%%%n",
                    s.profile(), s.source(), nz(s.realisedSavingsPct()), nz(s.realisedSavingsCiLow()),
                    nz(s.realisedSavingsCiHigh()), nz(s.perfectSavingsPct()), nz(s.projectedSavingsPct()),
                    100 * s.negativeDaysShare());
        }
    }

    private static double nz(Double value) {
        return value == null ? Double.NaN : value;
    }

    private static BacktestConfig configFromProperties() {
        Path dataDir = path("backtest.dataDir", REPO.resolve("backtest/data"));
        return new BacktestConfig(
                dataDir,
                profiles(),
                sources(dataDir),
                LocalDate.parse(System.getProperty("backtest.from", "2024-01-01")),
                LocalDate.parse(System.getProperty("backtest.to", "2026-08-29")),
                new CapacityLimit(
                        number("backtest.capacityCpu", OptimizerProperties.DEFAULTS.defaultMaxCpuHoursPerSlot()),
                        number("backtest.capacityGpu", OptimizerProperties.DEFAULTS.defaultMaxGpuHoursPerSlot())),
                Duration.ofSeconds(Long.parseLong(System.getProperty("backtest.exactBudgetSeconds", "30"))),
                Integer.parseInt(System.getProperty("backtest.threads",
                        String.valueOf(Runtime.getRuntime().availableProcessors()))));
    }

    /** The contract's kWh factors, unless {@code backtest.cpuKwhPerHour} / {@code backtest.gpuKwhPerHour} are set. */
    private static OptimizerProperties propertiesFromProperties() {
        OptimizerProperties d = OptimizerProperties.DEFAULTS;
        return new OptimizerProperties(number("backtest.cpuKwhPerHour", d.cpuKwhPerHour()),
                number("backtest.gpuKwhPerHour", d.gpuKwhPerHour()),
                d.defaultMaxCpuHoursPerSlot(), d.defaultMaxGpuHoursPerSlot());
    }

    /** The kWh factors an earlier run used (the contract's, for manifests written before they were configurable). */
    private static OptimizerProperties propertiesFromManifest(JsonNode manifest) {
        OptimizerProperties d = OptimizerProperties.DEFAULTS;
        JsonNode optimiser = manifest.path("optimiser");
        return new OptimizerProperties(optimiser.path("cpuKwhPerHour").asDouble(d.cpuKwhPerHour()),
                optimiser.path("gpuKwhPerHour").asDouble(d.gpuKwhPerHour()),
                d.defaultMaxCpuHoursPerSlot(), d.defaultMaxGpuHoursPerSlot());
    }

    private static double number(String property, double fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Double.parseDouble(value);
    }

    /** The settings an earlier run used, after checking its inputs have not changed. */
    private static BacktestConfig configFromManifest(Path manifestFile) {
        JsonNode manifest = BacktestOutput.read(manifestFile);
        JsonNode config = manifest.path("config");
        Path dataDir = REPO.resolve(manifest.path("inputs").path("dataDir").asText());
        BacktestOutput.inputHashes(manifest).forEach((file, hash) ->
                assertThat(BacktestOutput.sha256(dataDir.resolve(file)))
                        .as("input %s has changed since run %s", file, manifest.path("runId").asText())
                        .isEqualTo(hash));
        List<Path> profiles = new ArrayList<>();
        for (JsonNode profile : manifest.path("profiles")) {
            Path file = REPO.resolve(profile.path("file").asText());
            assertThat(BacktestOutput.sha256(file))
                    .as("profile %s has changed since run %s", file, manifest.path("runId").asText())
                    .isEqualTo(profile.path("sha256").asText());
            profiles.add(file);
        }
        List<String> sources = new ArrayList<>();
        config.path("sources").forEach(source -> sources.add(source.asText()));
        return new BacktestConfig(dataDir, profiles, sources,
                LocalDate.parse(config.path("from").asText()), LocalDate.parse(config.path("to").asText()),
                new CapacityLimit(config.path("capacityCpuHoursPerHour").asDouble(),
                        config.path("capacityGpuHoursPerHour").asDouble()),
                Duration.ofSeconds(config.path("exactBudgetSeconds").asLong()),
                Integer.parseInt(System.getProperty("backtest.threads",
                        String.valueOf(Runtime.getRuntime().availableProcessors()))));
    }

    private static List<Path> profiles() {
        String listed = System.getProperty("backtest.profiles");
        if (listed != null) {
            return Arrays.stream(listed.split(",")).map(String::trim).map(Path::of).toList();
        }
        try (Stream<Path> files = Files.list(DEFAULT_PROFILES)) {
            return files.filter(f -> f.toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list profiles in " + DEFAULT_PROFILES, e);
        }
    }

    private static List<String> sources(Path dataDir) {
        String listed = System.getProperty("backtest.sources");
        if (listed != null) {
            return Arrays.stream(listed.split(",")).map(String::trim).filter(s -> !s.isEmpty()).toList();
        }
        try (Stream<Path> files = Files.list(dataDir)) {
            return files.map(f -> f.getFileName().toString())
                    .filter(name -> name.startsWith("forecast_") && name.endsWith(".csv"))
                    .map(name -> name.substring("forecast_".length(), name.length() - ".csv".length()))
                    .sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException("cannot list forecasts in " + dataDir
                    + " - run backtest/export_prices.py first", e);
        }
    }

    private static Path path(String property, Path fallback) {
        String value = System.getProperty(property);
        return value == null ? fallback : Path.of(value).toAbsolutePath();
    }
}
