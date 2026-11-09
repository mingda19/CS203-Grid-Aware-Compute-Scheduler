package com.gacs.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.ScheduleDecisionDto;
import com.gacs.backend.exception.OptimizationFailedException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.OptimizerProperties;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Wraps {@link ScheduleOptimizationService#optimize} with the review/audit-trail bookkeeping
 * (GRID-134/135/140): which user requested it (401 if the authenticated email has no database
 * user), then an {@code OptimizerRun}/{@code OptimizationPlan} pair recording it for approval.
 *
 * <p>{@link #optimize} is deliberately not {@code @Transactional} itself: {@code
 * optimizer.optimize(...)} makes an external LLM call partway through (see {@code
 * ScheduleRunPersistence}'s javadoc) that must never hold a database connection. Putting {@code
 * @Transactional} here would undo that by Spring's REQUIRED propagation folding {@code
 * ScheduleRunPersistence}'s own short transactions back into one spanning the whole call. Instead,
 * {@link #recordPlan} is passed down as a callback that runs <em>inside</em> {@code
 * ScheduleRunPersistence.finalizeExplanation}'s transaction, after the LLM call has already
 * finished - so the {@code OptimizerRun}/{@code OptimizationPlan} write is atomic with the
 * explanation write (a failure in either rolls back both) without ever wrapping the HTTP call.
 * {@link #decide} has no such concern - it never calls anything external - so it stays {@code
 * @Transactional} directly.
 */
@Service
public class ScheduleReviewService {
    private final ScheduleOptimizationService optimizer;
    private final OptimizationPlanRecorder planRecorder;
    private final AuditEventService audit;
    private final AllocationSolver solver;
    private final OptimizerProperties properties;
    private final UserRepository users;
    private final Clock clock;
    private final ScheduleInputs inputs;
    private final ScheduleGate gate;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    private final com.gacs.backend.repository.WorkloadScheduleRepository schedules;

    public ScheduleReviewService(ScheduleOptimizationService optimizer,
                                 OptimizationPlanRecorder planRecorder, AuditEventService audit,
                                 AllocationSolver solver, OptimizerProperties properties,
                                 UserRepository users, Clock clock, ScheduleInputs inputs, ScheduleGate gate,
                                 com.gacs.backend.repository.WorkloadScheduleRepository schedules) {
        this.schedules = schedules;
        this.optimizer = optimizer;
        this.planRecorder = planRecorder;
        this.audit = audit;
        this.solver = solver;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
        this.inputs = inputs;
        this.gate = gate;
    }

    public OptimizeResponseDto optimize(OptimizeRequestDto request, String email) {
        User owner = operator(email);
        String provenance = inputs.capture(request, email);
        try {
            return optimizer.optimize(request, email,
                (result, snapshot) -> recordPlan(result, owner, snapshot, provenance));
        } catch (OptimizationFailedException failure) {
            try {
                planRecorder.recordFailed(failure.getRunId(), clock.instant(), AopUtils.getTargetClass(solver).getSimpleName(),
                    AllocationSolver.VERSION, json.writeValueAsString(properties), failure.getSnapshot());
            } catch (JsonProcessingException ex) { failure.addSuppressed(ex); }
            throw failure;
        }
    }

