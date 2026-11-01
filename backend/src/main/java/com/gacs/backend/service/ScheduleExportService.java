package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.exception.ScheduleExportRejectedException.Reason;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.AuditEvent;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AuditEventRepository;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.gacs.backend.service.ApprovedScheduleExport.utc;

/**
 * Exports approved schedules for execution adapters, behind the approval gate (docs/api-contract.md section 6.6,
 * GRID-135 AC3). Checks run in this order and the first failure wins:
 * <ol>
 *   <li>the plan exists and belongs to the caller, else 404 (the same as for decisions);</li>
 *   <li>its review state is APPROVED, else {@link Reason#NOT_APPROVED};</li>
 *   <li>its latest decision in the audit log is an approval whose schedule hash matches the stored plan,
 *       else {@link Reason#INVALIDATED};</li>
 *   <li>at least one of its slots has not ended, and no newer approved plan of the caller schedules any of its
 *       jobs, else {@link Reason#STALE};</li>
 *   <li>every job it schedules is still in the caller's jobs and the plan still fits it: the same CPU and GPU
 *       hours, a start inside the job's current start window and an end by its current deadline, using the
 *       optimiser's own rules; else {@link Reason#INVALIDATED}.</li>
 * </ol>
 */
@Service
public class ScheduleExportService {

    public enum Format {
        CSV("csv", new MediaType("text", "csv", StandardCharsets.UTF_8)),
        JSON("json", MediaType.APPLICATION_JSON);

        private final String extension;
        private final MediaType contentType;

        Format(String extension, MediaType contentType) {
            this.extension = extension;
            this.contentType = contentType;
        }

        public String extension() {
            return extension;
        }

        public MediaType contentType() {
            return contentType;
        }
    }

    /** A file ready to send: its download name, content type and UTF-8 bytes. */
    public record ExportFile(String filename, MediaType contentType, byte[] content) {}

    private static final Set<AuditActionType> DECISIONS =
            EnumSet.of(AuditActionType.APPROVE, AuditActionType.REJECT, AuditActionType.REVISE);
    private static final double HOURS_TOLERANCE = 1e-9;

    private final OptimizationPlanRepository plans;
    private final AuditEventRepository auditEvents;
    private final JobRepository jobs;
    private final UserRepository users;
    private final ApprovedScheduleCsvSerializer csv;
    private final ApprovedScheduleJsonSerializer json;
    private final StoredScheduleReader reader;
    private final Clock clock;

    public ScheduleExportService(OptimizationPlanRepository plans, AuditEventRepository auditEvents,
                                 JobRepository jobs, UserRepository users, ApprovedScheduleCsvSerializer csv,
                                 ApprovedScheduleJsonSerializer json, StoredScheduleReader reader, Clock clock) {
        this.plans = plans;
        this.auditEvents = auditEvents;
        this.jobs = jobs;
        this.users = users;
        this.csv = csv;
        this.json = json;
        this.reader = reader;
        this.clock = clock;
    }

    /**
     * @return the approved schedule as a {@code schedule-run-<runId>.<ext>} file
     * @throws BadCredentialsException          if the authenticated email has no database user (401)
     * @throws ResourceNotFoundException        if the plan is missing or owned by someone else (404)
     * @throws ScheduleExportRejectedException  if the gate refuses the export (403 or 409)
     */
    @Transactional(readOnly = true)
    public ExportFile export(UUID runId, Format format, String email) {
        User owner = AuthenticatedOperators.resolve(users, email);
        OptimizationPlan plan = plans.findOwned(runId.toString(), owner.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));

        requireApproved(plan);
        AuditEvent approval = requireMatchingApproval(plan);
        OptimizeResponseDto schedule = reader.read(plan.getResultJson())
                .orElseThrow(() -> invalidated(plan.getRunId(), "its stored schedule cannot be read"));
        requireCurrent(plan, schedule, owner);
        requireJobsUnchanged(plan.getRunId(), schedule, owner);

