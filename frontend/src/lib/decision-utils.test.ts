import { describe, it, expect } from "vitest"
import {
  calculateDecisionImpact,
  formatCurrency,
  formatMw,
  type OptimizationWorkloadItem,
} from "./decision-utils"

const mockWorkloads: OptimizationWorkloadItem[] = [
  {
    id: "WL-409",
    name: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    cluster: "64x NVIDIA H100 SXM5",
    powerKw: 1200,
    originalWindow: "01:00 – 05:00 SGT",
    proposedWindow: "09:30 – 13:30 SGT",
    deadline: "18:00 SGT (Today)",
    baselineCostUsd: 5640,
    optimizedCostUsd: 3800,
    savingsUsd: 1840,
    slackHours: 4.5,
    rationale: "Shift forward to West Texas overnight wind valley ($14.80/MWh)",
    included: true,
  },
  {
    id: "WL-108",
    name: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    cluster: "Antminer S19 Pro+ Pod 2",
    powerKw: 1800,
    originalWindow: "01:00 – 04:00 SGT (Full)",
    proposedWindow: "01:00 – 04:00 SGT (Throttled)",
    deadline: "Flexible Throughput",
    baselineCostUsd: 4680,
    optimizedCostUsd: 3230,
    savingsUsd: 1450,
    slackHours: 12.0,
    rationale: "Throttle non-urgent hash rate during thermal peak spike ($142.50/MWh)",
    included: true,
  },
  {
    id: "WL-812",
    name: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    cluster: "Slurm HPC Cluster (96 Nodes)",
    powerKw: 450,
    originalWindow: "14:00 – 16:30 SGT",
    proposedWindow: "10:00 – 12:30 SGT",
    deadline: "16:00 SGT (Today)",
    baselineCostUsd: 1390,
    optimizedCostUsd: 870,
    savingsUsd: 520,
    slackHours: 3.5,
    rationale: "Consolidate into cheap morning window before peak demand ramp",
    included: true,
  },
  {
    id: "WL-022",
    name: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    cluster: "Trane Centrifugal Chiller Bank",
    powerKw: 320,
    originalWindow: "01:00 – 03:30 SGT",
    proposedWindow: "21:30 – 00:00 SGT",
    deadline: "Peak Window (01:00 SGT)",
    baselineCostUsd: 1280,
    optimizedCostUsd: 970,
    savingsUsd: 310,
    slackHours: 2.5,
    rationale: "Pre-chill chilled water buffer before ERCOT thermal price spike",
    included: true,
  },
]

describe("decision-utils", () => {
  it("calculates comprehensive decision impact with all workloads included", () => {
    const impact = calculateDecisionImpact(mockWorkloads)

    expect(impact.includedCount).toBe(4)
    expect(impact.totalCount).toBe(4)
    expect(impact.netSavings).toBe(4120) // 1840 + 1450 + 520 + 310 = 4120
    expect(impact.savingsPercent).toBeGreaterThan(20)
    expect(impact.totalPowerShiftedKw).toBe(3770) // 1200 + 1800 + 450 + 320
    expect(impact.allSlaMet).toBe(true)
    expect(impact.minSlackHours).toBe(2.5)
    expect(impact.cleanEnergyPercent).toBeGreaterThan(60)
  })

  it("dynamically recalculates metrics when a workload is excluded", () => {
    const modified = mockWorkloads.map((w) =>
      w.id === "WL-812" ? { ...w, included: false } : w
    )
    const impact = calculateDecisionImpact(modified)

    expect(impact.includedCount).toBe(3)
    expect(impact.totalCount).toBe(4)
    expect(impact.netSavings).toBe(3600) // 4120 - 520 = 3600
    expect(impact.totalPowerShiftedKw).toBe(3320)
    expect(impact.allSlaMet).toBe(true)
  })

  it("handles empty / all excluded workloads gracefully", () => {
    const allExcluded = mockWorkloads.map((w) => ({ ...w, included: false }))
    const impact = calculateDecisionImpact(allExcluded)

    expect(impact.includedCount).toBe(0)
    expect(impact.netSavings).toBe(0)
    expect(impact.totalPowerShiftedKw).toBe(0)
    expect(impact.totalPowerShiftedMw).toBe(0)
  })

  it("formats currency and MW metrics cleanly", () => {
    expect(formatCurrency(4120)).toBe("$4,120")
    expect(formatMw(3.15)).toBe("3.15 MW")
  })
})
