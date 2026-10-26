package com.gacs.backend.service;

import com.gacs.backend.dto.FacilityRequestDto;
import com.gacs.backend.dto.FacilityResponseDto;
import com.gacs.backend.model.Facility;
import com.gacs.backend.repository.FacilityRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class FacilityService {

    private final FacilityRepository facilityRepository;

    public FacilityService(FacilityRepository facilityRepository) {
        this.facilityRepository = facilityRepository;
    }

    /**
     * Retrieves the operator's facility configuration, automatically creating a default one if none exists.
     * Safely handles and resolves any duplicate rows from concurrent requests.
     */
    @Transactional
    public FacilityResponseDto getOrCreateFacility(String ownerEmail) {
        List<Facility> facilities = facilityRepository.findAllByOwnerEmail(ownerEmail);
        if (facilities.isEmpty()) {
            Facility defaultFacility = Facility.builder()
                    .ownerEmail(ownerEmail)
                    .name("Primary Compute Cluster")
                    .maxCpuHours(256.0)
                    .maxGpuHours(64.0)
                    .location("LZ_NORTH")
                    .build();
            return FacilityResponseDto.fromEntity(facilityRepository.save(defaultFacility));
        }

        Facility primary = facilities.get(0);
        // Clean up any duplicate records created by simultaneous requests
        if (facilities.size() > 1) {
            for (int i = 1; i < facilities.size(); i++) {
                facilityRepository.delete(facilities.get(i));
            }
        }
        return FacilityResponseDto.fromEntity(primary);
    }

    /**
     * Updates the operator's facility configuration.
     */
    @Transactional
    public FacilityResponseDto updateFacility(FacilityRequestDto request, String ownerEmail) {
        if (request.getMaxCpuHours() == 0.0 && request.getMaxGpuHours() == 0.0) {
            throw new IllegalArgumentException("Facility must have maxCpuHours or maxGpuHours greater than 0");
        }

        List<Facility> facilities = facilityRepository.findAllByOwnerEmail(ownerEmail);
        Facility facility = facilities.isEmpty()
                ? Facility.builder().ownerEmail(ownerEmail).build()
                : facilities.get(0);

        if (facilities.size() > 1) {
            for (int i = 1; i < facilities.size(); i++) {
                facilityRepository.delete(facilities.get(i));
            }
        }

        facility.setName(request.getName().trim());
        facility.setMaxCpuHours(request.getMaxCpuHours());
        facility.setMaxGpuHours(request.getMaxGpuHours());
        facility.setLocation(request.getLocation().trim());

        Facility saved = facilityRepository.save(facility);
        return FacilityResponseDto.fromEntity(saved);
    }
}
