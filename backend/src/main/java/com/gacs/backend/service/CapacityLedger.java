package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.service.allocation.CapacityLimit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * What approved plans of every owner use, hour by hour, from the current hour on. A run solves against the
 * facility less this, so it cannot place work where an approved plan already is.
 *
 * <p>Plans are read without {@link ScheduleGate#check}, for the reason given on
 * {@link FacilityUtilizationService}. Jobs that left a plan are skipped.
 */
@Service
public class CapacityLedger {

    private final OptimizationPlanRepository plans;
    private final StoredScheduleReader reader;
    private final Clock clock;

    public CapacityLedger(OptimizationPlanRepository plans, StoredScheduleReader reader, Clock clock) {
        this.plans = plans;
        this.reader = reader;
        this.clock = clock;
    }

    public LocalDateTime currentHour() {
        return LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);
    }

    @Transactional(readOnly = true)
    public Map<LocalDateTime, CapacityLimit> reserved() {
        LocalDateTime from = currentHour();
        Map<LocalDateTime, double[]> sums = new TreeMap<>();
        for (OptimizationPlan plan : plans.findByReviewState(ReviewState.APPROVED)) {
            var schedule = reader.read(plan.getResultJson());
            if (schedule.isEmpty() || schedule.get().items() == null) continue;
            addHours(sums, schedule.get().items(), plan.getRemovedJobIds(), from);
        }
        Map<LocalDateTime, CapacityLimit> reserved = new TreeMap<>();
        sums.forEach((slot, sum) -> reserved.put(slot, new CapacityLimit(sum[0], sum[1])));
        return reserved;
    }

    /** The CPU and GPU hours {@code items} use in each hour, as {cpu, gpu}. */
    public static Map<LocalDateTime, double[]> hoursBySlot(List<ScheduleItem> items) {
        Map<LocalDateTime, double[]> sums = new TreeMap<>();
        addHours(sums, items, Set.of(), null);
        return sums;
    }

    /** Adds the hours of {@code items} to {@code sums}, skipping the given jobs and any hour before {@code from} (null: none). */
    private static void addHours(Map<LocalDateTime, double[]> sums, List<ScheduleItem> items,
                                 Set<Long> skipJobIds, LocalDateTime from) {
        for (ScheduleItem item : items) {
            if (item.allocations() == null || skipJobIds.contains(item.jobId())) continue;
            for (Allocation slot : item.allocations()) {
                if (from != null && slot.intervalStartUtc().isBefore(from)) continue;
                double[] sum = sums.computeIfAbsent(slot.intervalStartUtc(), k -> new double[2]);
                sum[0] += slot.cpuHours();
                sum[1] += slot.gpuHours();
            }
        }
    }
}
