import type { SchedulePlanDetail, PlanReviewState, ScheduleDecisionAction } from "./api"

export const reviewLabels: Record<PlanReviewState, string> = {
  PENDING: "Pending review", APPROVED: "Approved", REJECTED: "Rejected",
  REVISION_REQUESTED: "Revision requested", STALE: "Stale", INVALIDATED: "Invalidated",
}

/**
 * The universal blockers: true regardless of which action is being attempted, and regardless of
 * whether the plan currently holds a terminal decision. Used both by decisionUnavailable (every
 * action) and the controls around a review - the plan list, refresh - that stay
 * meaningful even once a decision is terminal.
 */
export function planUnavailable(plan: SchedulePlanDetail | null): string | null {
  if (!plan) return "Select a plan."
  if (plan.reviewState === "STALE" || plan.reviewState === "INVALIDATED") return "This plan is out of date. The system makes a new one within a minute."
  if (!plan.recommendation) return "This plan cannot be read."
  return null
}

/**
 * Review states a plan reaches only through a user's own decision, mirroring the backend's
 * TERMINAL_DECISIONS in ScheduleReviewService - kept as a literal set (not derived from
 * reviewLabels) so adding a future non-terminal state to that record doesn't silently widen this.
 */
const TERMINAL_DECISIONS: ReadonlySet<PlanReviewState> = new Set(["APPROVED", "REJECTED", "REVISION_REQUESTED"])

/**
 * Bug 3: a plan may carry exactly one terminal decision. Once it is APPROVED, REJECTED, or
 * REVISION_REQUESTED, every action (including re-pressing the same one) is blocked here so the UI
 * reflects that state instead of leaving all three buttons clickable and letting a later click
 * silently reverse an earlier decision on the same schedule.
 */
export function decisionUnavailable(plan: SchedulePlanDetail | null, action: ScheduleDecisionAction): string | null {
  const universal = planUnavailable(plan)
  if (universal) return universal
  // Revoking is the one move still open on a decided plan, and only on an approved one: an approval
  // may already have dispatched work, so there has to be a way to halt it. A rejected or
  // revision-requested plan dispatched nothing, so it is final.
  if (action === "revoke") {
    if (plan!.reviewState !== "APPROVED")
      return `Only an approved schedule can be revoked. This one is ${reviewLabels[plan!.reviewState].toLowerCase()}.`
    return plan!.canRevoke ? null : "Only the plan's owner or an admin can revoke its approval."
  }
  if (TERMINAL_DECISIONS.has(plan!.reviewState))
    return plan!.reviewState === "APPROVED"
      ? "This schedule is already approved. Revoke the approval to halt it."
      : `This schedule is already ${reviewLabels[plan!.reviewState].toLowerCase()} and cannot be decided again.`
  if (action === "approve" && !plan!.canApprove) return "You submitted this plan. Someone else has to approve it."
  if (action === "approve" && !plan!.recommendation!.items.some(item => item.scheduledStart && item.scheduledEnd)) return "No job in this plan is scheduled, so there is nothing to approve."
  return null
}

export function rationaleError(comment: string): string | null {
  if (!comment.trim()) return "Enter a justification for this decision."
  if (comment.length > 2000) return "Justification must not exceed 2,000 characters."
  return null
}

/** "A, B +2 more" for a list row or a title; "No jobs" when the plan names none. */
export function jobNamesLabel(names: string[], max = 2): string {
  if (!names.length) return "No jobs"
  const shown = names.slice(0, max).join(", ")
  return names.length > max ? `${shown} +${names.length - max} more` : shown
}
