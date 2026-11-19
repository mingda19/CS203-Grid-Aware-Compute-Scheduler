package com.gacs.backend.model;

/**
 * Valid action types recorded in the append-only audit log.
 * Covers operator governance actions (approve, reject, revise, override, dispatch)
 * per PRD section 6.6 and Jira GRID-83 / GRID-134.
 */
public enum AuditActionType {
    APPROVE,
    REJECT,
    REVISE,
    /**
     * Withdraws an approval that was already given, halting a schedule that may already have been
     * dispatched. Deliberately distinct from {@link #REJECT}: rejecting is a first decision on a
     * pending recommendation, revoking undoes a decision already made, and the two carry different
     * risk. Overloading REJECT to mean both - which it did - is why the UI could offer "Reject" on an
     * approved plan as though it were still an open choice.
     */
    REVOKE,
    OVERRIDE,
    DISPATCH,
    /** A job was deleted from an approved plan. The rest of the plan stays approved. */
    REMOVE_JOB
}
