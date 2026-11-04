import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { PlanRecommendation } from "./decision-panel"
import type { PlanReviewState, SchedulePlanDetail } from "@/lib/api"

const plan: SchedulePlanDetail = { runId: "owned-run", reviewState: "PENDING", invalidationReason: null, invalidatedAt: null, appliedAt: null,
  recommendation: { runId: "owned-run", status: "NEEDS_ATTENTION", provenOptimal: false, baselineCostUsd: 3, optimizedCostUsd: 4,
    projectedSavingsUsd: -1, projectedSavingsPercent: -33.33, plainEnglishExplanation: "<script>untrusted</script>", usedSeededFallbackPrices: false,
    items: [{ jobId: 1, jobName: "Training", baselineStart: "2026-11-02T10:00:00Z", scheduledStart: null, scheduledEnd: null, allocatedCpuHours: 0, allocatedGpuHours: 0, allocations: [], perJobStatus: "NO_FEASIBLE_SLOT", reason: "Insufficient capacity", baselineCostUsd: null, optimizedCostUsd: null, savingsUsd: null }] } }

describe("live recommendation display", () => {
  it.each(["PENDING", "APPROVED", "REJECTED", "REVISION_REQUESTED", "STALE", "INVALIDATED"] as PlanReviewState[])("renders %s state and server data", reviewState => {
    const html = renderToStaticMarkup(<PlanRecommendation plan={{ ...plan, reviewState }} />)
    expect(html).toContain("owned-run")
    expect(html).toContain("Training")
    expect(html).toContain("Insufficient capacity")
    expect(html).toContain("Not scheduled")
    expect(html).toContain("-$1.00")
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
    expect(html).not.toContain("Dispatched & Active")
  })
  it("renders a persisted invalidation reason even when recommendation data is unreadable", () => {
    const html = renderToStaticMarkup(<PlanRecommendation plan={{ ...plan, reviewState: "INVALIDATED", invalidationReason: "Job changed", recommendation: null }} />)
    expect(html).toContain("Job changed")
    expect(html).toContain("Invalidated")
  })
})
