package com.gacs.backend.service;

import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.OptimizationPlanRepository;
import com.gacs.backend.repository.UserRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

@Service
public class SchedulePlanQueryService {
    private final OptimizationPlanRepository plans;
    private final UserRepository users;
    private final AppSettingsService settings;
    private final StoredScheduleReader reader;

    public SchedulePlanQueryService(OptimizationPlanRepository plans, UserRepository users, AppSettingsService settings,
                                    StoredScheduleReader reader) {
        this.plans = plans;
        this.users = users;
        this.settings = settings;
        this.reader = reader;
    }

    /**
     * The review queue: every owner's plans, newest first. {@code approvableOnly} keeps the plans the caller
     * may approve, which leaves out their own unless self-approval is allowed or they are an admin.
     */
    @Transactional
    public Page<PlanSummary> list(String email, int page, int size, ReviewState state, boolean approvableOnly) {
        if (page < 0 || size < 1 || size > 100)
            throw new IllegalArgumentException("page must be nonnegative and size must be between 1 and 100");
        var caller = AuthenticatedOperators.resolve(users, email);
        boolean allowSelf = settings.get().isAllowSelfApproval();
        var paging = PageRequest.of(page, size);
        boolean excludeOwn = approvableOnly && !AppSettingsService.canApprove(caller, caller.getId(), allowSelf);
        Page<OptimizationPlan> found = excludeOwn
            ? (state == null
                ? plans.findByOwnerIdNotOrderByCreatedAtDescRunIdDesc(caller.getId(), paging)
                : plans.findByOwnerIdNotAndReviewStateOrderByCreatedAtDescRunIdDesc(caller.getId(), state, paging))
            : (state == null
                ? plans.findAllByOrderByCreatedAtDescRunIdDesc(paging)
                : plans.findByReviewStateOrderByCreatedAtDescRunIdDesc(state, paging));
        return found.map(plan -> summary(plan, caller.getId(),
            AppSettingsService.canApprove(caller, plan.getOwner().getId(), allowSelf)));
    }

    /** What a list row shows: the plan's jobs and savings, read from the stored result. Removed jobs are left out. */
    private PlanSummary summary(OptimizationPlan plan, Long callerId, boolean canApprove) {
        var schedule = reader.read(plan.getResultJson()).orElse(null);
        var items = schedule == null || schedule.items() == null ? List.<OptimizeResponseDto.ScheduleItem>of()
            : schedule.items().stream().filter(item -> !plan.getRemovedJobIds().contains(item.jobId())).toList();
        Instant firstStart = items.stream().map(OptimizeResponseDto.ScheduleItem::scheduledStart).filter(Objects::nonNull)
            .min(Comparator.naturalOrder()).map(start -> start.toInstant(ZoneOffset.UTC)).orElse(null);
        return new PlanSummary(plan.getRunId(), plan.getCreatedAt(), plan.getReviewState(), plan.getOwner().displayName(),
            canApprove, plan.getOwner().getId().equals(callerId),
            items.stream().map(OptimizeResponseDto.ScheduleItem::jobName).toList(),
            schedule == null ? null : schedule.projectedSavingsUsd(),
            schedule == null ? null : schedule.projectedSavingsPercent(),
            firstStart, schedule != null && schedule.usedSeededFallbackPrices());
    }

    /** {@code mine}: the caller submitted it. {@code jobNames}, {@code firstStart}: without removed jobs. Savings: null when unreadable. */
    public record PlanSummary(String runId, Instant createdAt, ReviewState reviewState, String ownerName,
                              boolean canApprove, boolean mine, List<String> jobNames,
                              Double projectedSavingsUsd, Double projectedSavingsPercent,
                              Instant firstStart, boolean usedSeededFallbackPrices) {}
}
