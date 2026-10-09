import { describe, it, expect } from "vitest"
import { getWorkloadActionImpact, applyWorkloadAction } from "./workload-actions"
import type { Workload } from "./workload-data"

const sampleWorkload: Workload = {
  id: "WL-409",
  name: "Llama-3-70B Fine-Tuning Run #4",
  type: "ML Training",
  powerKw: 1200,
  actualPowerKw: 1200,
  scheduledWindow: "01:30 – 05:30 UTC",
  deadline: "10:00 AM UTC",
  savings: "$1,840 (27%)",
  status: "Running",
  machineCluster: "64x NVIDIA H100 SXM5",
  priority: "High",
  slaBuffer: "+4.5 hrs buffer before SLA violation",
  slaRisk: "Zero",
}

describe("workload-actions", () => {
  it("calculates impact of throttling a running workload by 50%", () => {
    const impact = getWorkloadActionImpact(sampleWorkload, "throttle")
    expect(impact.nextStatus).toBe("Throttled")
    expect(impact.currentPowerKw).toBe(1200)
    expect(impact.nextPowerKw).toBe(600)
    expect(impact.powerDeltaKw).toBe(-600)
    expect(impact.powerDeltaPercent).toBe(-50)
    expect(impact.confirmButtonLabel).toContain("600 kW")
  })

  it("calculates impact of pausing a running workload", () => {
    const impact = getWorkloadActionImpact(sampleWorkload, "pause")
    expect(impact.nextStatus).toBe("Paused")
    expect(impact.nextPowerKw).toBe(0)
    expect(impact.powerDeltaKw).toBe(-1200)
    expect(impact.confirmButtonVariant).toBe("destructive")
    expect(impact.slaRisk).toBe("Moderate")
  })

  it("calculates impact of resuming a throttled workload to 100%", () => {
    const throttledWorkload: Workload = {
      ...sampleWorkload,
      status: "Throttled",
      actualPowerKw: 600,
    }
    const impact = getWorkloadActionImpact(throttledWorkload, "resume")
    expect(impact.nextStatus).toBe("Running")
    expect(impact.nextPowerKw).toBe(1200)
    expect(impact.powerDeltaKw).toBe(600)
    expect(impact.slaRisk).toBe("Zero")
  })

  it("applies action to produce updated workload state", () => {
    const updated = applyWorkloadAction(sampleWorkload, "throttle")
    expect(updated.status).toBe("Throttled")
    expect(updated.actualPowerKw).toBe(600)
  })
})
