package com.gacs.backend.service;

import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.WorkloadScheduleDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.Facility;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.WorkloadCategory;
import com.gacs.backend.model.User;
import com.gacs.backend.model.WorkloadSchedule;
import com.gacs.backend.repository.FacilityRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ScheduleApplicationService {
    private final ScheduleGate gate;
    private final WorkloadScheduleRepository schedules;
    private final FacilityRepository facilities;
    private final JobRepository jobs;
    private final AuditEventService audit;
    private final java.time.Clock clock;
    private final StoredScheduleReader reader;
    private final UserRepository users;
    private final AppSettingsService settings;
    private final OptimizationPlanRepository plans;
    public ScheduleApplicationService(ScheduleGate gate, WorkloadScheduleRepository schedules,
                                      FacilityRepository facilities, JobRepository jobs, AuditEventService audit,
                                      java.time.Clock clock, StoredScheduleReader reader, UserRepository users,
                                      AppSettingsService settings, OptimizationPlanRepository plans) {
        this.plans = plans;
        this.users = users;
        this.settings = settings;
        this.gate = gate;
        this.schedules = schedules;
        this.facilities = facilities;
        this.jobs = jobs;
        this.audit = audit;
        this.clock = clock;
        this.reader = reader;
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public List<WorkloadScheduleDto> apply(UUID runId, String email) {
        OptimizationPlan plan = gate.owned(runId, email);
        String name = plan.getOwner().displayName();
        return applyChecked(plan, gate.check(plan, true).schedule()).stream().map(s -> dto(s, name, true)).toList();
    }

    /**
     * Every user's calendar entries, soonest first. An entry of a plan that is no longer approved is hidden
     * (a stale or invalidated plan keeps its rows), unless its slot has already ended: that work ran, or
     * should have. Legacy entries with no run are kept. {@code mine} marks the caller's own entries, the only
     * ones the caller may change.
     */
    @Transactional(readOnly = true)
    public List<WorkloadScheduleDto> visible(String email) {
        var all = schedules.findAllByOrderByStartUtcAsc();
        var runIds = all.stream().map(WorkloadSchedule::getRunId).filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<String> approved = runIds.isEmpty() ? Set.of() : Set.copyOf(plans.findRunIdsInState(runIds, ReviewState.APPROVED));
        var now = java.time.LocalDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
        var shown = all.stream()
            .filter(s -> s.getRunId() == null || approved.contains(s.getRunId()) || !s.getEndUtc().isAfter(now)).toList();
        Map<String, String> names = users.findByEmailIn(shown.stream().map(WorkloadSchedule::getOwnerEmail).collect(Collectors.toSet()))
            .stream().collect(Collectors.toMap(User::getEmail, User::displayName));
        return shown.stream().map(s -> dto(s, names.getOrDefault(s.getOwnerEmail(), s.getOwnerEmail()),
            s.getOwnerEmail().equalsIgnoreCase(email))).toList();
    }

    /**
     * Writes an approved plan to the calendar. {@code schedule} is what {@code gate.check} returned for
     * this plan, and no second check runs here. Calendar rows belong to the plan's owner.
     */
    public List<WorkloadSchedule> applyChecked(OptimizationPlan plan, OptimizeResponseDto schedule) {
        String email = plan.getOwner().getEmail();
        var existing = schedules.findByRunIdOrderByIdAsc(plan.getRunId());
        if (plan.getAppliedAt() != null) return existing;
        String cluster = facilities.findFirstByOrderByIdAsc().map(Facility::getName).orElse("Primary Compute Cluster");
        // The job's own chosen category (bug 6) replaces the old GPU-hours guess ("any GPU hours at
        // all" implied ML Training, everything else implied HPC Batch) - a category the operator
        // never actually chose. Jobs deleted between approval and application fall back to the
        // calendar's default category rather than failing the whole dispatch.
        Map<Long, Job> ownerJobs = jobs.findByOwnerEmailAndIdIn(email,
                schedule.items().stream().map(OptimizeResponseDto.ScheduleItem::jobId).toList())
            .stream().collect(Collectors.toMap(Job::getId, j -> j));
        List<WorkloadSchedule> entries = new ArrayList<>();
        for (var item : schedule.items()) {
            if (item.scheduledStart() == null || item.scheduledEnd() == null) continue;
            Job job = ownerJobs.get(item.jobId());
            WorkloadCategory category = job != null ? job.getWorkloadCategory() : WorkloadCategory.ML_TRAINING;
            var entry = new WorkloadSchedule();
            entry.setOwnerEmail(email);
            entry.setRunId(plan.getRunId());
            entry.setJobId(item.jobId());
            entry.setTitle(item.jobName().substring(0, Math.min(200, item.jobName().length())));
            entry.setType(category.label());
            entry.setCluster(cluster.substring(0, Math.min(200, cluster.length())));
            // Display estimate retained from the existing calendar integration.
            entry.setPowerKw(Math.round(item.allocatedCpuHours() * 5 + item.allocatedGpuHours() * 25));
            entry.setSavings(item.savingsUsd() == null ? null : String.format(Locale.ROOT, "$%.2f", item.savingsUsd()));
            entry.setStatus("Scheduled");
            entry.setStartUtc(item.scheduledStart());
            entry.setEndUtc(item.scheduledEnd());
            entry.setNotes(item.perJobStatus());
            entries.add(entry);
        }
        if (entries.isEmpty()) throw new IllegalArgumentException("Run has no executable schedule slots");
        // A newer approved-and-applied run for a job supersedes any earlier applied run's calendar
        // entry for that same job (bug 4): without this, re-optimising and re-approving the same
        // job's work piled up one workload-schedule row per application instead of replacing it,
        // because ScheduleGate.supersede only marks the OLD OptimizationPlan STALE - it never
        // touches rows already written here by a previous apply() call for a different run.
        // Completed rows stay: they are the record of work that already ran.
        Set<Long> jobIds = entries.stream().map(WorkloadSchedule::getJobId).collect(Collectors.toSet());
        schedules.deleteByOwnerEmailAndJobIdInAndRunIdNotAndStatusNot(email, jobIds, plan.getRunId(), "Completed");
        schedules.saveAllAndFlush(entries);
        audit.recordEvent(new AuditEventRequestDto(AuditActionType.DISPATCH, plan.getRunId(),
            ScheduleHashUtil.computeSha256(plan.getResultJson()), "Applied approved schedule to calendar"));
        plan.markApplied(clock.instant());
        return entries;
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public WorkloadScheduleDto status(Long id, String status, String email) {
        if (status == null || !Set.of("Scheduled", "Running", "Throttled", "Completed").contains(status))
            throw new IllegalArgumentException("status must be Scheduled, Running, Throttled, or Completed");
        // Lock the same operator row before reading entries or checking their plan.
        gate.lock(email);
        var entry = schedules.findByIdAndOwnerEmail(id, email)
            .orElseThrow(() -> new ResourceNotFoundException("Workload schedule not found"));
        if (status.equals("Scheduled") || status.equals("Running")) {
            if (entry.getRunId() == null || entry.getJobId() == null)
                throw new ScheduleExportRejectedException(ScheduleExportRejectedException.Reason.NOT_APPROVED,
                    "Legacy calendar entry has no approved run; generate and apply a new recommendation");
            var plan = gate.owned(UUID.fromString(entry.getRunId()), email);
            gate.check(plan, true);
        }
        entry.setStatus(status);
        return dto(schedules.save(entry), nameOf(email), true);
    }

    @Transactional(noRollbackFor = ScheduleExportRejectedException.class)
    public PlanDetail detail(UUID runId, String email) {
        var plan = gate.reviewable(runId);
        try { gate.check(plan, false); }
        catch (ScheduleExportRejectedException ignored) { /* Detail reports the persisted refusal. */ }
        var caller = AuthenticatedOperators.resolve(users, email);
        return new PlanDetail(plan.getRunId(), plan.getReviewState(), plan.getInvalidationReason(),
            plan.getInvalidatedAt(), plan.getAppliedAt(), reader.read(plan.getResultJson()).orElse(null),
            plan.getOwner().displayName(), settings.canApprove(caller, plan), new TreeSet<>(plan.getRemovedJobIds()));
    }

    /**
     * @param appliedAt when {@link #apply} wrote this plan to the workload calendar, or null if it
     *                  never has been. The plan has carried this since application became gated, but
     *                  it was exposed on no DTO, so a client had no way to tell an approved plan that
     *                  is still waiting to be applied from one that was applied an hour ago. The
     *                  review UI consequently re-enabled "Add approved schedule to calendar" every
     *                  time the operator navigated back to it: it had been tracking the fact in
     *                  component state, which does not survive a remount, and the server never told
     *                  it otherwise. Clicking again correctly changed nothing, but offering the
     *                  action implied there was something left to do.
     * @param ownerName the plan's submitter, whose jobs it schedules
     * @param canApprove whether the caller may approve it, so the UI can explain a disabled button
     * @param removedJobIds jobs that left the plan after approval (deleted, or never placed)
     */
    public record PlanDetail(String runId, ReviewState reviewState, String invalidationReason,
                             Instant invalidatedAt, Instant appliedAt, OptimizeResponseDto recommendation,
                             String ownerName, boolean canApprove, Set<Long> removedJobIds) {}

    private String nameOf(String email) {
        return users.findByEmail(email).map(User::displayName).orElse(email);
    }

    public static WorkloadScheduleDto dto(WorkloadSchedule s, String ownerName, boolean mine) {
        return new WorkloadScheduleDto(s.getId(), s.getTitle(), s.getType(), s.getCluster(), s.getPowerKw(),
            s.getSavings(), s.getStatus(), s.getStartUtc(), s.getEndUtc(), s.getNotes(), s.getRunId(), s.getJobId(),
            ownerName, mine);
    }
}
