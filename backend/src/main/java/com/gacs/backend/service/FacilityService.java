package com.gacs.backend.service;

import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.model.Facility;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.FacilityRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;

/** One facility, shared by every user. Everyone reads it; only an admin changes it. */
@Service
public class FacilityService {

    private final FacilityRepository facilityRepository;
    private final OptimizationPlanRepository plans;
    private final Clock clock;

    public FacilityService(FacilityRepository facilityRepository, OptimizationPlanRepository plans, Clock clock) {
        this.facilityRepository = facilityRepository;
        this.plans = plans;
        this.clock = clock;
    }

    /**
     * The shared facility, created with the defaults on first read. {@code ownerEmail} only records who
     * triggered the creation. It never selects a facility.
     */
    // ponytail: two first reads at once can each insert a row; the lowest ID wins, the other is never read.
    @Transactional
    public FacilityResponseDto getOrCreateFacility(String ownerEmail) {
        return FacilityResponseDto.fromEntity(shared(ownerEmail));
    }

    private Facility shared(String ownerEmail) {
        return facilityRepository.findFirstByOrderByIdAsc().orElseGet(() -> facilityRepository.save(
                Facility.builder().ownerEmail(ownerEmail).build()));
    }

    /**
     * Admin change to the shared facility. Locks the facility row only. Taking an owner's row after it
     * could deadlock with an approval, which locks the owner first. So a change ends the approval of every
     * approved plan directly here, and does not go through {@link ScheduleInputs#invalidate}.
     */
    @Transactional
    public FacilityResponseDto updateFacility(FacilityRequestDto request, String adminEmail) {
        if (request.getMaxCpuHours() == 0.0 && request.getMaxGpuHours() == 0.0) {
            throw new IllegalArgumentException("Facility must have maxCpuHours or maxGpuHours greater than 0");
        }
        shared(adminEmail); // the row must exist before it can be locked
        Facility facility = facilityRepository.lockShared().orElseThrow();

        boolean changed = !java.util.Objects.equals(facility.getMaxCpuHours(), request.getMaxCpuHours())
            || !java.util.Objects.equals(facility.getMaxGpuHours(), request.getMaxGpuHours())
            || !java.util.Objects.equals(facility.getLocation(), request.getLocation().trim());
        if (changed) {
            facility.setSchedulingRevision(facility.getSchedulingRevision() + 1);
            for (var plan : plans.findByReviewState(ReviewState.APPROVED)) {
                plan.invalidate(ReviewState.INVALIDATED, "Facility scheduling inputs changed", clock.instant());
            }
        }
        facility.setName(request.getName().trim());
        facility.setMaxCpuHours(request.getMaxCpuHours());
        facility.setMaxGpuHours(request.getMaxGpuHours());
        facility.setLocation(request.getLocation().trim());
        return FacilityResponseDto.fromEntity(facilityRepository.save(facility));
    }
}