    private void recordPlan(OptimizeResponseDto result, User owner, String snapshot, String provenance) {
        try {
            var createdAt = clock.instant();
            planRecorder.record(result.runId(), createdAt, AopUtils.getTargetClass(solver).getSimpleName(),
                AllocationSolver.VERSION, json.writeValueAsString(properties), owner,
                json.writeValueAsString(result), StoredScheduleReader.jobIds(result), snapshot, provenance);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to serialize the solver config or optimizer result for run " + result.runId()
                            + " while recording its review plan", ex);
        }
    }

    /**
     * Review states a plan reaches only by a user's own decision, as opposed to STALE/INVALIDATED
     * which the system imposes automatically (see {@link OptimizationPlan#invalidate}). Bug 3: once
     * a plan holds one of these, it is terminal - every further decide() call, including repeating
     * the same action, is refused so an operator cannot flip an approval to a rejection (or back)
     * after the fact. This intentionally narrows the contract previously documented in
     * docs/api-contract.md ("repeated requests ... create additional history entries"); see REPORT.md.
     */
    private static final java.util.Set<com.gacs.backend.model.ReviewState> TERMINAL_DECISIONS = java.util.EnumSet.of(
        com.gacs.backend.model.ReviewState.APPROVED, com.gacs.backend.model.ReviewState.REJECTED,
        com.gacs.backend.model.ReviewState.REVISION_REQUESTED);

    /**
     * Removes the calendar entries this run dispatched.
     *
     * <p>Flipping the review state alone was not enough. It makes every execution gate refuse to
     * start or resume the work, but the {@code WorkloadSchedule} rows stay at {@code Scheduled} and
     * keep rendering on the timetable indefinitely - so to an operator the revocation looked like it
     * had silently failed. There is no cancelled status to move them to ({@code
     * ScheduleApplicationService} only ever writes Scheduled, Running, Throttled and Completed), and
     * the decision is to drop the rows rather than invent one: the audit log already records the
     * APPROVE, the DISPATCH and the REVOKE, so what happened stays reconstructable without keeping a
     * dead row on the calendar. Completed rows stay, since they record work that already ran and
     * a revocation cannot undo it.
     *
     * <p>Runs in the same transaction as the decision, so a failure here rolls the revocation back
     * rather than leaving a revoked plan whose work is still on the timetable.
     */
    private void discardCalendarEntries(UUID runId) {
        schedules.deleteByRunIdAndStatusNot(runId.toString(), "Completed");
    }

    /**
     * The one move still open on a run that already holds a decision: withdrawing an approval.
     *
     * <p>A first decision is terminal - that is what stops a plan being approved twice, or approved
     * and then rejected as though both were open choices. But an approval that has already dispatched
     * work has to stay reversible, or there is no way to halt a running schedule at all, which in a
     * product whose whole premise is human control would be the more serious defect. So exactly one
     * transition survives: {@code APPROVED -> REVOKE}. A rejected or revision-requested run is final,
     * since nothing was dispatched from it and there is nothing to halt.
     */
    private static boolean isRevocationOfApproval(com.gacs.backend.model.ReviewState state,
                                                  AuditActionType action) {
        return state == com.gacs.backend.model.ReviewState.APPROVED && action == AuditActionType.REVOKE;
    }

    @Transactional(noRollbackFor = com.gacs.backend.exception.ScheduleExportRejectedException.class)
    public ScheduleDecisionDto decide(UUID runId, AuditActionType action, String comment, String email) {
        OptimizationPlan plan = gate.owned(runId, email);
        User operator = plan.getOwner();
        if (TERMINAL_DECISIONS.contains(plan.getReviewState())
            && !isRevocationOfApproval(plan.getReviewState(), action))
            throw new com.gacs.backend.exception.ScheduleExportRejectedException(
                com.gacs.backend.exception.ScheduleExportRejectedException.Reason.ALREADY_DECIDED,
                "Schedule run " + runId + " is already " + plan.getReviewState().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + " and cannot be decided again"
                    + (plan.getReviewState() == com.gacs.backend.model.ReviewState.APPROVED
                        ? ". Revoke the approval instead to halt it." : ""));
        if (action == AuditActionType.REVOKE
            && plan.getReviewState() != com.gacs.backend.model.ReviewState.APPROVED)
            throw new com.gacs.backend.exception.ScheduleExportRejectedException(
                com.gacs.backend.exception.ScheduleExportRejectedException.Reason.ALREADY_DECIDED,
                "Schedule run " + runId + " is " + plan.getReviewState().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ')
                    + ", not approved, so there is no approval to revoke");
        if (action == AuditActionType.APPROVE || plan.getReviewState() == com.gacs.backend.model.ReviewState.INVALIDATED
            || plan.getReviewState() == com.gacs.backend.model.ReviewState.STALE) gate.check(plan, email, false);
        plan.recordDecision(action);
        AuditEventResponseDto event = audit.recordEvent(new AuditEventRequestDto(action,
            runId.toString(), ScheduleHashUtil.computeSha256(plan.getResultJson()), comment));
        if (action == AuditActionType.APPROVE) gate.supersede(plan);
        if (action == AuditActionType.REVOKE) discardCalendarEntries(runId);
        return new ScheduleDecisionDto(event.id(), runId, event.actionType(), operator.getId(),
            event.operatorRationale(), event.timestamp(), plan.getReviewState());
    }

    private User operator(String email) {
        return AuthenticatedOperators.resolve(users, email);
    }
}
