import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, it, expect } from "vitest"
import type { ScheduleItem } from "../../lib/api"
import { RecommendationExplanation } from "./RecommendationExplanation"

const item: ScheduleItem = {
  jobId: 1, jobName: "Training", baselineStart: "2026-10-20T10:00:00Z",
  scheduledStart: "2026-10-20T11:00:00Z", scheduledEnd: "2026-10-20T12:00:00Z",
  allocatedCpuHours: 1, allocatedGpuHours: 0, allocations: [], perJobStatus: "OPTIMISED",
  reason: null, baselineCostUsd: 1, optimizedCostUsd: 1.25, savingsUsd: -0.25, rescheduledFromElapsedStart: false,
}

const render = (overrides = {}) => renderToStaticMarkup(<RecommendationExplanation
  item={item} loading={false} error="" onRetry={() => {}} {...overrides}
/>)

describe("recommendation explanation", () => {
  it("shows backend prose as escaped text, constraints and cost increases", () => {
    const html = render({ explanation: { jobId: 1, whyThisPlacement: "<script>bad()</script>", constraints: [
      { constraintType: "DEADLINE", code: "DEADLINE_SLACK", slackHours: 2, relatedJobIds: [], explanation: "Finishes before deadline." },
    ] } })
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
    expect(html).toContain("$0.25 increase")
    expect(html).toContain("Finishes before deadline.")
    expect(html).toContain("2 hours of slack")
  })

  it("does not claim constraints were satisfied for an unscheduled job", () => {
    const html = render({ item: { ...item, perJobStatus: "NO_FEASIBLE_SLOT", baselineCostUsd: null, optimizedCostUsd: null, savingsUsd: null } })
    expect(html).toContain("Unavailable — job was not scheduled")
    expect(html).toContain("no constraints are reported as satisfied")
    expect(html).not.toContain("Constraints respected")
  })

  it("shows loading and retry states", () => {
    expect(render({ loading: true })).toContain("Loading explanation")
    expect(render({ error: "Could not load" })).toContain("Retry")
  })

  it("distinguishes zero savings from unavailable costs", () => {
    expect(render({ item: { ...item, savingsUsd: 0 } })).toContain("No cost change")
  })
})
