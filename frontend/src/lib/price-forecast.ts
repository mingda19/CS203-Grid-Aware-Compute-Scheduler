import type { ForecastAccuracyPoint, ForecastModel, PriceForecastPoint, PricePoint } from "./api"
import { getMarketDateString, parseIsoDate } from "./date-utils"

export const HOUR_MS = 3_600_000

/** The two forecast models, for the per-card switch. Plans use LEAR and fall back to LSTM. */
export const MODEL_OPTIONS = [{ value: "lstm", label: "LSTM" }, { value: "lear", label: "LEAR" }] as const
export const MODEL_LABEL: Record<ForecastModel, string> = { lear: "LEAR", lstm: "LSTM" }
export const MODEL_ROLE: Record<ForecastModel, string> = { lear: "used for plans", lstm: "fallback for plans" }
/** The Forecast page covers the next 72 hours, the same window plans are priced on. */
export const FORECAST_HOURS = 72

/** One hour on the Forecast chart. */
export interface ForecastRow {
  t: number
  /** Day-ahead market price. Known for past hours and for future hours the market has already cleared. */
  price: number | null
  /** The plan model's forecast. Only for hours after the last known price. */
  forecast: number | null
  /** Forecast ± the model's typical error for that hour, for the shaded band. */
  band: [number, number] | null
  typicalError: number | null
  /** 0-1, or null when the model has no score for the hour. */
  confidence: number | null
}

/**
 * Merges known prices and forecasts into one row per hour inside [from, to].
 * A known price always wins: forecasts start after the last known hour. That last known hour also
 * carries the forecast values, so the dashed lines start where the solid one ends.
 */
export function buildForecastRows(
  history: PricePoint[],
  forecast: PriceForecastPoint[],
  from: number,
  to: number,
): ForecastRow[] {
  const rows = new Map<number, ForecastRow>()
  const row = (t: number) => {
    let r = rows.get(t)
    if (!r) {
      r = { t, price: null, forecast: null, band: null, typicalError: null, confidence: null }
      rows.set(t, r)
    }
    return r
  }
  const inWindow = (t: number) => Number.isFinite(t) && t >= from && t <= to

  let lastKnown = -Infinity
  for (const p of history) {
    const t = parseIsoDate(p.intervalStartUtc).getTime()
    if (p.sppUsdMwh == null || !inWindow(t)) continue
    row(t).price = p.sppUsdMwh
    lastKnown = Math.max(lastKnown, t)
  }

  for (const p of forecast) {
    const t = parseIsoDate(p.intervalStartUtc).getTime()
    if (p.predictedPrice == null || !inWindow(t) || t <= lastKnown) continue
    const r = row(t)
    const error = p.typicalErrorUsdMwh ?? null
    r.forecast = p.predictedPrice
    r.typicalError = error
    r.band = error == null ? null : [p.predictedPrice - error, p.predictedPrice + error]
    r.confidence = p.confidenceScore ?? null
  }

  const anchor = rows.get(lastKnown)
  if (anchor?.price != null) {
    const after = [...rows.values()].filter((r) => r.t > lastKnown)
    if (after.some((r) => r.forecast != null)) {
      anchor.forecast = anchor.price
      if (after.some((r) => r.band != null)) anchor.band = [anchor.price, anchor.price]
    }
  }

  return [...rows.values()].sort((a, b) => a.t - b.t)
}

/** Cheapest hours marked per CT day. */
export const LOW_HOURS_PER_DAY = 4

/**
 * The start times of the cheapest LOW_HOURS_PER_DAY hours of each CT day, counting hours from
 * `fromHour` on and using the known price, else the forecast. A day with no more priced hours than
 * that (the tail of today) marks none, since marking every hour says nothing.
 */
export function cheapestHours(rows: ForecastRow[], fromHour: number): Set<number> {
  const days = new Map<string, Array<{ t: number; price: number }>>()
  for (const r of rows) {
    const price = r.price ?? r.forecast
    if (r.t < fromHour || price == null) continue
    const day = getMarketDateString(r.t)
    days.set(day, [...(days.get(day) ?? []), { t: r.t, price }])
  }
  const low = new Set<number>()
  for (const priced of days.values()) {
    if (priced.length <= LOW_HOURS_PER_DAY) continue
    priced.sort((a, b) => a.price - b.price).slice(0, LOW_HOURS_PER_DAY).forEach((h) => low.add(h.t))
  }
  return low
}

