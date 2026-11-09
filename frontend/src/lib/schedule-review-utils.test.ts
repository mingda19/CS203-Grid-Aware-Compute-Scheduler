import { describe, expect, it } from "vitest"
import { calendarUnavailable, decisionUnavailable, planUnavailable, rationaleError, reviewLabels, selectReviewRun } from "./schedule-review-utils"
import type { SchedulePlanDetail } from "./api"

const plan: SchedulePlanDetail = { runId: "run", reviewState: "PENDING", invalidationReason: null, invalidatedAt: null, appliedAt: null, ownerName: "Ada", canApprove: true, removedJobIds: [],
  recommendation: { runId: "run", status: "SCHEDULED", provenOptimal: true, baselineCostUsd: 2, optimizedCostUsd: 1, projectedSavingsUsd: 1,
    projectedSavingsPercent: 50, plainEnglishExplanation: "Shifted", usedSeededFallbackPrices: false,
    items: [{ jobId: 1, jobName: "Train", baselineStart: "2026-11-02T10:00:00Z", scheduledStart: "2026-11-02T11:00:00Z", scheduledEnd: "2026-11-02T12:00:00Z", allocatedCpuHours: 1, allocatedGpuHours: 0, allocations: [], perJobStatus: "OPTIMISED", reason: null, baselineCostUsd: 2, optimizedCostUsd: 1, savingsUsd: 1, rescheduledFromElapsedStart: false }] } }

describe("review controls", () => {
  it("requires a nonblank justification of at most 2,000 characters", () => {
    expect(rationaleError(" \n ")).toContain("justification")
    expect(rationaleError("a".repeat(2001))).toContain("2,000")
    expect(rationaleError("a".repeat(2000))).toBeNull()
  })
  it.each(["STALE", "INVALIDATED"] as const)("blocks all decisions for %s schedules", reviewState => {
    for (const action of ["approve", "reject", "revise"] as const)
      expect(decisionUnavailable({ ...plan, reviewState }, action)).toContain("plan was replaced")
  })
  it("blocks missing or unreadable plans", () => {
    expect(decisionUnavailable(null, "approve")).toBeTruthy()
    expect(decisionUnavailable({ ...plan, recommendation: null }, "approve")).toBeTruthy()
  })
  it("allows live decisions while preventing approval of an entirely unscheduled plan", () => {
    expect(decisionUnavailable(plan, "approve")).toBeNull()
    const unscheduled = { ...plan, recommendation: { ...plan.recommendation!, items: [] } }
    expect(decisionUnavailable(unscheduled, "approve")).toContain("no scheduled jobs")
    expect(decisionUnavailable(unscheduled, "revise")).toBeNull()
    expect(decisionUnavailable(unscheduled, "reject")).toBeNull()
  })
  it("blocks Approve with the reason when the caller may not approve, and leaves the other decisions open", () => {
    const own = { ...plan, canApprove: false }
    expect(decisionUnavailable(own, "approve")).toContain("cannot approve your own plan")
    for (const action of ["reject", "revise"] as const) expect(decisionUnavailable(own, action)).toBeNull()
    expect(decisionUnavailable({ ...own, reviewState: "APPROVED" }, "revoke")).toBeNull()
  })
  it("labels each persisted review state", () => {
    expect(Object.values(reviewLabels)).toEqual(["Pending review", "Approved", "Rejected", "Revision requested", "Stale", "Invalidated"])
  })
  it.each(["REJECTED", "REVISION_REQUESTED"] as const)("bug 3: blocks every action once a schedule is already %s", reviewState => {
    for (const action of ["approve", "reject", "revise", "revoke"] as const)
      expect(decisionUnavailable({ ...plan, reviewState }, action)).not.toBeNull()
  })
  it("bug 3: an approved schedule accepts no first decision again, and says what is still possible", () => {
    for (const action of ["approve", "reject", "revise"] as const)
      expect(decisionUnavailable({ ...plan, reviewState: "APPROVED" }, action))
        .toContain("Revoke the approval to halt it")
  })
  it("revoking is open on an approved schedule and closed on every other state", () => {
    // An approval may already have dispatched work, so it has to stay reversible; nothing was
    // dispatched from a rejected or revision-requested plan, so those are final.
    expect(decisionUnavailable({ ...plan, reviewState: "APPROVED" }, "revoke")).toBeNull()
    for (const reviewState of ["PENDING", "REJECTED", "REVISION_REQUESTED"] as const)
      expect(decisionUnavailable({ ...plan, reviewState }, "revoke"))
        .toContain("Only an approved schedule can be revoked")
  })
  it("bug 3: a terminal decision does not make the plan itself 'unavailable' for non-decision controls", () => {
    expect(planUnavailable({ ...plan, reviewState: "APPROVED" })).toBeNull()
    expect(planUnavailable({ ...plan, reviewState: "REJECTED" })).toBeNull()
    expect(planUnavailable({ ...plan, reviewState: "REVISION_REQUESTED" })).toBeNull()
  })
})

