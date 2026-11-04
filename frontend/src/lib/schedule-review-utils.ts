import type { SchedulePlanDetail, PlanReviewState, ScheduleDecisionAction } from "./api"

export const reviewLabels: Record<PlanReviewState, string> = {
  PENDING: "Pending review", APPROVED: "Approved", REJECTED: "Rejected",
  REVISION_REQUESTED: "Revision requested", STALE: "Stale", INVALIDATED: "Invalidated",
}

/**
 * The universal blockers: true regardless of which action is being attempted, and regardless of
 * whether the plan currently holds a terminal decision. Used both by decisionUnavailable (every
 * action) and calendarUnavailable (only reachable once APPROVED, so it never needs the terminal
 * check below) to gate the controls around a review - the plan picker, refresh, "add to
 * calendar" - that stay meaningful even once a decision is terminal.
 */
export function planUnavailable(plan: SchedulePlanDetail | null): string | null {
  if (!plan) return "Select a schedule to review."
  if (plan.reviewState === "STALE" || plan.reviewState === "INVALIDATED") return "Generate a new recommendation for this schedule."
  if (!plan.recommendation) return "The stored recommendation cannot be read. Generate a new recommendation."
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
  if (action === "revoke")
    return plan!.reviewState === "APPROVED" ? null
      : `Only an approved schedule can be revoked. This one is ${reviewLabels[plan!.reviewState].toLowerCase()}.`
  if (TERMINAL_DECISIONS.has(plan!.reviewState))
    return plan!.reviewState === "APPROVED"
      ? "This schedule is already approved. Revoke the approval to halt it."
      : `This schedule is already ${reviewLabels[plan!.reviewState].toLowerCase()} and cannot be decided again.`
  if (action === "approve" && !plan!.recommendation!.items.some(item => item.scheduledStart && item.scheduledEnd)) return "This recommendation has no scheduled jobs to approve."
  return null
}

export function rationaleError(comment: string): string | null {
  if (!comment.trim()) return "Enter a justification for this decision."
  if (comment.length > 2000) return "Justification must not exceed 2,000 characters."
  return null
}

export function calendarUnavailable(plan: SchedulePlanDetail | null): string | null {
  if (!plan || plan.reviewState !== "APPROVED") return "Approve this recommendation before adding it to the calendar."
  const universal = planUnavailable(plan)
  if (universal) return universal
  if (!plan.recommendation!.items.some(item => item.scheduledStart && item.scheduledEnd)) return "This recommendation has no scheduled jobs to approve."
  return null
}

export function selectReviewRun(current: string, runIds: string[], requested: string | null): string {
  if (current && (runIds.includes(current) || current === requested)) return current
  return (!current && requested) || runIds[0] || ""
}
