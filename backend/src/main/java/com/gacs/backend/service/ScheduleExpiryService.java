package com.gacs.backend.service;

import com.gacs.backend.exception.ScheduleExportRejectedException;
import com.gacs.backend.model.ReviewState;
import com.gacs.backend.repository.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.PlatformTransactionManager;

@Service
public class ScheduleExpiryService {
    private final UserRepository users;
    private final OptimizationPlanRepository plans;
    private final ScheduleGate gate;
    private final TransactionTemplate transactions;
    public ScheduleExpiryService(UserRepository users, OptimizationPlanRepository plans, ScheduleGate gate,
                                 PlatformTransactionManager manager) {
        this.users = users;
        this.plans = plans;
        this.gate = gate;
        this.transactions = new TransactionTemplate(manager);
    }
    @Scheduled(fixedDelayString = "${gacs.schedule.expiry-check-ms:60000}", initialDelayString = "${gacs.schedule.expiry-check-ms:60000}")
    public void expire() {
        for (var user : users.findAll()) transactions.executeWithoutResult(tx -> {
            gate.lock(user.getEmail());
            for (var plan : plans.findByOwnerIdAndReviewState(user.getId(), ReviewState.APPROVED)) {
                try { gate.check(plan, true); }
                catch (ScheduleExportRejectedException ignored) { /* Commit invalidation and continue. */ }
            }
            // Waiting plans too: approved plans change what they were solved against, and a start can pass.
            for (var plan : plans.findByOwnerIdAndReviewState(user.getId(), ReviewState.PENDING)) {
                try { gate.check(plan, false); }
                catch (ScheduleExportRejectedException ignored) { /* Commit invalidation and continue. */ }
            }
        });
    }
}
