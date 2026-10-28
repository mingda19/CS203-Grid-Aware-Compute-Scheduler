package com.gacs.backend.service;

import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.repository.JobRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

@Service
public class JobService {

    public static final Set<String> VALID_STATUSES = Set.of(
            "PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "CANCELLED"
    );

    private final JobRepository jobRepository;

    public JobService(JobRepository jobRepository) {
        this.jobRepository = jobRepository;
    }

    /**
     * Lists all compute jobs belonging to the authenticated operator chronologically.
     */
    @Transactional(readOnly = true)
    public List<JobResponseDto> getJobsByOwner(String ownerEmail) {
        return jobRepository.findByOwnerEmailOrderByOriginalStartTimeAsc(ownerEmail).stream()
                .map(JobResponseDto::fromEntity)
                .toList();
    }

    /**
     * Retrieves a single compute job scoped to the authenticated operator.
     */
    @Transactional(readOnly = true)
    public JobResponseDto getJobById(Long id, String ownerEmail) {
        Job job = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
        return JobResponseDto.fromEntity(job);
    }

    /**
     * Creates a new compute job for the authenticated operator.
     */
    @Transactional
    public JobResponseDto createJob(JobRequestDto request, String ownerEmail) {
        validateJobConstraints(request);

        String status = (request.getStatus() != null && !request.getStatus().isBlank())
                ? request.getStatus().trim().toUpperCase()
                : "PENDING";

        JobPriority priority = JobPriority.fromString(request.getPriority());

        Job job = Job.builder()
                .ownerEmail(ownerEmail)
                .name(request.getName().trim())
                .cpuHours(request.getCpuHours() != null ? request.getCpuHours() : 0.0)
                .gpuHours(request.getGpuHours() != null ? request.getGpuHours() : 0.0)
                .originalStartTime(request.getOriginalStartTime())
                .deadline(request.getDeadline())
                .flexibilityWindowHours(request.getFlexibilityWindowHours() != null ? request.getFlexibilityWindowHours() : 0)
                .isExempt(request.isExempt())
                .rawConstraints(request.getRawConstraints())
                .status(status)
                .priority(priority)
                .build();

        Job saved = jobRepository.save(job);
        return JobResponseDto.fromEntity(saved);
    }

    /**
     * Updates an existing compute job scoped to the authenticated operator.
     */
    @Transactional
    public JobResponseDto updateJob(Long id, JobRequestDto request, String ownerEmail) {
        Job existing = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));

        validateJobConstraints(request);

        existing.setName(request.getName().trim());
        existing.setCpuHours(request.getCpuHours() != null ? request.getCpuHours() : 0.0);
        existing.setGpuHours(request.getGpuHours() != null ? request.getGpuHours() : 0.0);
        existing.setOriginalStartTime(request.getOriginalStartTime());
        existing.setDeadline(request.getDeadline());
        existing.setFlexibilityWindowHours(request.getFlexibilityWindowHours() != null ? request.getFlexibilityWindowHours() : 0);
        existing.setExempt(request.isExempt());
        existing.setRawConstraints(request.getRawConstraints());

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            existing.setStatus(request.getStatus().trim().toUpperCase());
        }

        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            existing.setPriority(JobPriority.fromString(request.getPriority()));
        }

        Job updated = jobRepository.save(existing);
        return JobResponseDto.fromEntity(updated);
    }

    /**
     * Deletes a compute job scoped to the authenticated operator.
     */
    @Transactional
    public void deleteJob(Long id, String ownerEmail) {
        Job existing = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
        jobRepository.delete(existing);
    }

    /**
     * Validates business invariants and domain rules per GRID-9 AC4 and GRID-112.
     */
    public void validateJobConstraints(JobRequestDto request) {
        if (request.getName() == null || request.getName().isBlank()) {
            throw new IllegalArgumentException("Job name cannot be blank");
        }
        if (request.getName().trim().length() > 255) {
            throw new IllegalArgumentException("Job name must not exceed 255 characters");
        }

        double cpu = request.getCpuHours() != null ? request.getCpuHours() : 0.0;
        double gpu = request.getGpuHours() != null ? request.getGpuHours() : 0.0;

        if (cpu < 0.0 || !Double.isFinite(cpu)) {
            throw new IllegalArgumentException("cpuHours must be non-negative and finite");
        }
        if (gpu < 0.0 || !Double.isFinite(gpu)) {
            throw new IllegalArgumentException("gpuHours must be non-negative and finite");
        }
        if (cpu <= 0.0 && gpu <= 0.0) {
            throw new IllegalArgumentException("At least one of cpuHours or gpuHours must be greater than 0");
        }

        if (request.getOriginalStartTime() == null) {
            throw new IllegalArgumentException("originalStartTime is required");
        }
        if (request.getDeadline() == null) {
            throw new IllegalArgumentException("deadline is required");
        }
        if (request.getDeadline().isBefore(request.getOriginalStartTime())) {
            throw new IllegalArgumentException("deadline must be greater than or equal to originalStartTime");
        }

        if (request.getFlexibilityWindowHours() != null && request.getFlexibilityWindowHours() < 0) {
            throw new IllegalArgumentException("flexibilityWindowHours must be non-negative");
        }

        if (request.getRawConstraints() != null && request.getRawConstraints().length() > 1000) {
            throw new IllegalArgumentException("rawConstraints must not exceed 1000 characters");
        }

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            String normalizedStatus = request.getStatus().trim().toUpperCase();
            if (!VALID_STATUSES.contains(normalizedStatus)) {
                throw new IllegalArgumentException("Invalid status: '" + request.getStatus() + "'. Allowed statuses: " + VALID_STATUSES);
            }
        }

        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            JobPriority.fromString(request.getPriority());
        }
    }
}
