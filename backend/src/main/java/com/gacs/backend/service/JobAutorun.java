package com.gacs.backend.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Plans every owner's waiting jobs on a timer. {@code gacs.schedule.autorun-enabled=false} turns it off,
 * and Recompute still works.
 *
 * <p>The first tick after it is turned on plans every job that has no live plan, for every user.
 */
@Component
@ConditionalOnProperty(name = "gacs.schedule.autorun-enabled", havingValue = "true", matchIfMissing = true)
public class JobAutorun {
    private final JobPlanningService planning;

    public JobAutorun(JobPlanningService planning) { this.planning = planning; }

    @Scheduled(fixedDelayString = "${gacs.schedule.autorun-ms:60000}", initialDelayString = "${gacs.schedule.autorun-ms:60000}")
    public void run() { planning.tick(); }
}
