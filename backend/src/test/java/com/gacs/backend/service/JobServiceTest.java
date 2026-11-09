package com.gacs.backend.service;

import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.repository.JobRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
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

    /** One hour before {@link #start}, so the jobs these tests create are not in the past. */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-20T09:00:00Z"), ZoneOffset.UTC);

    private final String owner = "operator@datacenter.io";
    private final LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
    private final LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 18, 0);

    @BeforeEach
    void setUp() {
        jobService = new JobService(jobRepository, org.mockito.Mockito.mock(com.gacs.backend.service.ScheduleInputs.class), CLOCK);
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
        assertThat(captured.getPriority()).isEqualTo(JobPriority.NORMAL);
    }

    @Test
    @DisplayName("createJob with explicit priority persists and returns priority")
    void createJob_WithExplicitPriority() {
        JobRequestDto request = JobRequestDto.builder()
                .name("High Priority Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .priority("HIGH")
                .build();

        Job savedJob = Job.builder()
                .id(105L)
                .ownerEmail(owner)
                .name("High Priority Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .priority(JobPriority.HIGH)
                .build();

        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        JobResponseDto response = jobService.createJob(request, owner);

        assertThat(response.getPriority()).isEqualTo(JobPriority.HIGH);
        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getPriority()).isEqualTo(JobPriority.HIGH);
    }

    @Test
    @DisplayName("createJob with explicit workloadCategory persists and returns it")
    void createJob_WithExplicitWorkloadCategory() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Mining Rig")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory("CRYPTO_MINING")
                .build();

        Job savedJob = Job.builder()
                .id(106L)
                .ownerEmail(owner)
                .name("Mining Rig")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory(com.gacs.backend.model.WorkloadCategory.CRYPTO_MINING)
                .build();

        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        JobResponseDto response = jobService.createJob(request, owner);

        assertThat(response.getWorkloadCategory()).isEqualTo(com.gacs.backend.model.WorkloadCategory.CRYPTO_MINING);
        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getWorkloadCategory()).isEqualTo(com.gacs.backend.model.WorkloadCategory.CRYPTO_MINING);
    }

    @Test
    @DisplayName("createJob without workloadCategory defaults to ML_TRAINING")
    void createJob_DefaultsWorkloadCategory() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Default Category Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .build();

        Job savedJob = Job.builder()
                .id(107L)
                .ownerEmail(owner)
                .name("Default Category Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory(com.gacs.backend.model.WorkloadCategory.ML_TRAINING)
                .build();

        when(jobRepository.save(any(Job.class))).thenReturn(savedJob);

        jobService.createJob(request, owner);

        ArgumentCaptor<Job> captor = ArgumentCaptor.forClass(Job.class);
        verify(jobRepository).save(captor.capture());
        assertThat(captor.getValue().getWorkloadCategory()).isEqualTo(com.gacs.backend.model.WorkloadCategory.ML_TRAINING);
    }

    @Test
    @DisplayName("validateJobConstraints throws clear error when workloadCategory is unknown")
    void validate_ThrowsWhenInvalidWorkloadCategory() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Invalid Category Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory("UNDERWATER_BASKET_WEAVING")
                .build();

        assertThatThrownBy(() -> jobService.validateJobConstraints(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid workloadCategory: 'UNDERWATER_BASKET_WEAVING'");
    }

    @Test
    @DisplayName("validateJobConstraints throws clear error when priority is unknown")
    void validate_ThrowsWhenInvalidPriority() {
        JobRequestDto request = JobRequestDto.builder()
                .name("Invalid Priority Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(start)
                .deadline(deadline)
                .priority("URGENT")
                .build();

        assertThatThrownBy(() -> jobService.validateJobConstraints(request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid priority: 'URGENT'. Allowed priorities: CRITICAL, HIGH, NORMAL, LOW");
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
                .priority("CRITICAL")
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
        assertThat(updated.getPriority()).isEqualTo(JobPriority.CRITICAL);
        assertThat(updated.getRawConstraints()).isEqualTo("Changed to exempt priority");
        assertThat(updated.getStatus()).isEqualTo("SCHEDULED");
    }

    @Test
    @DisplayName("description: stored on create, kept when omitted on update, cleared when blank")
    void description_createKeepClear() {
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        JobRequestDto create = JobRequestDto.builder().name("D").cpuHours(1.0).gpuHours(0.0)
                .originalStartTime(start).deadline(deadline).description("  nightly ETL  ").build();
        assertThat(jobService.createJob(create, owner).getDescription()).isEqualTo("nightly ETL");

        Job existing = Job.builder().id(204L).ownerEmail(owner).name("D").cpuHours(1.0).gpuHours(0.0)
                .originalStartTime(start).deadline(deadline).description("nightly ETL").status("PENDING").build();
        when(jobRepository.findByIdAndOwnerEmail(204L, owner)).thenReturn(Optional.of(existing));
        JobRequestDto omit = JobRequestDto.builder().name("D").cpuHours(1.0).gpuHours(0.0)
                .originalStartTime(start).deadline(deadline).build();
        assertThat(jobService.updateJob(204L, omit, owner).getDescription()).isEqualTo("nightly ETL");

        JobRequestDto blank = JobRequestDto.builder().name("D").cpuHours(1.0).gpuHours(0.0)
                .originalStartTime(start).deadline(deadline).description("   ").build();
        assertThat(jobService.updateJob(204L, blank, owner).getDescription()).isNull();
    }

    @Test
    @DisplayName("updateJob changes workloadCategory when supplied")
    void updateJob_ChangesWorkloadCategory() {
        Job existing = Job.builder()
                .id(203L)
                .ownerEmail(owner)
                .name("Batch Job")
                .cpuHours(10.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory(com.gacs.backend.model.WorkloadCategory.ML_TRAINING)
                .status("PENDING")
                .build();

        when(jobRepository.findByIdAndOwnerEmail(203L, owner)).thenReturn(Optional.of(existing));

        JobRequestDto updateRequest = JobRequestDto.builder()
                .name("Batch Job")
                .cpuHours(10.0)
                .gpuHours(0.0)
                .originalStartTime(start)
                .deadline(deadline)
                .workloadCategory("HPC_BATCH")
                .build();

        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        JobResponseDto updated = jobService.updateJob(203L, updateRequest, owner);

        assertThat(updated.getWorkloadCategory()).isEqualTo(com.gacs.backend.model.WorkloadCategory.HPC_BATCH);
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

    // ------------------------------------------------------------------ elapsed scheduling windows

    /**
     * A job whose start has passed can never be run: the optimiser will not place work in an elapsed
     * hour, so accepting one would only store a job that is unschedulable from the moment it is saved.
     * CLOCK is fixed at 2026-10-20T09:00Z, so {@link #start} (10:00) is still an hour ahead.
     */
    private JobRequestDto.JobRequestDtoBuilder validRequest() {
        return JobRequestDto.builder()
                .name("Scheduling window")
                .cpuHours(10.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(2);
    }

    @Test
    @DisplayName("createJob rejects a start time before the current hour")
    void createJob_RejectsElapsedStart() {
        JobRequestDto request = validRequest().originalStartTime(start.minusDays(1)).build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is in the past")
                .hasMessageContaining("2026-10-20T09:00");

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("createJob accepts a start time in the hour already under way")
    void createJob_AcceptsCurrentHour() {
        // 09:00 is the hour the clock is in. Starts are on the hour, so refusing it would leave an
        // operator unable to schedule anything in the hour they are working in.
        JobRequestDto request = validRequest().originalStartTime(LocalDateTime.of(2026, 10, 20, 9, 0)).build();
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));

        assertThat(jobService.createJob(request, owner).getOriginalStartTime())
                .isEqualTo(LocalDateTime.of(2026, 10, 20, 9, 0));
    }

    @Test
    @DisplayName("createJob rejects a deadline that has already passed")
    void createJob_RejectsElapsedDeadline() {
        JobRequestDto request = validRequest()
                .originalStartTime(start.minusDays(2))
                .deadline(start.minusDays(1))
                .build();

        assertThatThrownBy(() -> jobService.createJob(request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is in the past");

        verify(jobRepository, never()).save(any(Job.class));
    }

    @Test
    @DisplayName("updateJob accepts an edit that leaves an elapsed start unchanged, such as the Lock switch")
    void updateJob_AcceptsUnchangedElapsedStart() {
        LocalDateTime elapsedStart = start.minusDays(1);
        Job existing = Job.builder().id(203L).ownerEmail(owner).name("Started")
                .cpuHours(10.0).gpuHours(0.0).originalStartTime(elapsedStart).deadline(deadline)
                .flexibilityWindowHours(2).isExempt(false).status("PENDING").build();
        when(jobRepository.findByIdAndOwnerEmail(203L, owner)).thenReturn(Optional.of(existing));
        when(jobRepository.save(any(Job.class))).thenAnswer(invocation -> invocation.getArgument(0));
        JobRequestDto request = validRequest().name("Started").originalStartTime(elapsedStart).isExempt(true).build();

        assertThat(jobService.updateJob(203L, request, owner).isExempt()).isTrue();
    }

    @Test
    @DisplayName("updateJob is gated too, so a past start cannot be reintroduced by editing")
    void updateJob_RejectsElapsedStart() {
        JobRequestDto request = validRequest().originalStartTime(start.minusDays(1)).build();
        // updateJob resolves the job before validating, so the lookup has to succeed to reach the check.
        when(jobRepository.findByIdAndOwnerEmail(202L, owner))
                .thenReturn(Optional.of(Job.builder().id(202L).ownerEmail(owner).build()));

        assertThatThrownBy(() -> jobService.updateJob(202L, request, owner))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is in the past");

        verify(jobRepository, never()).save(any(Job.class));
    }
}
