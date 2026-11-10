import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { PlanHeader, PlanRecommendation } from "./PlanRecommendation"
import type { PlanReviewState, SchedulePlanDetail } from "@/lib/api"

const plan: SchedulePlanDetail = { runId: "owned-run", reviewState: "PENDING", invalidationReason: null, invalidatedAt: null, appliedAt: null, ownerName: "Ada", canApprove: true, canRevoke: true, removedJobIds: [],
  recommendation: { runId: "owned-run", status: "NEEDS_ATTENTION", provenOptimal: false, baselineCostUsd: 3, optimizedCostUsd: 4,
    projectedSavingsUsd: -1, projectedSavingsPercent: -33.33, plainEnglishExplanation: "<script>untrusted</script>", usedSeededFallbackPrices: false,
    items: [{ jobId: 1, jobName: "Training", baselineStart: "2026-11-02T10:00:00Z", scheduledStart: null, scheduledEnd: null, allocatedCpuHours: 0, allocatedGpuHours: 0, allocations: [], perJobStatus: "NO_FEASIBLE_SLOT", reason: "Insufficient capacity", baselineCostUsd: null, optimizedCostUsd: null, savingsUsd: null, rescheduledFromElapsedStart: false }] } }

const placed = { ...plan.recommendation!.items[0], jobId: 2, jobName: "Nightly ETL", scheduledStart: "2026-11-02T11:00:00Z", scheduledEnd: "2026-11-02T12:00:00Z", perJobStatus: "OPTIMISED" as const, reason: null, savingsUsd: 0.5 }

describe("live recommendation display", () => {
  it.each(["PENDING", "APPROVED", "REJECTED", "REVISION_REQUESTED", "STALE", "INVALIDATED"] as PlanReviewState[])("renders %s state and server data", reviewState => {
    const html = renderToStaticMarkup(<><PlanHeader plan={{ ...plan, reviewState }} /><PlanRecommendation plan={{ ...plan, reviewState }} /></>)
    expect(html).toContain("owned-run")
    expect(html).toContain("Training")
    expect(html).toContain("Insufficient capacity")
    expect(html).toContain("Not placed")
    expect(html).toContain("Costs $1.00 more")
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
    expect(html).not.toContain("Dispatched & Active")
  })
  it("names the owner and labels a job deleted after approval", () => {
    const approved = { ...plan, reviewState: "APPROVED" as const, removedJobIds: [1],
      recommendation: { ...plan.recommendation!, items: [{ ...plan.recommendation!.items[0], scheduledStart: "2026-11-02T11:00:00Z", scheduledEnd: "2026-11-02T12:00:00Z" }] } }
    const html = renderToStaticMarkup(<><PlanHeader plan={approved} /><PlanRecommendation plan={approved} /></>)
    expect(html).toContain("Submitted by Ada")
    expect(html).toContain("Deleted")
  })
  it("gives the time zone once and shows exempt jobs without a moved time", () => {
    const html = renderToStaticMarkup(<PlanRecommendation plan={{ ...plan, recommendation: { ...plan.recommendation!, items: [{ ...placed, perJobStatus: "UNCHANGED_EXEMPT" }] } }} />)
    expect(html.match(/Times are in/g)).toHaveLength(1)
    expect(html).toContain("Not moved (exempt)")
  })
  it("titles the plan with its job names", () => {
    const html = renderToStaticMarkup(<PlanHeader plan={{ ...plan, recommendation: { ...plan.recommendation!, items: [placed] } }} />)
    expect(html).toContain("Nightly ETL")
  })
  it("renders a persisted invalidation reason even when recommendation data is unreadable", () => {
    const html = renderToStaticMarkup(<PlanHeader plan={{ ...plan, reviewState: "INVALIDATED", invalidationReason: "Job changed", recommendation: null }} />)
    expect(html).toContain("Invalidated")
  })
})
