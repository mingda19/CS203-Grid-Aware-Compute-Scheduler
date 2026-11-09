package com.gacs.backend.service;

import com.gacs.backend.model.ConfirmationOutcome;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.WorkloadSchedule;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizationPlanRepository.PlanLink;
import com.gacs.backend.repository.WorkloadScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Where each job stands, worked out from its plans and calendar rows every time it is asked, so the
 * answer cannot drift from them. {@code Job.status} only matters for CANCELLED.
 *
 * <p>The autorun plans exactly the jobs this reports as {@link Status#SCHEDULING}, so what the page shows
 * and what the poller does cannot disagree.
 */
@Service
public class JobStatusResolver {

    public enum Status { SCHEDULING, AWAITING_APPROVAL, SCHEDULED, RUNNING, COMPLETED, REJECTED, NEEDS_ATTENTION, CANCELLED }

    /** Why a job needs attention. Recompute retries only {@link #FAILED}. */
    public enum Cause { NONE, FAILED, UNCONFIRMED, DEADLINE_PASSED }

    /**
     * @param reason     shown in the job's detail dialog
     * @param runId      the plan the status comes from, if any
     * @param approved   true while the job is in an approved plan, or finished under one
     */
    public record Derived(Status status, String reason, String runId, boolean approved, Cause cause) {
        static Derived of(Status status) { return new Derived(status, null, null, false, Cause.NONE); }
    }

    private final OptimizationPlanRepository plans;
    private final WorkloadScheduleRepository schedules;
    private final ScheduleInputs inputs;
    private final Clock clock;

    public JobStatusResolver(OptimizationPlanRepository plans, WorkloadScheduleRepository schedules,
                             ScheduleInputs inputs, Clock clock) {
        this.plans = plans;
        this.schedules = schedules;
        this.inputs = inputs;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public Map<Long, Derived> resolve(Collection<Job> jobs) {
        Map<Long, Derived> result = new HashMap<>();
        if (jobs.isEmpty()) return result;
        List<Long> ids = jobs.stream().map(Job::getId).toList();
        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC);

        Map<Long, List<PlanLink>> links = plans.findLinks(ids).stream().collect(Collectors.groupingBy(PlanLink::jobId));
        Set<String> removed = new HashSet<>();
        for (Object[] row : plans.findRemoved(ids)) removed.add(row[0] + "@" + row[1]);
        Map<Long, List<WorkloadSchedule>> rows = schedules.findByJobIdIn(ids).stream()
            .filter(row -> row.getJobId() != null).collect(Collectors.groupingBy(WorkloadSchedule::getJobId));

        for (Job job : jobs) {
            List<PlanLink> mine = links.getOrDefault(job.getId(), List.of()).stream()
                .sorted(Comparator.comparing(PlanLink::createdAt, Comparator.reverseOrder())).toList();
            result.put(job.getId(), derive(job, mine, removed, rows.getOrDefault(job.getId(), List.of()), now));
        }
        return result;
    }

    private Derived derive(Job job, List<PlanLink> mine, Set<String> removed, List<WorkloadSchedule> rows,
                           LocalDateTime now) {
        if ("CANCELLED".equals(job.getStatus())) return Derived.of(Status.CANCELLED);

        PlanLink finished = finishedUnder(mine, rows, now);
        if (finished != null)
            return new Derived(Status.COMPLETED, null, finished.runId(), true, Cause.NONE);

        for (PlanLink link : mine) {
            boolean left = removed.contains(job.getId() + "@" + link.runId());
            if (link.state() == ReviewState.APPROVED && !left) {
                boolean running = rows.stream().anyMatch(row -> link.runId().equals(row.getRunId())
                    && ("Running".equals(row.getStatus()) || "Throttled".equals(row.getStatus())));
                return new Derived(running ? Status.RUNNING : Status.SCHEDULED, null, link.runId(), true, Cause.NONE);
            }
        }
        for (PlanLink link : mine) {
            if (link.state() != ReviewState.PENDING) continue;
            if (job.hasCurrentPlanError())
                return new Derived(Status.NEEDS_ATTENTION, job.getPlanError(), link.runId(), false, Cause.FAILED);
            return new Derived(Status.AWAITING_APPROVAL, null, link.runId(), false, Cause.NONE);
        }

        ConfirmationOutcome outcome = job.getConfirmationOutcome();
        if (outcome != null && outcome != ConfirmationOutcome.CONFIRMED)
            return new Derived(Status.NEEDS_ATTENTION, "The job's constraints are not confirmed yet.", null, false,
                Cause.UNCONFIRMED);
        if (!job.getDeadline().isAfter(now))
            return new Derived(Status.NEEDS_ATTENTION, "The deadline has passed.", null, false, Cause.DEADLINE_PASSED);
        if (job.hasCurrentPlanError())
            return new Derived(Status.NEEDS_ATTENTION, job.getPlanError(), null, false, Cause.FAILED);

        // Only the latest plan counts, and only if it was decided on the job as it is now. An older rejection
        // would leave the job stuck after a later plan was replaced.
        if (!mine.isEmpty()) {
            PlanLink latest = mine.get(0);
            if ((latest.state() == ReviewState.REJECTED || latest.state() == ReviewState.REVISION_REQUESTED)
                && plans.findInputSnapshot(latest.runId())
                    .map(snapshot -> inputs.revisionIn(snapshot, job.getId())).filter(r -> r == job.getSchedulingRevision())
                    .isPresent())
                return new Derived(Status.REJECTED, latest.state() == ReviewState.REJECTED
                    ? "The plan was rejected or its approval was revoked. Edit the job or recompute to plan it again."
                    : "Changes were requested on the plan. Edit the job or recompute to plan it again.",
                    latest.runId(), false, Cause.NONE);
        }
        return Derived.of(Status.SCHEDULING);
    }

    /**
     * The plan a job finished under: it has calendar rows, every one ended (or marked Completed), and the
     * plan was still approved when they ended. A plan the gate marked stale because its slots ended counts,
     * and so does one that lost its approval after the last slot ended.
     */
    private PlanLink finishedUnder(List<PlanLink> mine, List<WorkloadSchedule> rows, LocalDateTime now) {
        if (rows.isEmpty()) return null;
        if (rows.stream().allMatch(row -> "Completed".equals(row.getStatus())))
            return mine.stream().filter(l -> l.runId().equals(rows.get(0).getRunId())).findFirst()
                .orElse(new PlanLink(rows.get(0).getJobId(), rows.get(0).getRunId(), ReviewState.APPROVED, null, null));
        if (!rows.stream().allMatch(row -> !row.getEndUtc().isAfter(now))) return null;
        LocalDateTime lastEnd = rows.stream().map(WorkloadSchedule::getEndUtc).max(Comparator.naturalOrder()).orElseThrow();
        String runId = rows.get(0).getRunId();
        for (PlanLink link : mine) {
            if (!link.runId().equals(runId)) continue;
            if (link.state() == ReviewState.APPROVED) return link;
            boolean lost = link.state() == ReviewState.STALE || link.state() == ReviewState.INVALIDATED;
            if (lost && link.invalidatedAt() != null
                && !link.invalidatedAt().isBefore(lastEnd.toInstant(ZoneOffset.UTC))) return link;
        }
        return null;
    }
}
