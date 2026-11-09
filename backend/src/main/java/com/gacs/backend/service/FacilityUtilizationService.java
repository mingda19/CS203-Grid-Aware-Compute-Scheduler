package com.gacs.backend.service;

import com.gacs.backend.dto.FacilityUtilizationDto;
import com.gacs.backend.dto.FacilityUtilizationDto.State;
import com.gacs.backend.dto.FacilityUtilizationDto.Usage;
import com.gacs.backend.dto.OptimizeResponseDto.Allocation;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * How much of the facility the live approved plans schedule in the current hour.
 *
 * <p>Only the server can say which approved plans are still live: {@link ScheduleGate#check} finds ended
 * slots, changed jobs, changed facility inputs and hash mismatches, and records the new state. This method
 * runs that gate, so it can change stored plan states.
 */
@Service
public class FacilityUtilizationService {

    private final OptimizationPlanRepository plans;
    private final UserRepository users;
    private final FacilityService facilities;
    private final ScheduleGate gate;
    private final Clock clock;

    public FacilityUtilizationService(OptimizationPlanRepository plans, UserRepository users,
                                      FacilityService facilities, ScheduleGate gate, Clock clock) {
        this.plans = plans;
        this.users = users;
        this.facilities = facilities;
        this.gate = gate;
        this.clock = clock;
    }

    // Keeps the invalidations the gate records, as ScheduleReviewService.decide does.
    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public FacilityUtilizationDto current(String email) {
        gate.lock(email);
        var owner = AuthenticatedOperators.resolve(users, email);
        var facility = facilities.getOrCreateFacility(email);
        double cpuLimit = facility.getMaxCpuHours() == null ? 0.0 : facility.getMaxCpuHours();
        double gpuLimit = facility.getMaxGpuHours() == null ? 0.0 : facility.getMaxGpuHours();
        LocalDateTime hour = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).truncatedTo(ChronoUnit.HOURS);

        List<String> runIds = new ArrayList<>();
        double cpu = 0.0;
        double gpu = 0.0;
        for (OptimizationPlan plan : plans.findByOwnerIdAndReviewState(owner.getId(), ReviewState.APPROVED)) {
            ScheduleGate.Checked checked;
            try {
                checked = gate.check(plan, email, true);
            } catch (ScheduleExportRejectedException notLive) {
                continue; // The gate already stored the new state.
            }
            runIds.add(plan.getRunId());
            if (checked.schedule().items() == null) continue;
            for (ScheduleItem item : checked.schedule().items()) {
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
