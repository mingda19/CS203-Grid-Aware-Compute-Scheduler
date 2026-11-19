package com.gacs.backend.service;

import com.gacs.backend.model.AppSettings;
import com.gacs.backend.model.OptimizationPlan;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.AppSettingsRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AppSettingsService {

    private final AppSettingsRepository settings;

    public AppSettingsService(AppSettingsRepository settings) {
        this.settings = settings;
    }

    /** The one settings row, created with the defaults on first read. */
    @Transactional
    public AppSettings get() {
        return settings.findById(AppSettings.ID).orElseGet(() -> settings.save(new AppSettings()));
    }

    @Transactional
    public AppSettings update(boolean allowSelfApproval) {
        AppSettings current = get();
        current.setAllowSelfApproval(allowSelfApproval);
        return settings.save(current);
    }

    public static boolean isAdmin(User user) {
        return "ROLE_ADMIN".equals(user.getRole());
    }

    /**
     * Anyone may approve a plan except its submitter, unless the submitter is an admin or an admin has
     * allowed self-approval. Rejecting and revising are not limited by this rule; revoking is, see
     * {@link #canRevoke}.
     */
    @Transactional
    public boolean canApprove(User caller, OptimizationPlan plan) {
        return canApprove(caller, plan.getOwner().getId(), get().isAllowSelfApproval());
    }

    /** The same rule for a caller of many plans, which read the setting once. */
    public static boolean canApprove(User caller, Long ownerId, boolean allowSelfApproval) {
        return !caller.getId().equals(ownerId) || allowSelfApproval || isAdmin(caller);
    }

    /** Only the plan's owner (the jobs' author) or an admin may withdraw an approval. */
    public static boolean canRevoke(User caller, OptimizationPlan plan) {
        return caller.getId().equals(plan.getOwner().getId()) || isAdmin(caller);
    }
}
