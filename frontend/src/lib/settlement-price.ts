import type { PricePoint } from "./api"
import { formatMarketDateTime, parseIsoDate } from "./date-utils"

export function currentSettlementMetrics(points: PricePoint[], asOf: number) {
  const eligible = points.filter((point): point is PricePoint & { sppUsdMwh: number } =>
    point.sppUsdMwh != null && Number.isFinite(point.sppUsdMwh) &&
    parseIsoDate(point.intervalStartUtc).getTime() <= asOf,
  ).sort((a, b) => parseIsoDate(a.intervalStartUtc).getTime() - parseIsoDate(b.intervalStartUtc).getTime())
  const latest = eligible.at(-1) ?? null
  const recent = eligible.slice(-288)
  const average = recent.length ? recent.reduce((sum, point) => sum + point.sppUsdMwh, 0) / recent.length : null
  const current = latest?.sppUsdMwh ?? null
  const diff = current != null && average != null && average !== 0 ? (current - average) / Math.abs(average) * 100 : 0
  const peak = eligible.reduce<typeof latest>((highest, point) =>
    !highest || point.sppUsdMwh > highest.sppUsdMwh ? point : highest, null)
  return {
    latest,
    currentLmp: current,
    formattedCurrent: current == null ? "—" : `$${current.toFixed(2)}`,
    diffPercent: Math.abs(diff),
    isDrop: diff <= 0,
    rollingAvgFormatted: average == null ? "—" : `$${average.toFixed(2)}`,
    peakPriceFormatted: peak == null ? "—" : `$${peak.sppUsdMwh.toFixed(2)}`,
    peakWindow: peak == null ? "Recent peak: —" : `Peak ${formatMarketDateTime(peak.intervalStartUtc)}`,
    stale: latest != null && asOf - parseIsoDate(latest.intervalStartUtc).getTime() >= 3_600_000,
  }
}
