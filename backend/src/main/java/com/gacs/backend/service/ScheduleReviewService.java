package com.gacs.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.AuditEventRequestDto;
import com.gacs.backend.dto.AuditEventResponseDto;
import com.gacs.backend.dto.OptimizeRequestDto;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.ScheduleDecisionDto;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.AuditActionType;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.service.allocation.AllocationSolver;
import com.gacs.backend.service.allocation.OptimizerProperties;
import com.gacs.backend.util.ScheduleHashUtil;
import org.springframework.aop.support.AopUtils;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

/**
 * Wraps {@link ScheduleOptimizationService#optimize} with the review/audit-trail bookkeeping
 * (GRID-134/135/140): which user requested it (401 if the authenticated email has no database
 * user), then an {@code OptimizerRun}/{@code OptimizationPlan} pair recording it for approval.
 *
 * <p>{@link #optimize} is deliberately not {@code @Transactional}: {@code optimizer.optimize(...)}
 * makes an external LLM call partway through (see {@code ScheduleRunPersistence}'s javadoc) that
 * must never hold a database connection. Putting {@code @Transactional} here would undo that by
 * Spring's REQUIRED propagation folding {@code ScheduleRunPersistence}'s own short transactions
 * back into one spanning the whole call. {@link #decide} has no such concern - it never calls
 * anything external - so it stays {@code @Transactional} directly.
 */
@Service
public class ScheduleReviewService {
    private final ScheduleOptimizationService optimizer;
    private final OptimizationPlanRepository plans;
    private final OptimizationPlanRecorder planRecorder;
    private final AuditEventService audit;
    private final AllocationSolver solver;
    private final OptimizerProperties properties;
    private final UserRepository users;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ScheduleReviewService(ScheduleOptimizationService optimizer, OptimizationPlanRepository plans,
                                 OptimizationPlanRecorder planRecorder, AuditEventService audit,
                                 AllocationSolver solver, OptimizerProperties properties,
                                 UserRepository users, Clock clock) {
        this.optimizer = optimizer;
        this.plans = plans;
        this.planRecorder = planRecorder;
        this.audit = audit;
        this.solver = solver;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
    }

    public OptimizeResponseDto optimize(OptimizeRequestDto request, String email) {
        User owner = operator(email);
        OptimizeResponseDto result = optimizer.optimize(request, email);
        try {
            var createdAt = clock.instant();
            planRecorder.record(result.runId(), createdAt, AopUtils.getTargetClass(solver).getSimpleName(),
                AllocationSolver.VERSION, json.writeValueAsString(properties), owner,
                json.writeValueAsString(result), StoredScheduleReader.jobIds(result));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store optimization result", ex);
        }
        return result;
    }

    @Transactional
    public ScheduleDecisionDto decide(UUID runId, AuditActionType action, String comment, String email) {
        User operator = operator(email);
        OptimizationPlan plan = plans.findOwnedForUpdate(runId.toString(), operator.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));
        plan.recordDecision(action);
        AuditEventResponseDto event = audit.recordEvent(new AuditEventRequestDto(action,
            runId.toString(), ScheduleHashUtil.computeSha256(plan.getResultJson()), comment));
        return new ScheduleDecisionDto(event.id(), runId, event.actionType(), operator.getId(),
            event.operatorRationale(), event.timestamp(), plan.getReviewState());
    }

    private User operator(String email) {
        return users.findByEmail(email)
            .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
    }
}