/** Joins consecutive hours into [start, end) spans, so the chart draws one shaded area per run. */
export function hourSpans(hours: Set<number>): Array<[number, number]> {
  const spans: Array<[number, number]> = []
  for (const t of [...hours].sort((a, b) => a - b)) {
    const last = spans[spans.length - 1]
    if (last && last[1] === t) last[1] = t + HOUR_MS
    else spans.push([t, t + HOUR_MS])
  }
  return spans
}

export interface AccuracyStats {
  /** Hours with both a forecast and a recorded price. */
  hours: number
  /** Mean absolute miss, $/MWh. */
  averageMiss: number | null
  /** Root mean square miss, $/MWh: the same measure as Card 1's typical error. */
  typicalError: number | null
  biggestMiss: { miss: number; t: number } | null
}

export function accuracyStats(points: ForecastAccuracyPoint[]): AccuracyStats {
  if (!points.length) return { hours: 0, averageMiss: null, typicalError: null, biggestMiss: null }
  let abs = 0
  let sq = 0
  let biggest: { miss: number; t: number } | null = null
  for (const p of points) {
    const miss = Math.abs(p.predictedPrice - p.actualPrice)
    abs += miss
    sq += miss * miss
    if (!biggest || miss > biggest.miss) biggest = { miss, t: parseIsoDate(p.intervalStartUtc).getTime() }
  }
  return { hours: points.length, averageMiss: abs / points.length, typicalError: Math.sqrt(sq / points.length), biggestMiss: biggest }
}

/** One past hour on the deviation chart: forecast minus the day-ahead price, null when not checked. */
export interface DeviationRow { t: number; deviation: number | null; forecast: number | null; actual: number | null }

/**
 * Every hour from `from` to `to`, so the bars are evenly spaced and gaps show as gaps.
 * Positive deviation: the forecast was too high. Negative: too low.
 */
export function deviationRows(points: ForecastAccuracyPoint[], from: number, to: number): DeviationRow[] {
  const byT = new Map(points.map((p) => [parseIsoDate(p.intervalStartUtc).getTime(), p]))
  const rows: DeviationRow[] = []
  for (let t = Math.ceil(from / HOUR_MS) * HOUR_MS; t <= to; t += HOUR_MS) {
    const p = byT.get(t)
    rows.push(p
      ? { t, deviation: p.predictedPrice - p.actualPrice, forecast: p.predictedPrice, actual: p.actualPrice }
      : { t, deviation: null, forecast: null, actual: null })
  }
  return rows
}

/** Tick positions on whole hours, with a step that gives at most 7 labels. */
export function timeTicks(min: number, max: number): number[] {
  const span = max - min
  const step = [1, 2, 3, 6, 12, 24, 48].map((h) => h * HOUR_MS).find((s) => span / s <= 7) ?? 48 * HOUR_MS
  const ticks: number[] = []
  for (let t = Math.ceil(min / step) * step; t <= max; t += step) ticks.push(t)
  return ticks
}

/**
 * The card's status badge. A forecast run older than `staleAfterMs` is cached, and so is data whose
 * background refresh has failed for two polls in a row (quiet refreshes keep the old value, so
 * `error` alone never shows it). `loadAgesMs` is how long ago each source last loaded.
 */
export function cardStatus(p: {
  failed: boolean
  runAgeMs: number | null
  loadAgesMs: Array<number | null>
  staleAfterMs: number
  refreshMs: number
}): "live" | "cached" | "unavailable" {
  if (p.failed || p.runAgeMs == null) return "unavailable"
  const refreshStalled = p.loadAgesMs.some((age) => age != null && age > 2 * p.refreshMs)
  return p.runAgeMs > p.staleAfterMs || refreshStalled ? "cached" : "live"
}
