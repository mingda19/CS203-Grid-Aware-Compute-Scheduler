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
    OVERRIDE,
    DISPATCH
}
