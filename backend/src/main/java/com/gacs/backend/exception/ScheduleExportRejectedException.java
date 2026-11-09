package com.gacs.backend.exception;

import org.springframework.http.HttpStatus;

/**
 * A schedule operation (decision, export, or application) refused by the approval gate
 * (docs/api-contract.md section 6.6, GRID-135 AC3; ALREADY_DECIDED extends the same gate to
 * {@code ScheduleReviewService#decide} for bug 3). The reason decides the HTTP status and is
 * returned in the error envelope as {@code data.reason}.
 */
public class ScheduleExportRejectedException extends RuntimeException {

    public enum Reason {
        /** The plan's review state is not APPROVED. */
        NOT_APPROVED(HttpStatus.FORBIDDEN),
        /** The approval has been overtaken: every slot has ended, or a newer approved plan schedules the same jobs. */
        STALE(HttpStatus.CONFLICT),
        /** The approved plan no longer matches its approval record or the jobs it schedules. */
        INVALIDATED(HttpStatus.CONFLICT),
        /** The run already carries a terminal decision (APPROVE/REJECT/REVISE); it cannot be decided again (bug 3). */
        ALREADY_DECIDED(HttpStatus.CONFLICT);

        private final HttpStatus status;

        Reason(HttpStatus status) {
            this.status = status;
        }

        public HttpStatus status() {
            return status;
        }
    }

    private final Reason reason;

    public ScheduleExportRejectedException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason getReason() {
        return reason;
    }
}
