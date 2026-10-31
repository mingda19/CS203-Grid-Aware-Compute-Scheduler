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
import com.gacs.backend.model.OptimizerRun;
import com.gacs.backend.model.OptimizerRunStatus;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.OptimizerRunRepository;
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

@Service
public class ScheduleReviewService {
    private final ScheduleOptimizationService optimizer;
    private final OptimizationPlanRepository plans;
    private final OptimizerRunRepository runs;
    private final AuditEventService audit;
    private final AllocationSolver solver;
    private final OptimizerProperties properties;
    private final UserRepository users;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ScheduleReviewService(ScheduleOptimizationService optimizer, OptimizationPlanRepository plans,
                                 OptimizerRunRepository runs, AuditEventService audit,
                                 AllocationSolver solver, OptimizerProperties properties,
                                 UserRepository users, Clock clock) {
        this.optimizer = optimizer;
        this.plans = plans;
        this.runs = runs;
        this.audit = audit;
        this.solver = solver;
        this.properties = properties;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public OptimizeResponseDto optimize(OptimizeRequestDto request, String email) {
        User owner = operator(email);
        OptimizeResponseDto result = optimizer.optimize(request, email);
        try {
            var createdAt = clock.instant();
            OptimizerRun run = runs.save(OptimizerRun.builder()
                .id(result.runId()).createdAt(createdAt)
                .solverName(AopUtils.getTargetClass(solver).getSimpleName())
                .solverVersion(AllocationSolver.VERSION)
                .solverConfig(json.writeValueAsString(properties))
                .status(OptimizerRunStatus.valueOf(result.status()))
                .provenOptimal(result.provenOptimal()).build());
            plans.save(new OptimizationPlan(run, owner, json.writeValueAsString(result), createdAt));
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