        String body = format == Format.CSV
                ? csv.serialize(schedule)
                : json.serialize(schedule, plan.getRun(), approval);
        return new ExportFile("schedule-run-" + runId + "." + format.extension(), format.contentType(),
                body.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireApproved(OptimizationPlan plan) {
        if (plan.getReviewState() != ReviewState.APPROVED) {
            throw new ScheduleExportRejectedException(Reason.NOT_APPROVED, "Schedule run " + plan.getRunId()
                    + " is " + plan.getReviewState() + ", not APPROVED, so it cannot be exported");
        }
    }

    /**
     * The audit log is the record of approval: its latest decision must approve exactly the stored plan.
     *
     * @return that approval event
     */
    private AuditEvent requireMatchingApproval(OptimizationPlan plan) {
        String runId = plan.getRunId();
        AuditEvent latest = auditEvents.findFirstByRunIdAndActionTypeInOrderByIdDesc(runId, DECISIONS).orElse(null);
        if (latest == null || latest.getActionType() != AuditActionType.APPROVE) {
            throw invalidated(runId, "its review state is APPROVED but its latest recorded decision is "
                    + (latest == null ? "missing" : latest.getActionType()));
        }
        if (!ScheduleHashUtil.computeSha256(plan.getResultJson()).equals(latest.getScheduleHash())) {
            throw invalidated(runId, "the stored schedule no longer matches the hash recorded when it was approved");
        }
        return latest;
    }

    private void requireCurrent(OptimizationPlan plan, OptimizeResponseDto schedule, User owner) {
        String runId = plan.getRunId();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);
        Optional<LocalDateTime> lastEnd = items(schedule).stream()
                .map(ScheduleItem::scheduledEnd)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder());
        if (lastEnd.isPresent() && !now.isBefore(lastEnd.get())) {
            throw stale(runId, "all of its slots ended by " + utc(lastEnd.get()));
        }

        Set<Long> jobIds = StoredScheduleReader.jobIds(schedule);
        for (OptimizationPlan newer : plans.findOwnedInStateCreatedAfter(owner.getId(), ReviewState.APPROVED,
                plan.getCreatedAt())) {
            OptimizeResponseDto newerSchedule = reader.read(newer.getResultJson()).orElseThrow(() ->
                    new IllegalStateException("Stored schedule for run " + newer.getRunId() + " cannot be read"));
            Set<Long> shared = new TreeSet<>(StoredScheduleReader.jobIds(newerSchedule));
            shared.retainAll(jobIds);
            if (!shared.isEmpty()) {
                throw stale(runId, "run " + newer.getRunId() + " was approved later and also schedules job(s) "
                        + shared);
            }
        }
    }

    /** Applies the optimiser's per-job rules (contract section 6.4) to the jobs as they are now. */
    private void requireJobsUnchanged(String runId, OptimizeResponseDto schedule, User owner) {
        List<ScheduleItem> scheduled = items(schedule).stream()
                .filter(item -> item.scheduledStart() != null && item.scheduledEnd() != null)
                .sorted(Comparator.comparing(ScheduleItem::jobId))
                .toList();
        if (scheduled.isEmpty()) {
            return;
        }
        Map<Long, Job> current = jobs.findByOwnerEmailAndIdIn(owner.getEmail(),
                        scheduled.stream().map(ScheduleItem::jobId).toList()).stream()
                .collect(Collectors.toMap(Job::getId, Function.identity()));

        for (ScheduleItem item : scheduled) {
            Long id = item.jobId();
            Job job = current.get(id);
            if (job == null) {
                throw invalidated(runId, "job " + id + " is no longer in your jobs");
            }
            if (!sameHours(job.getCpuHours(), item.allocatedCpuHours())
                    || !sameHours(job.getGpuHours(), item.allocatedGpuHours())) {
                throw invalidated(runId, "job " + id + "'s CPU or GPU hours changed after the schedule was generated");
            }
            LocalDateTime earliest = job.earliestAllowedStart();
            LocalDateTime latest = job.latestAllowedStart();
            if (item.scheduledStart().isBefore(earliest) || item.scheduledStart().isAfter(latest)) {
                throw invalidated(runId, "job " + id + " must now start between " + utc(earliest) + " and "
                        + utc(latest) + ", but the schedule starts it at " + utc(item.scheduledStart()));
            }
            // The optimiser only uses slots that end by the deadline's hour.
            if (item.scheduledEnd().isAfter(job.getDeadline().truncatedTo(ChronoUnit.HOURS))) {
                throw invalidated(runId, "job " + id + "'s deadline " + utc(job.getDeadline())
                        + " is now before its scheduled end " + utc(item.scheduledEnd()));
            }
        }
    }

    private static List<ScheduleItem> items(OptimizeResponseDto schedule) {
        return schedule.items() == null ? List.of() : schedule.items();
    }

    private static boolean sameHours(Double current, double planned) {
        double value = current == null ? 0.0 : current;
        return Math.abs(value - planned) <= HOURS_TOLERANCE * Math.max(1.0, Math.abs(planned));
    }

    private static ScheduleExportRejectedException stale(String runId, String why) {
        return new ScheduleExportRejectedException(Reason.STALE, "Schedule run " + runId + " is stale: " + why);
    }

    private static ScheduleExportRejectedException invalidated(String runId, String why) {
        return new ScheduleExportRejectedException(Reason.INVALIDATED,
                "Schedule run " + runId + " is invalidated: " + why);
    }
}
