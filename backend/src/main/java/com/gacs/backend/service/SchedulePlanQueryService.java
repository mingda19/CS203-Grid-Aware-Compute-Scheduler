package com.gacs.backend.service;

import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;

@Service
public class SchedulePlanQueryService {
    private final OptimizationPlanRepository plans;
    private final UserRepository users;

    public SchedulePlanQueryService(OptimizationPlanRepository plans, UserRepository users) {
        this.plans = plans;
        this.users = users;
    }

    @Transactional(readOnly = true)
    public Page<PlanSummary> list(String email, int page, int size) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be nonnegative and size must be between 1 and 100");
        var owner = AuthenticatedOperators.resolve(users, email);
        return plans.findByOwnerIdOrderByCreatedAtDescRunIdDesc(owner.getId(), PageRequest.of(page, size))
            .map(plan -> new PlanSummary(plan.getRunId(), plan.getCreatedAt(), plan.getReviewState()));
    }

    public record PlanSummary(String runId, Instant createdAt, ReviewState reviewState) {}
}
