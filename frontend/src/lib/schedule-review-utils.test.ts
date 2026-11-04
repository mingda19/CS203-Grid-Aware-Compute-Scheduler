import { describe, expect, it } from "vitest"
import { calendarUnavailable, decisionUnavailable, planUnavailable, rationaleError, reviewLabels, selectReviewRun } from "./schedule-review-utils"
import type { SchedulePlanDetail } from "./api"

const plan: SchedulePlanDetail = { runId: "run", reviewState: "PENDING", invalidationReason: null, invalidatedAt: null,
  recommendation: { runId: "run", status: "SCHEDULED", provenOptimal: true, baselineCostUsd: 2, optimizedCostUsd: 1, projectedSavingsUsd: 1,
    projectedSavingsPercent: 50, plainEnglishExplanation: "Shifted", usedSeededFallbackPrices: false,
    items: [{ jobId: 1, jobName: "Train", baselineStart: "2026-11-02T10:00:00Z", scheduledStart: "2026-11-02T11:00:00Z", scheduledEnd: "2026-11-02T12:00:00Z", allocatedCpuHours: 1, allocatedGpuHours: 0, allocations: [], perJobStatus: "OPTIMISED", reason: null, baselineCostUsd: 2, optimizedCostUsd: 1, savingsUsd: 1 }] } }

describe("review controls", () => {
  it("requires a nonblank justification of at most 2,000 characters", () => {
    expect(rationaleError(" \n ")).toContain("justification")
    expect(rationaleError("a".repeat(2001))).toContain("2,000")
    expect(rationaleError("a".repeat(2000))).toBeNull()
  })
  it.each(["STALE", "INVALIDATED"] as const)("blocks all decisions for %s schedules", reviewState => {
    for (const action of ["approve", "reject", "revise"] as const)
      expect(decisionUnavailable({ ...plan, reviewState }, action)).toContain("new recommendation")
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
  it("labels each persisted review state", () => {
    expect(Object.values(reviewLabels)).toEqual(["Pending review", "Approved", "Rejected", "Revision requested", "Stale", "Invalidated"])
  })
  it.each(["APPROVED", "REJECTED", "REVISION_REQUESTED"] as const)("bug 3: blocks every action once a schedule is already %s", reviewState => {
    for (const action of ["approve", "reject", "revise"] as const)
      expect(decisionUnavailable({ ...plan, reviewState }, action)).toContain("cannot be decided again")
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
  it("opens the exact recommendation linked from the summary, including older runs", () => {
    expect(selectReviewRun("", ["newer-run"], "summary-run")).toBe("summary-run")
    expect(selectReviewRun("summary-run", ["newer-run"], "summary-run")).toBe("summary-run")
    expect(selectReviewRun("chosen-run", ["chosen-run", "newer-run"], "summary-run")).toBe("chosen-run")
    expect(selectReviewRun("", ["newer-run"], null)).toBe("newer-run")
    expect(selectReviewRun("", [], null)).toBe("")
  })
})