describe("approval before calendar application", () => {
  it.each(["PENDING", "REJECTED", "REVISION_REQUESTED", "STALE", "INVALIDATED"] as const)("prevents adding %s plans to the calendar", reviewState => {
    expect(calendarUnavailable({ ...plan, reviewState })).toContain("Approve")
  })
  it("allows approved, readable schedules with scheduled jobs", () => {
    expect(calendarUnavailable({ ...plan, reviewState: "APPROVED" })).toBeNull()
    expect(calendarUnavailable({ ...plan, reviewState: "APPROVED", recommendation: null })).toBeTruthy()
    expect(calendarUnavailable(null)).toBeTruthy()
  })
  /**
   * Bug: "Add to calendar" relit after the schedule had already been applied. The fact lived only in
   * the hook's component state, so navigating away and back lost it; the server's plan.appliedAt is
   * now on the DTO and is what this branch reads.
   */
  it("reports an already-applied schedule as done rather than available", () => {
    const applied = { ...plan, reviewState: "APPROVED" as const, appliedAt: "2026-11-04T09:00:00Z" }
    expect(calendarUnavailable(applied)).toBe("This schedule is already on the workload calendar.")
    // Still available while the server says it has never been applied.
    expect(calendarUnavailable({ ...applied, appliedAt: null })).toBeNull()
  })
  it("prefers 'already applied' over the no-scheduled-jobs message for an applied plan", () => {
    // An applied plan is finished either way; "already added" is the more informative answer, and
    // the empty-items case cannot legitimately have been applied in the first place.
    const emptyButApplied = {
      ...plan, reviewState: "APPROVED" as const, appliedAt: "2026-11-04T09:00:00Z",
      recommendation: { ...plan.recommendation!, items: [] },
    }
    expect(calendarUnavailable(emptyButApplied)).toBe("This schedule is already on the workload calendar.")
  })
  it("keeps an unreadable or unapproved plan's own reason ahead of the applied check", () => {
    // planUnavailable still wins: a stale plan needs regenerating, which matters more than whether
    // it once reached the calendar.
    expect(calendarUnavailable({ ...plan, reviewState: "STALE", appliedAt: "2026-11-04T09:00:00Z" }))
      .toContain("Approve")
    expect(calendarUnavailable({
      ...plan, reviewState: "APPROVED", appliedAt: "2026-11-04T09:00:00Z", recommendation: null,
    })).toContain("cannot be read")
  })
  it("opens the exact recommendation linked from the summary, including older runs", () => {
    expect(selectReviewRun("", ["newer-run"], "summary-run")).toBe("summary-run")
    expect(selectReviewRun("summary-run", ["newer-run"], "summary-run")).toBe("summary-run")
    expect(selectReviewRun("chosen-run", ["chosen-run", "newer-run"], "summary-run")).toBe("chosen-run")
    expect(selectReviewRun("", ["newer-run"], null)).toBe("newer-run")
    expect(selectReviewRun("", [], null)).toBe("")
  })
})
