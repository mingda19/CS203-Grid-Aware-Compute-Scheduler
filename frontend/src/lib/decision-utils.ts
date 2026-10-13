export interface OptimizationWorkloadItem {
  id: string
  name: string
  type: string
  cluster: string
  powerKw: number
  originalWindow: string
  proposedWindow: string
  deadline: string
  baselineCostUsd: number
  optimizedCostUsd: number
  savingsUsd: number
  slackHours: number
  rationale: string
  included: boolean
}

export interface DecisionImpactMetrics {
  baselineCost: number
  optimizedCost: number
  netSavings: number
  savingsPercent: number
  totalPowerShiftedKw: number
  totalPowerShiftedMw: number
  baselinePeakMw: number
  optimizedPeakMw: number
  peakReductionMw: number
  includedCount: number
  totalCount: number
  allSlaMet: boolean
  minSlackHours: number
  cleanEnergyPercent: number
  cleanEnergyBaselinePercent: number
}

// Facility fixed baseline base cost (cooling, idle servers, lighting) in USD
const FACILITY_BASE_COST = 5400

export function calculateDecisionImpact(
  items: OptimizationWorkloadItem[],
  baselinePeakCapacityMw = 4.8
): DecisionImpactMetrics {
  const includedItems = items.filter((item) => item.included)

  const itemsBaselineCost = includedItems.reduce((acc, item) => acc + item.baselineCostUsd, 0)
  const itemsOptimizedCost = includedItems.reduce((acc, item) => acc + item.optimizedCostUsd, 0)

  const baselineCost = itemsBaselineCost + FACILITY_BASE_COST
  const optimizedCost = itemsOptimizedCost + FACILITY_BASE_COST
  const netSavings = Math.max(0, baselineCost - optimizedCost)
  const savingsPercent = baselineCost > 0 ? (netSavings / baselineCost) * 100 : 0

  const totalPowerShiftedKw = includedItems.reduce((acc, item) => acc + item.powerKw, 0)
  const totalPowerShiftedMw = totalPowerShiftedKw / 1000

  // Peak load reduction calculation (coincidence factor ~ 0.44 during peak hours)
  const peakReductionMw = Number((totalPowerShiftedMw * 0.438).toFixed(2))
  const optimizedPeakMw = Number(Math.max(1.0, baselinePeakCapacityMw - peakReductionMw).toFixed(2))

  const minSlackHours = includedItems.length > 0
    ? Math.min(...includedItems.map((item) => item.slackHours))
    : 0
  const allSlaMet = includedItems.every((item) => item.slackHours >= 0)

  // Clean energy mix scales with wind valley shift volume
  const cleanEnergyBaselinePercent = 41.2
  const cleanEnergyBonus = Math.min(30, (totalPowerShiftedMw / 3.77) * 27.3)
  const cleanEnergyPercent = Number((cleanEnergyBaselinePercent + cleanEnergyBonus).toFixed(1))

  return {
    baselineCost,
    optimizedCost,
    netSavings,
    savingsPercent,
    totalPowerShiftedKw,
    totalPowerShiftedMw,
    baselinePeakMw: baselinePeakCapacityMw,
    optimizedPeakMw,
    peakReductionMw,
    includedCount: includedItems.length,
    totalCount: items.length,
    allSlaMet,
    minSlackHours,
    cleanEnergyPercent,
    cleanEnergyBaselinePercent,
  }
}

export function formatCurrency(amount: number): string {
  return new Intl.NumberFormat("en-US", {
    style: "currency",
    currency: "USD",
    maximumFractionDigits: 0,
  }).format(amount)
}

export function formatMw(mw: number): string {
  return `${mw.toFixed(2)} MW`
}
