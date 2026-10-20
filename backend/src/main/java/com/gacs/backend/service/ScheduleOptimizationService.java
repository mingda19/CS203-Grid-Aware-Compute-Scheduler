package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.ScheduleSolver.ClusterCapacity;
import com.gacs.backend.service.ScheduleSolver.JobSpec;
import com.gacs.backend.service.ScheduleSolver.PerJobStatus;
import com.gacs.backend.service.ScheduleSolver.SolverAssignment;
import com.gacs.backend.service.ScheduleSolver.SolverInput;
import com.gacs.backend.service.ScheduleSolver.SolverResult;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

/**
 * Orchestrates one optimization run: validate the request, load the price curve,
 * hand a fully specified problem to the {@link ScheduleSolver}, then price the result
 * against the baseline.
 *
 * <p>Baseline and optimized costs are computed from the same {@link PriceSeries}
 * instance, which is what makes the savings figure meaningful (GRID-124 AC5.3).
 */
@Service
public class ScheduleOptimizationService {

    /** Jobs in these states are finished or abandoned and are not eligible for a run. */
    private static final Set<String> INELIGIBLE_STATUSES = Set.of("COMPLETED", "CANCELLED");

    private static final int MAX_LOCATION_LENGTH = 100;

    private final JobRepository jobs;
    private final PriceSeriesProvider priceSeriesProvider;
    private final ScheduleSolver solver;
    private final Clock clock;

