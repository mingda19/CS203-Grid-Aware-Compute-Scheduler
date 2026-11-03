package com.gacs.backend.service;

import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.repository.OptimizationPlanRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/**
 * Records job IDs for plans stored before {@code optimization_plan_jobs} existed, so the audit job filter finds
 * their decisions too (GRID-134 AC6.1a). Runs at startup; plans that already have job IDs are left alone.
 */
@Component
public class OptimizationPlanJobBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(OptimizationPlanJobBackfill.class);

    private final OptimizationPlanRepository plans;
    private final StoredScheduleReader reader;

    public OptimizationPlanJobBackfill(OptimizationPlanRepository plans, StoredScheduleReader reader) {
        this.plans = plans;
        this.reader = reader;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        backfill();
    }

    /** @return how many plans got job IDs */
    @Transactional
    public int backfill() {
        int filled = 0;
        for (OptimizationPlan plan : plans.findWithoutJobIds()) {
            Set<Long> jobIds = reader.read(plan.getResultJson()).map(StoredScheduleReader::jobIds).orElse(Set.of());
            if (!jobIds.isEmpty()) {
                plan.backfillJobIds(jobIds);
                filled++;
            }
        }
        if (filled > 0) {
            log.info("Recorded job IDs for {} stored optimization plan(s)", filled);
        }
        return filled;
    }
}
