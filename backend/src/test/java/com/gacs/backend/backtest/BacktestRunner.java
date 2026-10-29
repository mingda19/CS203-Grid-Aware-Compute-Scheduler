package com.gacs.backend.backtest;

import com.gacs.backend.backtest.WorkloadGenerator.GeneratedDay;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeRequestDto.ClusterCapacityInput;
import com.gacs.backend.dto.OptimizeRequestDto.ForecastPointInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.service.PriceSeries;
import com.gacs.backend.service.allocation.AllocationResult;
import com.gacs.backend.service.allocation.ContractRules;
import com.gacs.backend.service.allocation.EnergyCostModel;
import com.gacs.backend.service.allocation.JobOutcome;
import com.gacs.backend.service.allocation.JobPlan;
import com.gacs.backend.service.allocation.OptimizationProblem;
import com.gacs.backend.service.allocation.OptimizationProblemFactory;
import com.gacs.backend.service.allocation.OptimizerJob;
import com.gacs.backend.service.allocation.OptimizerProperties;
import com.gacs.backend.service.allocation.SlotCapacity;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Runs the backtest (docs/backtest-plan.md section 3.3). For every workload profile and every day:
 * <ol>
 *   <li>generate the day's jobs; their fixed schedule fits capacity (decision D2);</li>
 *   <li>for each planning source, build the problem from <b>that source's prices only</b>, solve it, and check the
 *       plan against every contract rule;</li>
 *   <li>also plan with the actual prices (perfect foresight), the best the optimiser could have done;</li>
 *   <li>settle the fixed schedule and every plan at the actual prices.</li>
 * </ol>
 * No look-ahead: the solver only ever sees the planning prices. Actual prices are used afterwards, for costs.
 * The problem is built by the same {@link OptimizationProblemFactory} the endpoint uses.
 *
 * <p>Costs in the rows are raw (unrounded) and cover scheduled jobs only, like the endpoint's totals. Ticket 2's
 * metrics are computed from these rows.
 */
public final class BacktestRunner {

    /** Planning source name for perfect foresight: planning with the actual prices. */
    public static final String PERFECT = "perfect";

    private final EnergyCostModel costs;
    private final OptimizationProblemFactory factory;
    private final PlanSolver solver;

    public BacktestRunner(OptimizerProperties properties, PlanSolver solver) {
        this.costs = new EnergyCostModel(properties);
        this.factory = new OptimizationProblemFactory(null, properties); // forecast[] path: no forecast service
        this.solver = solver;
    }

    /** One planning source on one day of one profile. Costs are raw USD over scheduled jobs. */
    public record DayRow(String profile, LocalDate date, String source, int jobs, int scheduled, int exempt,
                         double energyKwh, double baselineCostActual, double optimisedCostActual,
                         double baselineCostPlanned, double optimisedCostPlanned, boolean provenOptimal,
                         int violations, int rejectedDraws, int jobsNotGenerated) {
    }

    /** One job under one planning source. Cost fields are null when the job was not scheduled. */
    public record JobRow(String profile, LocalDate date, String source, long jobId, String jobClass, double cpuHours,
                         double gpuHours, double energyKwh, LocalDateTime originalStart, LocalDateTime deadline,
                         int flexibilityHours, boolean exempt, String status, LocalDateTime scheduledStart,
                         LocalDateTime scheduledEnd, int hours, Double baselineCostActual,
                         Double optimisedCostActual, Double optimisedCostPlanned) {
    }

    /** A day (or one source on a day) left out, and why. {@code source} is {@code *} for every source. */
    public record Skip(String profile, LocalDate date, String source, String reason) {
    }

    /** Solve time. Kept apart from the results because it is not repeatable. */
    public record Timing(String profile, LocalDate date, String source, long solveMillis) {
    }

    public record Result(List<DayRow> days, List<JobRow> jobs, List<Skip> skipped, List<Timing> timings) {
    }

