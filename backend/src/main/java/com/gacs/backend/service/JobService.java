package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.JobRequestDto;
import com.gacs.backend.dto.JobResponseDto;
import com.gacs.backend.exception.ConflictException;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.ConfirmationOutcome;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.JobPriority;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.model.WorkloadCategory;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class JobService {

    public static final Set<String> VALID_STATUSES = Set.of(
            "PENDING", "SCHEDULED", "RUNNING", "COMPLETED", "CANCELLED"
    );

    private final JobRepository jobRepository;
    private final ScheduleInputs inputs;
    private final Clock clock;
    private final OptimizationPlanRepository plans;
    private final WorkloadScheduleRepository schedules;
    private final AuditEventService audit;
    private final UserRepository users;
    private final JobStatusResolver statuses;

    public JobService(JobRepository jobRepository, ScheduleInputs inputs, Clock clock,
                      OptimizationPlanRepository plans, WorkloadScheduleRepository schedules,
                      AuditEventService audit, UserRepository users, JobStatusResolver statuses) {
        this.statuses = statuses;
        this.inputs = inputs;
        this.jobRepository = jobRepository;
        this.clock = clock;
        this.plans = plans;
        this.schedules = schedules;
        this.audit = audit;
        this.users = users;
    }

    /** The job with where it stands now. {@code status} is worked out from its plans, not read from the row. */
    private static JobResponseDto dto(Job job, Map<Long, JobStatusResolver.Derived> derived) {
        JobResponseDto dto = JobResponseDto.fromEntity(job);
        var state = derived.get(job.getId());
        dto.setStatus(state.status().name());
        dto.setStatusReason(state.reason());
        dto.setApprovedRunId(state.approved() ? state.runId() : null);
        return dto;
    }

    /**
     * Lists all compute jobs belonging to the authenticated operator chronologically.
     */
    @Transactional(readOnly = true)
    public List<JobResponseDto> getJobsByOwner(String ownerEmail) {
        List<Job> own = jobRepository.findByOwnerEmailOrderByOriginalStartTimeAsc(ownerEmail);
        Map<Long, JobStatusResolver.Derived> derived = statuses.resolve(own);
        return own.stream().map(job -> dto(job, derived)).toList();
    }

    /** Every owner's jobs with the owner's name, for an admin. */
    @Transactional(readOnly = true)
    public List<JobResponseDto> getAllJobs() {
        List<Job> all = jobRepository.findAllByOrderByOriginalStartTimeAsc();
        Map<Long, JobStatusResolver.Derived> derived = statuses.resolve(all);
        Map<String, String> names = new HashMap<>();
        users.findAll().forEach(user -> names.put(user.getEmail(), user.displayName()));
        return all.stream().map(job -> {
            JobResponseDto dto = dto(job, derived);
            dto.setOwnerName(names.getOrDefault(job.getOwnerEmail(), job.getOwnerEmail()));
            return dto;
        }).toList();
    }

    /**
     * Retrieves a single compute job scoped to the authenticated operator.
     */
    @Transactional(readOnly = true)
    public JobResponseDto getJobById(Long id, String ownerEmail) {
        Job job = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
        return dto(job, statuses.resolve(List.of(job)));
    }

    /**
     * Creates a new compute job for the authenticated operator.
     */
    @Transactional
    public JobResponseDto createJob(JobRequestDto request, String ownerEmail) {
        validateJobConstraints(request);
        validateNotInThePast(request.getOriginalStartTime(), request.getDeadline());

        String status = (request.getStatus() != null && !request.getStatus().isBlank())
                ? request.getStatus().trim().toUpperCase()
                : "PENDING";

        JobPriority priority = JobPriority.fromString(request.getPriority());
        WorkloadCategory workloadCategory = WorkloadCategory.fromString(request.getWorkloadCategory());

        Job job = Job.builder()
                .ownerEmail(ownerEmail)
                .name(request.getName().trim())
                .description(blankToNull(request.getDescription()))
                .cpuHours(request.getCpuHours() != null ? request.getCpuHours() : 0.0)
                .gpuHours(request.getGpuHours() != null ? request.getGpuHours() : 0.0)
                .originalStartTime(request.getOriginalStartTime())
                .deadline(request.getDeadline())
                .flexibilityWindowHours(request.getFlexibilityWindowHours() != null ? request.getFlexibilityWindowHours() : 0)
                .isExempt(request.isExempt())
                .rawConstraints(request.getRawConstraints())
                .status(status)
                .priority(priority)
                .workloadCategory(workloadCategory)
                .build();

        applyParseMetadata(job, request);
        Job saved = jobRepository.save(job);
        return dto(saved, statuses.resolve(List.of(saved)));
    }

    /**
     * Updates an existing compute job scoped to the authenticated operator.
     */
    @Transactional
    public JobResponseDto updateJob(Long id, JobRequestDto request, String ownerEmail) {
        inputs.lock(ownerEmail);
        Job existing = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));

        // An approved job is locked: every field, name and description included. Delete is the only change.
        // So is a finished one: its plan goes stale when its slots end, which would lift the lock.
        var state = statuses.resolve(List.of(existing)).get(id);
        if (state.approved() && state.status() == JobStatusResolver.Status.COMPLETED)
            throw new ConflictException("This job has finished and cannot be edited. You can delete it.");
        if (state.approved())
            throw new ConflictException("This job is approved and cannot be edited. You can delete it.");

        validateJobConstraints(request);
        // Only a time the edit changes is checked: a job whose start has passed stays editable (Lock
        // switch, title, hours), as long as the edit does not move a time into the past.
        validateNotInThePast(
                request.getOriginalStartTime().equals(existing.getOriginalStartTime()) ? null : request.getOriginalStartTime(),
                request.getDeadline().equals(existing.getDeadline()) ? null : request.getDeadline());

        var before = ScheduleInputs.state(existing);
        applyParseMetadata(existing, request);

        existing.setName(request.getName().trim());
        // Not a scheduling input, so it is outside the ScheduleInputs comparison below.
        if (request.getDescription() != null) {
            existing.setDescription(blankToNull(request.getDescription()));
        }
        existing.setCpuHours(request.getCpuHours() != null ? request.getCpuHours() : 0.0);
        existing.setGpuHours(request.getGpuHours() != null ? request.getGpuHours() : 0.0);
        existing.setOriginalStartTime(request.getOriginalStartTime());
        existing.setDeadline(request.getDeadline());
        existing.setFlexibilityWindowHours(request.getFlexibilityWindowHours() != null ? request.getFlexibilityWindowHours() : 0);
        existing.setExempt(request.isExempt());

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            existing.setStatus(request.getStatus().trim().toUpperCase());
        }

        if (request.getPriority() != null && !request.getPriority().isBlank()) {
            existing.setPriority(JobPriority.fromString(request.getPriority()));
        }

        if (request.getWorkloadCategory() != null && !request.getWorkloadCategory().isBlank()) {
            existing.setWorkloadCategory(WorkloadCategory.fromString(request.getWorkloadCategory()));
        }

        if (!before.equals(ScheduleInputs.state(existing))) {
            existing.setSchedulingRevision(existing.getSchedulingRevision() + 1);
        }
        Job updated = jobRepository.save(existing);
        return dto(updated, statuses.resolve(List.of(updated)));
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Resolve and validate provenance before changing the managed entity. */
    private void applyParseMetadata(Job job, JobRequestDto request) {
        String source = request.hasRawConstraints() ? request.getRawConstraints() : job.getRawConstraints();
        boolean sourceChanged = !Objects.equals(source, job.getRawConstraints());
        var parsedAt = sourceChanged ? null : job.getParsedAt();
        var outcome = sourceChanged ? null : job.getConfirmationOutcome();
        if (request.hasParsedAt()) {
            parsedAt = request.getParsedAt();
        }
        if (request.hasConfirmationOutcome()) {
            outcome = request.getConfirmationOutcome();
        } else if (request.hasParsedAt() && parsedAt != null) {
            outcome = ConfirmationOutcome.PENDING;
        }
        if ((parsedAt != null || outcome != null) && (source == null || source.isBlank())) {
            throw new IllegalArgumentException("Parse metadata requires nonblank rawConstraints");
        }
        if (outcome != null && parsedAt == null) {
            throw new IllegalArgumentException("confirmationOutcome requires parsedAt");
        }
        job.setRawConstraints(source);
        job.setParsedAt(parsedAt);
        job.setConfirmationOutcome(outcome);
    }

    /**
     * Deletes a job. Its owner can, and so can an admin; anyone else gets 404.
     *
     * <p>In an approved plan the job only leaves the plan: the other jobs keep their approval and their
     * times, like reverting one commit. A pending plan that held it is caught by the gate's next check.
     */
    @Transactional
    public void deleteJob(Long id, String callerEmail, boolean admin) {
        String ownerEmail = admin
                ? jobRepository.findById(id).map(Job::getOwnerEmail)
                        .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id))
                : callerEmail;
        User owner = inputs.lock(ownerEmail);
        Job existing = jobRepository.findByIdAndOwnerEmail(id, ownerEmail)
                .orElseThrow(() -> new ResourceNotFoundException("Job not found with id: " + id));
        for (OptimizationPlan plan : plans.findByOwnerIdAndReviewState(owner.getId(), ReviewState.APPROVED)) {
            if (!plan.liveJobIds().contains(id)) continue;
            plan.removeJobs(List.of(id));
            audit.recordEvent(new AuditEventRequestDto(AuditActionType.REMOVE_JOB, plan.getRunId(),
                    ScheduleHashUtil.computeSha256(plan.getResultJson()),
                    "Removed job " + existing.getName() + " (#" + id + ") from the plan"));
            if (plan.liveJobIds().isEmpty())
                plan.invalidate(ReviewState.INVALIDATED, "all of its jobs were deleted", clock.instant());
        }
        schedules.deleteByJobIdAndStatusNot(id, "Completed");
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
        validateTiming(request.getOriginalStartTime(), request.getDeadline(), request.getFlexibilityWindowHours());

        if (request.getRawConstraints() != null && request.getRawConstraints().length() > 1000) {
            throw new IllegalArgumentException("rawConstraints must not exceed 1000 characters");
        }

        if (request.getStatus() != null && !request.getStatus().isBlank()) {
            String normalizedStatus = request.getStatus().trim().toUpperCase();
            if (!VALID_STATUSES.contains(normalizedStatus)) {
                throw new IllegalArgumentException("Invalid status: '" + request.getStatus() + "'. Allowed statuses: " + VALID_STATUSES);
            }
        }

        validatePriority(request.getPriority());
        validateWorkloadCategory(request.getWorkloadCategory());
    }

    /**
     * Timing rules shared by the job API and the constraint parser. Values that are absent are not checked
     * here: the job API requires start and deadline before calling this, while a parse may cover only some of
     * them.
     */
    public void validateTiming(LocalDateTime originalStartTime, LocalDateTime deadline, Integer flexibilityWindowHours) {
        if (originalStartTime != null && deadline != null && deadline.isBefore(originalStartTime)) {
            throw new IllegalArgumentException("deadline must be greater than or equal to originalStartTime");
        }
        if (flexibilityWindowHours != null && flexibilityWindowHours < 0) {
            throw new IllegalArgumentException("flexibilityWindowHours must be non-negative");
        }
    }

    /**
     * Rejects a job whose scheduling window has already elapsed. Nothing can be run in an hour that
     * has passed, so a job starting before the current hour is not schedulable the moment it is saved:
     * the optimiser would report it {@code NO_FEASIBLE_SLOT} and the operator could not act on the
     * plan it produced.
     *
     * <p>The current hour itself is allowed. Starts are on the hour, so excluding it would leave an
     * operator unable to schedule anything in the hour they are working in.
     *
     * <p>Applies to updates as well as creates, but an update passes only the times it changes, so a
     * past start cannot be introduced by editing while a job whose start has already passed can still
     * be edited in other ways. A {@code null} argument is not checked.
     *
     * <p>Deliberately not part of {@link #validateTiming}: the constraint parser shares that method
     * and only proposes values for the operator to review, so it must still be able to parse "6pm
     * yesterday" and show it as a reviewable field rather than throw.
     */
    public void validateNotInThePast(LocalDateTime originalStartTime, LocalDateTime deadline) {
        LocalDateTime currentHour = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.HOURS);
        if (originalStartTime != null && originalStartTime.isBefore(currentHour)) {
            throw new IllegalArgumentException("originalStartTime " + originalStartTime
                    + " is in the past; it must be at or after the current hour " + currentHour
                    + " UTC, because a job cannot be run in an hour that has already passed");
        }
        if (deadline != null && !deadline.isAfter(currentHour)) {
            throw new IllegalArgumentException("deadline " + deadline
                    + " is in the past; it must be after the current hour " + currentHour + " UTC");
        }
    }

    /** Priority rule shared by the job API and the constraint parser. Blank means the default. */
    public void validatePriority(String priority) {
        if (priority != null && !priority.isBlank()) {
            JobPriority.fromString(priority);
        }
    }

    /** Workload category rule for the job API. Blank means the default (ML_TRAINING). */
    public void validateWorkloadCategory(String workloadCategory) {
        if (workloadCategory != null && !workloadCategory.isBlank()) {
            WorkloadCategory.fromString(workloadCategory);
        }
    }
}
