import { describe, expect, it } from "vitest"
import { decisionUnavailable, planUnavailable, rationaleError, reviewLabels, jobNamesLabel } from "./schedule-review-utils"
import type { SchedulePlanDetail } from "./api"

const plan: SchedulePlanDetail = { runId: "run", reviewState: "PENDING", invalidationReason: null, invalidatedAt: null, appliedAt: null, ownerName: "Ada", canApprove: true, canRevoke: true, removedJobIds: [],
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
      expect(decisionUnavailable({ ...plan, reviewState }, action)).toContain("out of date")
  })
  it("blocks missing or unreadable plans", () => {
    expect(decisionUnavailable(null, "approve")).toBeTruthy()
    expect(decisionUnavailable({ ...plan, recommendation: null }, "approve")).toBeTruthy()
  })
  it("allows live decisions while preventing approval of an entirely unscheduled plan", () => {
    expect(decisionUnavailable(plan, "approve")).toBeNull()
    const unscheduled = { ...plan, recommendation: { ...plan.recommendation!, items: [] } }
    expect(decisionUnavailable(unscheduled, "approve")).toContain("nothing to approve")
    expect(decisionUnavailable(unscheduled, "revise")).toBeNull()
    expect(decisionUnavailable(unscheduled, "reject")).toBeNull()
  })
  it("blocks Approve with the reason when the caller may not approve, and leaves the other decisions open", () => {
    const own = { ...plan, canApprove: false }
    expect(decisionUnavailable(own, "approve")).toContain("You submitted this plan")
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

describe("job names label", () => {
  it("lists up to the limit and counts the rest", () => {
    expect(jobNamesLabel([])).toBe("No jobs")
    expect(jobNamesLabel(["A"])).toBe("A")
    expect(jobNamesLabel(["A", "B"])).toBe("A, B")
    expect(jobNamesLabel(["A", "B", "C"])).toBe("A, B +1 more")
    expect(jobNamesLabel(["A", "B", "C", "D", "E"], 3)).toBe("A, B, C +2 more")
  })
})