    public Result run(BacktestConfig config) {
        HourlyPrices actual = HourlyPrices.load(BacktestConfig.ACTUAL, config.actualPricesFile());
        List<HourlyPrices> sources = config.sources().stream()
                .map(source -> HourlyPrices.load(source, config.forecastFile(source)))
                .toList();
        List<WorkloadProfile> profiles = config.profileFiles().stream().map(WorkloadProfile::load).toList();
        SlotCapacity capacity = SlotCapacity.uniform(config.capacity());
        WorkloadGenerator generator = new WorkloadGenerator(capacity);

        List<Task> tasks = new ArrayList<>();
        for (WorkloadProfile profile : profiles) {
            for (LocalDate day = config.from(); !day.isAfter(config.to()); day = day.plusDays(1)) {
                tasks.add(new Task(profile, day));
            }
        }

        List<Result> parts = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(config.threads())) {
            List<Future<Result>> futures = new ArrayList<>();
            for (Task task : tasks) {
                futures.add(pool.submit(() -> runDay(task, generator, actual, sources, config)));
            }
            for (Future<Result> future : futures) {
                parts.add(future.get()); // collected in task order, so output order never depends on threads
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("backtest interrupted", e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("backtest failed: " + e.getCause().getMessage(), e.getCause());
        }

        List<DayRow> days = new ArrayList<>();
        List<JobRow> jobs = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        List<Timing> timings = new ArrayList<>();
        for (Result part : parts) {
            days.addAll(part.days());
            jobs.addAll(part.jobs());
            skipped.addAll(part.skipped());
            timings.addAll(part.timings());
        }
        return new Result(List.copyOf(days), List.copyOf(jobs), List.copyOf(skipped), List.copyOf(timings));
    }

    private record Task(WorkloadProfile profile, LocalDate day) {
    }

    private Result runDay(Task task, WorkloadGenerator generator, HourlyPrices actualPrices,
                          List<HourlyPrices> sources, BacktestConfig config) {
        String profile = task.profile().id();
        LocalDate day = task.day();
        List<DayRow> days = new ArrayList<>();
        List<JobRow> jobs = new ArrayList<>();
        List<Skip> skipped = new ArrayList<>();
        List<Timing> timings = new ArrayList<>();

        Optional<PriceSeries> actual = actualPrices.day(day);
        if (actual.isEmpty()) {
            skipped.add(new Skip(profile, day, "*", "actual prices incomplete"));
            return new Result(days, jobs, skipped, timings);
        }
        GeneratedDay generated = generator.generate(task.profile(), day);
        if (generated.jobs().isEmpty()) {
            skipped.add(new Skip(profile, day, "*", "no job fits the facility"));
            return new Result(days, jobs, skipped, timings);
        }

        List<PlanningSource> planning = new ArrayList<>();
        planning.add(new PlanningSource(PERFECT, actual.get()));
        for (HourlyPrices source : sources) {
            Optional<PriceSeries> prices = source.day(day);
            if (prices.isPresent()) {
                planning.add(new PlanningSource(source.name(), prices.get()));
            } else {
                skipped.add(new Skip(profile, day, source.name(), "forecast prices incomplete"));
            }
        }

        for (PlanningSource source : planning) {
            OptimizationProblem problem = factory.build(request(generated.jobs(), source.prices(), config));
            long started = System.nanoTime();
            AllocationResult result = solver.solve(problem); // sees the planning prices only
            timings.add(new Timing(profile, day, source.name(), (System.nanoTime() - started) / 1_000_000));
            int violations = ContractRules.violations(problem, result).size();
            settle(profile, day, source, problem, result, violations, generated, actual.get(), days, jobs);
        }
        return new Result(days, jobs, skipped, timings);
    }

    private record PlanningSource(String name, PriceSeries prices) {
    }

    /** The day's jobs as an endpoint request, with explicit prices and the facility capacity. */
    static OptimizeRequestDto request(List<OptimizerJob> jobs, PriceSeries prices, BacktestConfig config) {
        List<JobInput> inputs = jobs.stream()
                .map(job -> new JobInput(job.id(), job.name(), job.cpuHours(), job.gpuHours(),
                        job.originalStartTime(), job.deadline(), job.flexibilityWindowHours(), job.exempt(), null))
                .toList();
        List<ForecastPointInput> forecast = prices.asMap().entrySet().stream()
                .map(entry -> new ForecastPointInput(entry.getKey(), entry.getValue()))
                .toList();
        ClusterCapacityInput capacity = new ClusterCapacityInput(config.capacity().cpuHours(),
                config.capacity().gpuHours());
        return new OptimizeRequestDto(inputs, capacity, null, null, forecast, null);
    }

    private void settle(String profile, LocalDate day, PlanningSource source, OptimizationProblem problem,
                        AllocationResult result, int violations, GeneratedDay generated, PriceSeries actual,
                        List<DayRow> days, List<JobRow> jobs) {
        int scheduled = 0;
        int exempt = 0;
        double energy = 0.0;
        double baselineActual = 0.0;
        double optimisedActual = 0.0;
        double baselinePlanned = 0.0;
        double optimisedPlanned = 0.0;
        Map<Long, String> classes = generated.jobClasses();

        for (JobOutcome outcome : result.outcomes()) {
            OptimizerJob job = outcome.job();
            double kwh = costs.jobKwh(job);
            energy += kwh;
            if (job.exempt()) {
                exempt++;
            }
            Double jobBaselineActual = null;
            Double jobOptimisedActual = null;
            Double jobOptimisedPlanned = null;
            if (outcome.scheduled()) {
                scheduled++;
                JobPlan baseline = problem.baselineOf(job);
                jobBaselineActual = costs.costUsd(baseline, actual);
                jobOptimisedActual = costs.costUsd(outcome.plan(), actual);
                jobOptimisedPlanned = costs.costUsd(outcome.plan(), source.prices());
                baselineActual += jobBaselineActual;
                optimisedActual += jobOptimisedActual;
                baselinePlanned += costs.costUsd(baseline, source.prices());
                optimisedPlanned += jobOptimisedPlanned;
            }
            jobs.add(new JobRow(profile, day, source.name(), job.id(), classes.get(job.id()), job.cpuHours(),
                    job.gpuHours(), kwh, job.originalStartTime(), job.deadline(), job.flexibilityWindowHours(),
                    job.exempt(), outcome.status().name(),
                    outcome.scheduled() ? outcome.plan().start() : null,
                    outcome.scheduled() ? outcome.plan().end() : null,
                    outcome.scheduled() ? outcome.plan().slotCount() : 0,
                    jobBaselineActual, jobOptimisedActual, jobOptimisedPlanned));
        }
        days.add(new DayRow(profile, day, source.name(), result.outcomes().size(), scheduled, exempt, energy,
                baselineActual, optimisedActual, baselinePlanned, optimisedPlanned, result.provenOptimal(),
                violations, generated.rejectedDraws(), generated.jobsNotPlaced()));
    }
}
