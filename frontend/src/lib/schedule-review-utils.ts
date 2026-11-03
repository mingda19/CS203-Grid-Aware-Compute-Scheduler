import type { SchedulePlanDetail, PlanReviewState, ScheduleDecisionAction } from "./api"

export const reviewLabels: Record<PlanReviewState, string> = {
  PENDING: "Pending review", APPROVED: "Approved", REJECTED: "Rejected",
  REVISION_REQUESTED: "Revision requested", STALE: "Stale", INVALIDATED: "Invalidated",
}

export function decisionUnavailable(plan: SchedulePlanDetail | null, action: ScheduleDecisionAction): string | null {
  if (!plan) return "Select a schedule to review."
  if (plan.reviewState === "STALE" || plan.reviewState === "INVALIDATED") return "Generate a new recommendation for this schedule."
  if (!plan.recommendation) return "The stored recommendation cannot be read. Generate a new recommendation."
  if (action === "approve" && !plan.recommendation.items.some(item => item.scheduledStart && item.scheduledEnd)) return "This recommendation has no scheduled jobs to approve."
  return null
}

export function rationaleError(comment: string): string | null {
  if (!comment.trim()) return "Enter a justification for this decision."
  if (comment.length > 2000) return "Justification must not exceed 2,000 characters."
  return null
}

export function calendarUnavailable(plan: SchedulePlanDetail | null): string | null {
  if (!plan || plan.reviewState !== "APPROVED") return "Approve this recommendation before adding it to the calendar."
  return decisionUnavailable(plan, "approve")
}

export function selectReviewRun(current: string, runIds: string[], requested: string | null): string {
  if (current && (runIds.includes(current) || current === requested)) return current
  return (!current && requested) || runIds[0] || ""
}