    public ScheduleOptimizationService(
        JobRepository jobs, PriceSeriesProvider priceSeriesProvider, ScheduleSolver solver, Clock clock
    ) {
        this.jobs = jobs;
        this.priceSeriesProvider = priceSeriesProvider;
        this.solver = solver;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public OptimizeResponseDto optimize(OptimizeRequestDto request, String ownerEmail) {
        OptimizeRequestDto safeRequest = request == null
            ? new OptimizeRequestDto(null, null, null, null)
            : request;

        String location = validateLocation(safeRequest);
        int horizonHours = validateHorizon(safeRequest);
        ClusterCapacity capacity = resolveCapacity(safeRequest);

        LocalDateTime horizonStart = LocalDateTime.now(clock).truncatedTo(ChronoUnit.HOURS);
        LocalDateTime horizonEnd = horizonStart.plusHours(horizonHours);

        List<JobSpec> specs = resolveJobs(safeRequest, ownerEmail);
        PriceSeries prices = priceSeriesProvider.load(location, horizonStart, horizonHours);
        validateJobsAgainstWindow(specs, prices, horizonStart, horizonEnd);

        SolverResult result = solver.solve(new SolverInput(specs, prices, capacity, horizonStart, horizonHours));
        return buildResponse(result, prices);
    }

    // ---------------------------------------------------------------- validation

    private static String validateLocation(OptimizeRequestDto request) {
        if (request.forecastLocation() != null && request.forecastLocation().isBlank()) {
            throw new IllegalArgumentException("forecastLocation must be a non-empty settlement point name");
        }
        String location = request.forecastLocationOrDefault();
        if (location.length() > MAX_LOCATION_LENGTH) {
            throw new IllegalArgumentException(
                "forecastLocation must be at most " + MAX_LOCATION_LENGTH + " characters");
        }
        return location;
    }

    private static int validateHorizon(OptimizeRequestDto request) {
        int horizonHours = request.horizonHoursOrDefault();
        if (horizonHours < OptimizeRequestDto.MIN_HORIZON_HOURS
            || horizonHours > OptimizeRequestDto.MAX_HORIZON_HOURS) {
            throw new IllegalArgumentException("horizonHours must be between "
                + OptimizeRequestDto.MIN_HORIZON_HOURS + " and " + OptimizeRequestDto.MAX_HORIZON_HOURS);
        }
        return horizonHours;
    }

    private static ClusterCapacity resolveCapacity(OptimizeRequestDto request) {
        var input = request.clusterCapacity();
        if (input == null) {
            return ClusterCapacity.defaults();
        }
        double cpu = input.maxConcurrentCpuHours() == null
            ? ClusterCapacity.DEFAULT_MAX_CPU_HOURS : input.maxConcurrentCpuHours();
        double gpu = input.maxConcurrentGpuHours() == null
            ? ClusterCapacity.DEFAULT_MAX_GPU_HOURS : input.maxConcurrentGpuHours();

        if (!Double.isFinite(cpu) || cpu <= 0.0) {
            throw new IllegalArgumentException("clusterCapacity.maxConcurrentCpuHours must be a finite value greater than 0");
        }
        if (!Double.isFinite(gpu) || gpu <= 0.0) {
            throw new IllegalArgumentException("clusterCapacity.maxConcurrentGpuHours must be a finite value greater than 0");
        }
        return new ClusterCapacity(cpu, gpu);
    }

    /**
     * Resolves the job set. An explicit {@code jobs} array is validated and used as given;
     * omitting the field optimizes the operator's own eligible stored jobs instead.
     */
    private List<JobSpec> resolveJobs(OptimizeRequestDto request, String ownerEmail) {
        if (request.jobs() == null) {
            List<JobSpec> stored = jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(ownerEmail).stream()
                .filter(job -> job.getStatus() == null
                    || !INELIGIBLE_STATUSES.contains(job.getStatus().toUpperCase(Locale.ROOT)))
                .map(ScheduleOptimizationService::toSpec)
                .toList();
            if (stored.isEmpty()) {
                throw new IllegalArgumentException(
                    "No eligible jobs found for this operator. Create a job first, or supply a jobs array.");
            }
            return stored;
        }
        if (request.jobs().isEmpty()) {
            throw new IllegalArgumentException("jobs must contain at least one job");
        }

        List<JobSpec> specs = new ArrayList<>();
        for (int index = 0; index < request.jobs().size(); index++) {
            specs.add(toSpec(request.jobs().get(index), index));
        }
        return specs;
    }

    private static JobSpec toSpec(Job job) {
        return new JobSpec(job.getId(), job.getName(),
            job.getCpuHours() == null ? 0.0 : job.getCpuHours(),
            job.getGpuHours() == null ? 0.0 : job.getGpuHours(),
            job.getOriginalStartTime(), job.getDeadline(),
            job.getFlexibilityWindowHours() == null ? 0 : job.getFlexibilityWindowHours(),
            job.isExempt());
    }

    /**
     * Validates one inline job. Mirrors the rules enforced by {@code JobService} so a job
     * that would be rejected at creation is also rejected here.
     */
    private static JobSpec toSpec(OptimizeRequestDto.JobInput input, int index) {
        String where = "jobs[" + index + "]";
        if (input == null) {
            throw new IllegalArgumentException(where + " must not be null");
        }
        if (input.name() == null || input.name().isBlank()) {
            throw new IllegalArgumentException(where + ".name must not be blank");
        }
        double cpu = input.cpuHoursOrZero();
        double gpu = input.gpuHoursOrZero();
        if (!Double.isFinite(cpu) || cpu < 0.0) {
            throw new IllegalArgumentException(where + ".cpuHours must be non-negative and finite");
        }
        if (!Double.isFinite(gpu) || gpu < 0.0) {
            throw new IllegalArgumentException(where + ".gpuHours must be non-negative and finite");
        }
        if (cpu <= 0.0 && gpu <= 0.0) {
            throw new IllegalArgumentException(
                where + " must request at least one of cpuHours or gpuHours greater than 0");
        }
        if (input.originalStartTime() == null) {
            throw new IllegalArgumentException(where + ".originalStartTime is required");
        }
        if (input.deadline() == null) {
            throw new IllegalArgumentException(where + ".deadline is required");
        }
        if (input.deadline().isBefore(input.originalStartTime())) {
            throw new IllegalArgumentException(
                where + ".deadline must be greater than or equal to originalStartTime");
        }
        if (input.flexibilityWindowHoursOrZero() < 0) {
            throw new IllegalArgumentException(where + ".flexibilityWindowHours must be non-negative");
        }
        return new JobSpec(input.id(), input.name().trim(), cpu, gpu,
            input.originalStartTime(), input.deadline(),
            input.flexibilityWindowHoursOrZero(), input.exempt());
    }

    /**
     * Rejects a run whose baseline cannot be priced.
     *
     * <p>A job whose original slot lies outside the horizon, or inside it but with no
     * forecast price, has no baseline cost to compare against - reporting savings for it
     * would be fabricating a number. Failing with a clear message is better than
     * returning a misleading schedule (GRID-124 AC5.4).
     */
    private static void validateJobsAgainstWindow(
        List<JobSpec> specs, PriceSeries prices, LocalDateTime horizonStart, LocalDateTime horizonEnd
    ) {
        for (JobSpec spec : specs) {
            LocalDateTime baseline = spec.baselineSlot();
            String identity = spec.id() == null ? "'" + spec.name() + "'" : "id " + spec.id();

            if (baseline.isBefore(horizonStart) || !baseline.isBefore(horizonEnd)) {
                throw new IllegalArgumentException("Job " + identity + " has originalStartTime " + baseline
                    + ", which falls outside the optimization horizon " + horizonStart + " to " + horizonEnd
                    + ". Its baseline cost cannot be priced.");
            }
            if (prices.priceAt(baseline).isEmpty()) {
                throw new IllegalArgumentException("Job " + identity + " starts at " + baseline
                    + ", for which no forecast price is available. Its baseline cost cannot be priced.");
            }
        }
    }

    // ---------------------------------------------------------------- response assembly

    private static OptimizeResponseDto buildResponse(SolverResult result, PriceSeries prices) {
        List<OptimizeResponseDto.ScheduleItem> items = new ArrayList<>();
        double baselineTotal = 0.0;
        double optimizedTotal = 0.0;

        for (SolverAssignment assignment : result.assignments()) {
            JobSpec job = assignment.job();
            double energy = job.energyKwh();

            double baselinePrice = prices.priceAt(job.baselineSlot()).orElse(0.0);
            double scheduledPrice = prices.priceAt(assignment.slotStart()).orElse(baselinePrice);

            double baselineCost = CostModel.round(CostModel.cost(energy, baselinePrice));
            double optimizedCost = CostModel.round(CostModel.cost(energy, scheduledPrice));

            baselineTotal += baselineCost;
            optimizedTotal += optimizedCost;

            items.add(new OptimizeResponseDto.ScheduleItem(
                job.id(), job.name(),
                job.baselineSlot(), assignment.slotStart(), assignment.slotEnd(),
                job.cpuHours(), job.gpuHours(),
                assignment.status().name(),
                baselineCost, optimizedCost, CostModel.round(baselineCost - optimizedCost)
            ));
        }

        double baselineCostUsd = CostModel.round(baselineTotal);
        double optimizedCostUsd = CostModel.round(optimizedTotal);
        double savingsUsd = CostModel.round(baselineCostUsd - optimizedCostUsd);
        double savingsPercent = baselineCostUsd == 0.0
            ? 0.0
            : CostModel.round((savingsUsd / baselineCostUsd) * 100.0);

        String status = result.certifiedOptimal() ? OptimizeResponseDto.STATUS_OPTIMAL : OptimizeResponseDto.STATUS_FEASIBLE;
        return new OptimizeResponseDto(
            "run-" + UUID.randomUUID(),
            status,
            baselineCostUsd, optimizedCostUsd, savingsUsd, savingsPercent,
            explain(result, prices, savingsUsd, savingsPercent),
            prices.isSeeded(),
            !result.certifiedOptimal(),
            List.copyOf(items)
        );
    }

    /**
     * Builds the operator-facing summary. Deterministic for a given solver result.
     */
    private static String explain(
        SolverResult result, PriceSeries prices, double savingsUsd, double savingsPercent
    ) {
        long total = result.assignments().size();
        long moved = count(result, PerJobStatus.RESCHEDULED);
        long alreadyOptimal = count(result, PerJobStatus.UNCHANGED_ALREADY_OPTIMAL);
        long exempt = count(result, PerJobStatus.UNCHANGED_EXEMPT);
        long infeasible = count(result, PerJobStatus.UNCHANGED_NO_FEASIBLE_SLOT);

        StringBuilder text = new StringBuilder();
        if (moved == 0) {
            text.append(String.format(Locale.ROOT,
                "No job could be moved to a cheaper hour, so the schedule is unchanged across all %d job(s). ",
                total));
        } else {
            // Describe the hours the jobs actually vacated and moved into. Quoting the
            // horizon-wide peak and valley instead would read as a claim about these moves
            // while naming hours no job was placed in.
            String priceContext = movedPriceContext(result, prices);
            text.append(String.format(Locale.ROOT,
                "Shifted %d of %d job(s)%s, cutting projected energy cost by $%.2f (%.2f%%). ",
                moved, total, priceContext, savingsUsd, savingsPercent));
        }
        if (alreadyOptimal > 0) {
            text.append(String.format(Locale.ROOT,
                "%d job(s) were already in the cheapest hour available to them. ", alreadyOptimal));
        }
        if (exempt > 0) {
            text.append(String.format(Locale.ROOT,
                "%d job(s) are exempt from rescheduling and were held at their original time. ", exempt));
        }
        if (infeasible > 0) {
            text.append(String.format(Locale.ROOT,
                "%d job(s) had no slot meeting both their deadline and cluster capacity, "
                    + "so they remain at their original time. ", infeasible));
        }
        text.append("Every non-exempt job in this plan finishes on or before its deadline.");
        if (prices.isSeeded()) {
            text.append(" Note: no stored price forecast covered this window, so a seeded "
                + "fallback price curve was used for both the baseline and the optimized cost.");
        }
        return text.toString().trim();
    }

    private static long count(SolverResult result, PerJobStatus status) {
        return result.assignments().stream().filter(a -> a.status() == status).count();
    }

    /**
     * Price context for the jobs that actually moved: the dearest hour vacated and the
     * cheapest hour moved into. Returns an empty string when no price is available for
     * either side, so the sentence still reads correctly without it.
     */
    private static String movedPriceContext(SolverResult result, PriceSeries prices) {
        List<SolverAssignment> movedJobs = result.assignments().stream()
            .filter(a -> a.status() == PerJobStatus.RESCHEDULED)
            .toList();

        var vacated = movedJobs.stream()
            .flatMap(a -> prices.priceAt(a.job().baselineSlot())
                .map(price -> java.util.Map.entry(a.job().baselineSlot(), price)).stream())
            .max(java.util.Map.Entry.comparingByValue());
        var occupied = movedJobs.stream()
            .flatMap(a -> prices.priceAt(a.slotStart())
                .map(price -> java.util.Map.entry(a.slotStart(), price)).stream())
            .min(java.util.Map.Entry.comparingByValue());

        if (vacated.isEmpty() || occupied.isEmpty()) {
            return "";
        }
        return String.format(Locale.ROOT,
            " out of the %02d:00 hour ($%.2f/MWh) and into the %02d:00 hour ($%.2f/MWh)",
            vacated.get().getKey().getHour(), vacated.get().getValue(),
            occupied.get().getKey().getHour(), occupied.get().getValue());
    }
}
