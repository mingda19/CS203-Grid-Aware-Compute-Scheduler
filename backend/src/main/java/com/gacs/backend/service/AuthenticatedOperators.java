package com.gacs.backend.service;

import com.gacs.backend.model.User;
import com.gacs.backend.repository.UserRepository;
import org.springframework.security.authentication.BadCredentialsException;

/**
 * The one check {@link ScheduleReviewService} and {@link ScheduleExportService} both need before
 * doing anything else: the authenticated caller's email must still resolve to a database user. A
 * JWT can outlive the account it was issued for (e.g. the user was deleted), so this is checked
 * fresh on every call rather than trusted from the token.
 */
final class AuthenticatedOperators {

    private AuthenticatedOperators() {
    }

    /** @throws BadCredentialsException if {@code email} has no database user (401) */
    static User resolve(UserRepository users, String email) {
        return users.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Authenticated user no longer exists"));
    }
}
