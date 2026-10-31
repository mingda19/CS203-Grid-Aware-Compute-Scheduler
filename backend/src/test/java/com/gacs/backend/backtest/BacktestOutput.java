package com.gacs.backend.backtest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.gacs.backend.backtest.BacktestRunner.DayRow;
import com.gacs.backend.backtest.BacktestRunner.JobRow;
import com.gacs.backend.backtest.BacktestRunner.Result;
import com.gacs.backend.backtest.BacktestRunner.Skip;
import com.gacs.backend.backtest.BacktestRunner.Timing;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.OptimizerProperties;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Writes a run's results and its manifest (docs/backtest-plan.md section 3.4).
 *
 * <p>Repeatable files: {@code days.csv}, {@code jobs.csv}, {@code skipped.csv}. The same inputs, profiles, settings
 * and code always give byte-identical copies, and the manifest records their SHA-256. {@code timings.csv} and the
 * manifest's timestamp are the only things allowed to differ between runs.
 */
public final class BacktestOutput {

    /** Files whose bytes must match exactly when a run is repeated. */
    public static final List<String> REPEATABLE_FILES = List.of("days.csv", "jobs.csv", "skipped.csv");
    public static final String MANIFEST = "manifest.json";

    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);

    private BacktestOutput() {
    }

    /** Writes every file and returns the manifest that was written. */
    public static JsonNode write(Result result, BacktestConfig config, OptimizerProperties properties, Path runDir,
                                 Path repoRoot) {
        return write(result, config, properties, PlanSolver.REPEATABLE, runDir, repoRoot);
    }

    /** As above, recording which {@link PlanSolver} made the plans ({@code repeatable} or {@code heuristic}). */
    public static JsonNode write(Result result, BacktestConfig config, OptimizerProperties properties, String solver,
                                 Path runDir, Path repoRoot) {
        try {
            Files.createDirectories(runDir);
            writeLines(runDir.resolve("days.csv"), daysCsv(result.days()));
            writeLines(runDir.resolve("jobs.csv"), jobsCsv(result.jobs()));
            writeLines(runDir.resolve("skipped.csv"), skippedCsv(result.skipped()));
            writeLines(runDir.resolve("timings.csv"), timingsCsv(result.timings()));
            ObjectNode manifest = manifest(result, config, properties, solver, runDir, repoRoot);
            Files.writeString(runDir.resolve(MANIFEST), JSON.writeValueAsString(manifest) + "\n");
            return manifest;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write backtest output to " + runDir, e);
        }
    }

    // ------------------------------------------------------------------ CSV

    static List<String> daysCsv(List<DayRow> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,date,source,jobs,scheduled,exempt,energy_kwh,baseline_cost_actual,optimised_cost_actual,"
                + "baseline_cost_planned,optimised_cost_planned,proven_optimal,violations,rejected_draws,"
                + "jobs_not_generated");
        for (DayRow r : rows) {
            lines.add(String.join(",", r.profile(), r.date().toString(), r.source(), str(r.jobs()),
                    str(r.scheduled()), str(r.exempt()), num(r.energyKwh()), num(r.baselineCostActual()),
                    num(r.optimisedCostActual()), num(r.baselineCostPlanned()), num(r.optimisedCostPlanned()),
                    String.valueOf(r.provenOptimal()), str(r.violations()), str(r.rejectedDraws()),
                    str(r.jobsNotGenerated())));
        }
        return lines;
    }

    static List<String> jobsCsv(List<JobRow> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,date,source,job_id,job_class,cpu_hours,gpu_hours,energy_kwh,original_start,deadline,"
                + "flexibility_hours,exempt,status,scheduled_start,scheduled_end,hours,baseline_cost_actual,"
                + "optimised_cost_actual,optimised_cost_planned");
        for (JobRow r : rows) {
            lines.add(String.join(",", r.profile(), r.date().toString(), r.source(), String.valueOf(r.jobId()),
                    r.jobClass(), num(r.cpuHours()), num(r.gpuHours()), num(r.energyKwh()),
                    r.originalStart().toString(), r.deadline().toString(), str(r.flexibilityHours()),
                    String.valueOf(r.exempt()), r.status(), time(r.scheduledStart()), time(r.scheduledEnd()),
                    str(r.hours()), num(r.baselineCostActual()), num(r.optimisedCostActual()),
                    num(r.optimisedCostPlanned())));
        }
        return lines;
    }

    static List<String> skippedCsv(List<Skip> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,date,source,reason");
        rows.forEach(r -> lines.add(String.join(",", r.profile(), r.date().toString(), r.source(), r.reason())));
        return lines;
    }

    static List<String> timingsCsv(List<Timing> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("profile,date,source,solve_ms");
        rows.forEach(r -> lines.add(String.join(",", r.profile(), r.date().toString(), r.source(),
                String.valueOf(r.solveMillis()))));
        return lines;
    }

    /** Fixed 8 decimal places, so the text never depends on platform number formatting. */
    private static String num(Double value) {
        return value == null ? "" : String.format(Locale.ROOT, "%.8f", value);
    }

    private static String str(int value) {
        return String.valueOf(value);
    }

    private static String time(Object value) {
        return value == null ? "" : value.toString();
    }

    private static void writeLines(Path file, List<String> lines) throws IOException {
        Files.writeString(file, String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ manifest

    private static ObjectNode manifest(Result result, BacktestConfig config, OptimizerProperties properties,
                                       String solver, Path runDir, Path repoRoot) {
        ObjectNode root = JSON.createObjectNode();
        root.put("runId", runDir.getFileName().toString());
        root.put("createdAtUtc", Instant.now().toString());

        ObjectNode git = root.putObject("git");
        git.put("commit", gitOutput(repoRoot, "rev-parse", "HEAD"));
        git.put("uncommittedChanges", !gitOutput(repoRoot, "status", "--porcelain").isEmpty());

        ObjectNode settings = root.putObject("config");
        settings.put("from", config.from().toString());
        settings.put("to", config.to().toString());
        ArrayNode sources = settings.putArray("sources");
        config.sources().forEach(sources::add);
        settings.put("capacityCpuHoursPerHour", config.capacity().cpuHours());
        settings.put("capacityGpuHoursPerHour", config.capacity().gpuHours());
        settings.put("exactBudgetSeconds", config.exactBudget().toSeconds());
        settings.put("solver", solver);
        settings.put("threads", config.threads());

        ObjectNode optimiser = root.putObject("optimiser");
        optimiser.put("cpuKwhPerHour", properties.cpuKwhPerHour());
        optimiser.put("gpuKwhPerHour", properties.gpuKwhPerHour());
        optimiser.put("minShare", AllocationSolver.MIN_SHARE);

        ArrayNode profiles = root.putArray("profiles");
        for (Path file : config.profileFiles()) {
            ObjectNode profile = profiles.addObject();
            profile.put("file", relative(repoRoot, file));
            profile.put("id", WorkloadProfile.load(file).id());
            profile.put("sha256", sha256(file));
        }

        ObjectNode inputs = root.putObject("inputs");
        inputs.put("dataDir", relative(repoRoot, config.dataDir()));
        ObjectNode inputHashes = inputs.putObject("sha256");
        inputHashes.put(config.actualPricesFile().getFileName().toString(), sha256(config.actualPricesFile()));
        for (String source : config.sources()) {
            inputHashes.put(config.forecastFile(source).getFileName().toString(), sha256(config.forecastFile(source)));
        }
        Path exportManifest = config.dataDir().resolve("export_manifest.json");
        if (Files.exists(exportManifest)) {
            inputHashes.put("export_manifest.json", sha256(exportManifest));
        }

        ObjectNode outputs = root.putObject("outputs");
        for (String name : REPEATABLE_FILES) {
            outputs.put(name, sha256(runDir.resolve(name)));
        }

        ObjectNode summary = root.putObject("summary");
        summary.put("dayRows", result.days().size());
        summary.put("jobRows", result.jobs().size());
        summary.put("skipped", result.skipped().size());
        summary.put("violations", result.days().stream().mapToInt(DayRow::violations).sum());
        summary.put("provenOptimalShare", result.days().isEmpty() ? 0.0
                : (double) result.days().stream().filter(DayRow::provenOptimal).count() / result.days().size());
        return root;
    }

    /** Hashes of the input files a manifest was made from, by file name. */
    public static Map<String, String> inputHashes(JsonNode manifest) {
        Map<String, String> hashes = new LinkedHashMap<>();
        manifest.path("inputs").path("sha256").properties().forEach(e -> hashes.put(e.getKey(), e.getValue().asText()));
        return hashes;
    }

    public static Map<String, String> outputHashes(JsonNode manifest) {
        Map<String, String> hashes = new LinkedHashMap<>();
        manifest.path("outputs").properties().forEach(e -> hashes.put(e.getKey(), e.getValue().asText()));
        return hashes;
    }

    public static JsonNode read(Path manifestFile) {
        try {
            return JSON.readTree(manifestFile.toFile());
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read manifest " + manifestFile, e);
        }
    }

    public static String sha256(Path file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(Files.readAllBytes(file)));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot hash " + file, e);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String relative(Path repoRoot, Path file) {
        Path absolute = file.toAbsolutePath().normalize();
        Path root = repoRoot.toAbsolutePath().normalize();
        return absolute.startsWith(root) ? root.relativize(absolute).toString() : absolute.toString();
    }

    /** Output of a git command, or "unknown" when git is not available (e.g. a source zip). */
    private static String gitOutput(Path repoRoot, String... args) {
        List<String> command = new ArrayList<>(List.of("git", "-C", repoRoot.toString()));
        command.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!process.waitFor(10, TimeUnit.SECONDS) || process.exitValue() != 0) {
                return "unknown";
            }
            return out;
        } catch (IOException e) {
            return "unknown";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
