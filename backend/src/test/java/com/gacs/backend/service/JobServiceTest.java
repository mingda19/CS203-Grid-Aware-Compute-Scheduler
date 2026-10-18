package com.gacs.backend.service;

import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.Job;
import com.gacs.backend.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JobServiceTest {

    @Mock
    private JobRepository jobRepository;

    private JobService jobService;

    private final String owner = "operator@datacenter.io";
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 18, 0);

    @BeforeEach
    void setUp() {
        jobService = new JobService(jobRepository);
    }

    @Test
    @DisplayName("getJobsByOwner returns mapped DTOs ordered chronologically")
    void getJobsByOwner_ReturnsMappedList() {
        Job j1 = Job.builder()
                .id(1L)
                .ownerEmail(owner)
                .name("Job 1")
                .originalStartTime(start)
                .deadline(deadline)
                .isExempt(false)
                .build();

        Job j2 = Job.builder()
                .id(2L)
                .ownerEmail(owner)
                .name("Job 2")
                .originalStartTime(start.plusHours(4))
                .deadline(deadline.plusHours(4))
                .isExempt(true)
                .build();

        when(jobRepository.findByOwnerEmailOrderByOriginalStartTimeAsc(owner)).thenReturn(List.of(j1, j2));

        List<JobResponseDto> result = jobService.getJobsByOwner(owner);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).getName()).isEqualTo("Job 1");
        assertThat(result.get(0).isExempt()).isFalse();
        assertThat(result.get(1).getName()).isEqualTo("Job 2");
        assertThat(result.get(1).isExempt()).isTrue();
    }

    @Test
    @DisplayName("getJobById returns DTO when found for owner")
    void getJobById_Found_ReturnsDto() {
        Job job = Job.builder()
                .id(42L)
                .ownerEmail(owner)
                .name("Fine-Tuning Run")
                .cpuHours(64.0)
                .gpuHours(16.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(true)
                .build();

        when(jobRepository.findByIdAndOwnerEmail(42L, owner)).thenReturn(Optional.of(job));

        JobResponseDto dto = jobService.getJobById(42L, owner);

        assertThat(dto.getId()).isEqualTo(42L);
        assertThat(dto.getOwnerEmail()).isEqualTo(owner);
        assertThat(dto.isExempt()).isTrue();
        assertThat(dto.getCpuHours()).isEqualTo(64.0);
        assertThat(dto.getGpuHours()).isEqualTo(16.0);
    }

    @Test
    @DisplayName("getJobById throws ResourceNotFoundException when missing or wrong tenant")
    void getJobById_NotFound_ThrowsException() {
        when(jobRepository.findByIdAndOwnerEmail(99L, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.getJobById(99L, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found with id: 99");
    }

    @Test
    @DisplayName("createJob successfully persists and returns DTO with isExempt and originalStartTime")
    void createJob_Success() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Model Evaluation")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(6)
                .isExempt(true)
                .rawConstraints("Run within low price hours")
                .build();

        Job savedJob = Job.builder()
                .id(101L)
                .ownerEmail(owner)
                .name("Model Evaluation")
                .cpuHours(32.0)
                .gpuHours(8.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(6)
                .isExempt(true)
                .rawConstraints("Run within low price hours")
                .status("PENDING")
                .build();

        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        JobResponseDto response = jobService.createJob(request, owner);

        assertThat(response.getId()).isEqualTo(101L);
        assertThat(response.getName()).isEqualTo("Model Evaluation");
        assertThat(response.isExempt()).isTrue();
        assertThat(response.getOriginalStartTime()).isEqualTo(start);
        assertThat(response.getDeadline()).isEqualTo(deadline);
        assertThat(response.getStatus()).isEqualTo("PENDING");

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        Job captured = captor.getValue();
        assertThat(captured.getOwnerEmail()).isEqualTo(owner);
        assertThat(captured.isExempt()).isTrue();
    }

    @Test
    @DisplayName("validateJobConstraints throws when both cpuHours and gpuHours are zero")
    void validate_ThrowsWhenZeroResources() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Zero Resource Job")
                .cpuHours(0.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("At least one of cpuHours or gpuHours must be greater than 0");
    }

    @Test
    @DisplayName("validateJobConstraints throws when deadline is before originalStartTime")
    void validate_ThrowsWhenDeadlineBeforeStart() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Impossible Job")
                .cpuHours(10.0)
                .gpuHours(0.0)
                .originalStartTime(deadline)
                .deadline(start)
                .build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deadline must be greater than or equal to originalStartTime");
    }

    @Test
    @DisplayName("validateJobConstraints throws when flexibilityWindowHours is negative")
    void validate_ThrowsWhenNegativeFlexibility() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Negative Window")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(-2)
                .build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("flexibilityWindowHours must be non-negative");
    }

    @Test
    @DisplayName("validateJobConstraints throws when invalid status is provided")
    void validate_ThrowsWhenInvalidStatus() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Bad Status Job")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .status("UNKNOWN_STATUS")
                .build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid status");
    }

    @Test
    @DisplayName("updateJob updates all editable fields including isExempt and originalStartTime")
    void updateJob_Success() {
        Job existing = Job.builder()
                .id(202L)
                .ownerEmail(owner)
                .name("Old Name")
                .cpuHours(10.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(2)
                .isExempt(false)
                .status("PENDING")
                .build();

        when(jobRepository.findByIdAndOwnerEmail(202L, owner)).thenReturn(Optional.of(existing));

        LocalDateTime newStart = start.plusHours(2);
        LocalDateTime newDeadline = deadline.plusHours(2);

        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Updated Run")
                .cpuHours(20.0)
                .gpuHours(4.0)
                .originalStartTime(newStart)
                .deadline(newDeadline)
                .flexibilityWindowHours(8)
                .isExempt(true)
                .rawConstraints("Changed to exempt priority")
                .status("SCHEDULED")
                .build();

        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobResponseDto updated = jobService.updateJob(202L, updateRequest, owner);

        assertThat(updated.getName()).isEqualTo("Updated Run");
        assertThat(updated.getCpuHours()).isEqualTo(20.0);
        assertThat(updated.getGpuHours()).isEqualTo(4.0);
        assertThat(updated.getOriginalStartTime()).isEqualTo(newStart);
        assertThat(updated.getDeadline()).isEqualTo(newDeadline);
        assertThat(updated.getFlexibilityWindowHours()).isEqualTo(8);
        assertThat(updated.isExempt()).isTrue();
        assertThat(updated.getRawConstraints()).isEqualTo("Changed to exempt priority");
        assertThat(updated.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("updateJob throws ResourceNotFoundException when job not found for owner")
    void updateJob_NotFound_ThrowsException() {
        when(jobRepository.findByIdAndOwnerEmail(404L, owner)).thenReturn(Optional.empty());

        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Update Nonexistent")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .build();

        assertThatThrownBy(() -> jobService.updateJob(404L, updateRequest, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found with id: 404");
    }

    @Test
    @DisplayName("deleteJob deletes job when found for owner")
    void deleteJob_Success() {
        Job existing = Job.builder().id(303L).ownerEmail(owner).build();
        when(jobRepository.findByIdAndOwnerEmail(303L, owner)).thenReturn(Optional.of(existing));

        jobService.deleteJob(303L, owner);

        verify(jobRepository).delete(existing);
    }

    @Test
    @DisplayName("deleteJob throws ResourceNotFoundException when job not found for owner")
    void deleteJob_NotFound_ThrowsException() {
        when(jobRepository.findByIdAndOwnerEmail(404L, owner)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> jobService.deleteJob(404L, owner))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Job not found with id: 404");

        verify(jobRepository, never()).delete(any());
    }
}
