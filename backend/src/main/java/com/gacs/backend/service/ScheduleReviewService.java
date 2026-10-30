package com.gacs.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.*;
import com.gacs.backend.exception.ResourceNotFoundException;
import com.gacs.backend.model.*;
import com.gacs.backend.repository.*;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.util.UUID;

@Service
public class ScheduleReviewService {
    private final ScheduleOptimizationService optimizer;
    private final OptimizationPlanRepository plans;
    private final ScheduleDecisionRepository decisions;
    private final UserRepository users;
    private final Clock clock;
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    public ScheduleReviewService(ScheduleOptimizationService optimizer, OptimizationPlanRepository plans,
                                 ScheduleDecisionRepository decisions, UserRepository users, Clock clock) {
        this.optimizer = optimizer;
        this.plans = plans;
        this.decisions = decisions;
        this.users = users;
        this.clock = clock;
    }

    @Transactional
    public OptimizeResponseDto optimize(OptimizeRequestDto request, String email) {
        User owner = operator(email);
        OptimizeResponseDto result = optimizer.optimize(request);
        try {
            plans.save(new OptimizationPlan(UUID.fromString(result.runId()), owner,
                json.writeValueAsString(result), clock.instant()));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Unable to store optimization result", ex);
        }
        return result;
    }

    @Transactional
    public ScheduleDecisionDto decide(UUID runId, DecisionAction action, String comment, String email) {
        User operator = operator(email);
        OptimizationPlan plan = plans.findOwnedForUpdate(runId, operator.getId())
            .orElseThrow(() -> new ResourceNotFoundException("Optimization plan not found"));
        if (comment != null && comment.length() > 2000) {
            throw new IllegalArgumentException("comment must be at most 2000 characters");
        }
        ScheduleDecision decision = decisions.save(new ScheduleDecision(plan, operator, action,
            comment == null ? null : comment.trim(), clock.instant()));
        plan.recordDecision(action);
        return new ScheduleDecisionDto(decision.getId(), runId, action, operator.getId(),
            decision.getComment(), decision.getDecidedAt(), plan.getReviewState());
    }

    private User operator(String email) {
        return users.findByEmail(email)
            .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
    }
}
