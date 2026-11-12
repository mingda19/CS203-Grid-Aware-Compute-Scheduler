package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeRequestDto.ClusterCapacityInput;
import com.gacs.backend.dto.OptimizeRequestDto.JobInput;
import com.gacs.backend.exception.OptimizationFailedException;
import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.exception.UnprocessableDataException;
import com.gacs.backend.model.Job;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.JobStatusResolver.Cause;
import com.gacs.backend.service.JobStatusResolver.Status;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Plans the jobs nobody has planned yet. The autorun poller calls {@link #plan(String, boolean)} for each
 * owner, and Recompute calls it for the signed-in user with {@code retry} set.
 *
 * <p>A job is planned when {@link JobStatusResolver} reports it {@link Status#SCHEDULING}, so the poller
 * skips exactly what the Jobs page shows as waiting on someone: a job in a waiting or approved plan, a
 * finished, cancelled or expired job, one with unconfirmed constraints, one whose latest plan was rejected,
 * and one that already failed at its current revision. With {@code retry}, rejected jobs and failed jobs
 * are planned again. A job in an approved plan never is.
 *
 * <p>No transaction spans a solve, which can call an LLM (see {@link ScheduleReviewService}).
 */
@Service
public class JobPlanningService {

    private static final Logger log = LoggerFactory.getLogger(JobPlanningService.class);

    /** The created plans and the jobs this attempt could not plan. */
    public record Result(List<String> runIds, int failed, List<Long> failedJobIds) {}

    private final UserRepository users;
    private final JobRepository jobs;
    private final OptimizationPlanRepository plans;
    private final JobStatusResolver statuses;
    private final FacilityService facilities;
    private final ScheduleReviewService review;
    private final ScheduleGate gate;
    private final TransactionTemplate transactions;

    public JobPlanningService(UserRepository users, JobRepository jobs, OptimizationPlanRepository plans,
                              JobStatusResolver statuses, FacilityService facilities, ScheduleReviewService review,
                              ScheduleGate gate, PlatformTransactionManager manager) {
        this.users = users;
        this.jobs = jobs;
        this.plans = plans;
        this.statuses = statuses;
        this.facilities = facilities;
        this.review = review;
        this.gate = gate;
        this.transactions = new TransactionTemplate(manager);
    }

    /** One pass over every owner. A failure for one owner is logged and does not stop the others. */
    public void tick() {
        for (User user : users.findAll()) {
            try { plan(user.getEmail(), false); }
            catch (RuntimeException ex) { log.warn("Autorun could not plan jobs for user {}: {}", user.getId(), ex.toString()); }
        }
    }

    public Result plan(String email, boolean retry) {
        List<Job> candidates = transactions.execute(status -> candidates(email, retry));
        Result result = new Result(new ArrayList<>(), 0, List.of());
        if (candidates == null || candidates.isEmpty()) return result;
        var facility = facilities.getOrCreateFacility(email);
        var cluster = new ClusterCapacityInput(facility.getMaxCpuHours(), facility.getMaxGpuHours());
        List<Long> failedJobIds = new ArrayList<>();
        solve(email, candidates, cluster, facility.getLocation(), result.runIds(), failedJobIds);
        return new Result(result.runIds(), failedJobIds.size(), List.copyOf(failedJobIds));
    }

    /**
     * The owner's jobs to plan. Waiting plans are checked first, so a plan an edit or a facility change has
     * just made out of date no longer counts as live.
     */
    private List<Job> candidates(String email, boolean retry) {
        User owner = gate.lock(email);
        for (var plan : plans.findByOwnerIdAndReviewState(owner.getId(), ReviewState.PENDING)) {
            try { gate.check(plan, false); }
            catch (ScheduleExportRejectedException ignored) { /* The refusal is stored on the plan. */ }
        }
        List<Job> all = jobs.findByOwnerEmailOrderByOriginalStartTimeAsc(email);
        var derived = statuses.resolve(all);
        return all.stream().filter(job -> {
            var state = derived.get(job.getId());
            return state.status() == Status.SCHEDULING
                || (retry && (state.status() == Status.REJECTED
                    || (state.status() == Status.NEEDS_ATTENTION && state.cause() == Cause.FAILED
                        && state.runId() == null)));
        }).toList();
    }

    /**
     * Plans {@code batch} together. A 400, a 422 or a solver crash (503) means some job cannot be planned, and
     * the request does not say which, so independent groups are retried separately. Related jobs stay
     * together: splitting them would discard their saved relationships. A crash left uncaught
     * would be retried every tick with no reason on the job. Any other failure, such as a missing
     * forecast or a database error, records nothing on the jobs and propagates: the next tick tries again.
     */
    private void solve(String email, List<Job> batch, ClusterCapacityInput cluster, String location,
                       List<String> runIds, List<Long> failedJobIds) {
        try {
            var response = review.optimize(request(batch, cluster, location), email, true);
            // A run that placed nothing was ended as it was recorded, and its jobs got a reason.
            if (OptimizeResponseDto.STATUS_INFEASIBLE.equals(response.status()))
                batch.forEach(job -> failedJobIds.add(job.getId()));
            else runIds.add(response.runId());
        } catch (IllegalArgumentException | UnprocessableDataException | OptimizationFailedException ex) {
            var groups = constraintGroups(batch);
            if (groups.size() > 1) {
                for (var group : groups) solve(email, group, cluster, location, runIds, failedJobIds);
            } else {
                String reason = ex instanceof OptimizationFailedException crash
                    ? "The optimiser failed on this job (run " + crash.getRunId() + "). Edit the job or use Recompute to try again."
                    : ex.getMessage();
                for (Job job : batch) recordFailure(job, reason);
                batch.forEach(job -> failedJobIds.add(job.getId()));
            }
        }
    }

    /** Connected components, treating every saved pair rule as an undirected edge for batching. */
    static List<List<Job>> constraintGroups(List<Job> batch) {
        var byId = new java.util.LinkedHashMap<Long, Job>();
        batch.forEach(job -> byId.put(job.getId(), job));
        var neighbours = new java.util.HashMap<Long, java.util.Set<Long>>();
        for (Job job : batch) for (var rule : job.getPairConstraints()) {
            if (!byId.containsKey(rule.referencedJobId())) continue;
            neighbours.computeIfAbsent(job.getId(), key -> new java.util.HashSet<>()).add(rule.referencedJobId());
            neighbours.computeIfAbsent(rule.referencedJobId(), key -> new java.util.HashSet<>()).add(job.getId());
        }
        var visited = new java.util.HashSet<Long>();
        List<List<Job>> groups = new ArrayList<>();
        for (Job job : batch) {
            if (!visited.add(job.getId())) continue;
            var pending = new java.util.ArrayDeque<Long>();
            pending.add(job.getId());
            List<Job> group = new ArrayList<>();
            while (!pending.isEmpty()) {
                long id = pending.remove();
                group.add(byId.get(id));
                for (long next : neighbours.getOrDefault(id, java.util.Set.of()))
                    if (visited.add(next)) pending.add(next);
            }
            groups.add(group);
        }
        return groups;
    }

    private void recordFailure(Job planned, String message) {
        transactions.executeWithoutResult(status -> jobs.findById(planned.getId()).ifPresent(job -> {
            // Only for the job as it was planned. An edit in between has made the reason out of date.
            if (job.getSchedulingRevision() == planned.getSchedulingRevision())
                job.recordPlanError(message, planned.getSchedulingRevision());
        }));
    }

    private static OptimizeRequestDto request(List<Job> batch, ClusterCapacityInput cluster, String location) {
        List<JobInput> inputs = batch.stream().map(job -> new JobInput(job.getId(), job.getName(), job.getCpuHours(),
            job.getGpuHours(), job.earliestAllowedStart(), job.getDeadline(), job.getFlexibilityWindowHours(),
            job.isExempt(), job.getPriority().name())).toList();
        return new OptimizeRequestDto(inputs, cluster, location, null, null, null, true);
    }
}
