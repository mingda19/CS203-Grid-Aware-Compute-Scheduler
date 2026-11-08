package com.gacs.backend.service;

import com.gacs.backend.dto.FacilityUtilizationDto;
import com.gacs.backend.dto.FacilityUtilizationDto.State;
import com.gacs.backend.dto.FacilityUtilizationDto.Usage;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.OptimizationPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of the shared facility the approved plans of every owner schedule in the current hour.
 *
 * <p>Plans are read without {@link ScheduleGate#check}: checking another owner's plan needs that owner's
 * lock, and two callers locking owners in opposite orders deadlock. The approved state is kept current
 * elsewhere: a facility change ends every approval, a job delete or edit does so for its own plans, and
 * {@link ScheduleExpiryService} runs the gate every minute.
 */
@Service
public class FacilityUtilizationService {

    private final OptimizationPlanRepository plans;
    private final FacilityService facilities;
    private final StoredScheduleReader reader;
    private final Clock clock;

    public FacilityUtilizationService(OptimizationPlanRepository plans, FacilityService facilities,
                                      StoredScheduleReader reader, Clock clock) {
        this.plans = plans;
        this.facilities = facilities;
        this.reader = reader;
        this.clock = clock;
    }

    @Transactional
    public FacilityUtilizationDto current(String email) {
        var facility = facilities.getOrCreateFacility(email);
        double cpuLimit = facility.getMaxCpuHours() == null ? 0.0 : facility.getMaxCpuHours();
        double gpuLimit = facility.getMaxGpuHours() == null ? 0.0 : facility.getMaxGpuHours();
        LocalDateTime hour = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);

        List<String> runIds = new ArrayList<>();
        double cpu = 0.0;
        double gpu = 0.0;
        for (OptimizationPlan plan : plans.findByReviewState(ReviewState.APPROVED)) {
            var schedule = reader.read(plan.getResultJson());
            if (schedule.isEmpty()) continue;
            runIds.add(plan.getRunId());
            if (schedule.get().items() == null) continue;
            for (ScheduleItem item : schedule.get().items()) {
                if (item.allocations() == null) continue;
                for (Allocation slot : item.allocations()) {
                    if (hour.equals(slot.intervalStartUtc())) {
                        cpu += slot.cpuHours();
                        gpu += slot.gpuHours();
                    }
                }
            }
        }

        if (runIds.isEmpty()) {
            return new FacilityUtilizationDto(State.NO_APPROVED_PLAN, List.of(), hour,
                new Usage(null, cpuLimit, null), new Usage(null, gpuLimit, null));
        }
        return new FacilityUtilizationDto(State.LIVE, runIds, hour, usage(cpu, cpuLimit), usage(gpu, gpuLimit));
    }

    private static Usage usage(double used, double limit) {
        return new Usage(used, limit, limit > 0 ? used / limit * 100 : null);
    }
}
