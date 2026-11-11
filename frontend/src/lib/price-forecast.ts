import type { ForecastAccuracyPoint, ForecastModel, PriceForecastPoint, PricePoint } from "./api"
import { getMarketDateString, getMarketHoursMinutes, parseIsoDate } from "./date-utils"

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
  /** Mean of forecast minus price, $/MWh. Above 0: the forecast runs high. */
  bias: number | null
}

/** The points for hours that start at `from` or later: the page's 7d or 30d window. */
export const withinWindow = (points: ForecastAccuracyPoint[], from: number) =>
  points.filter((p) => parseIsoDate(p.intervalStartUtc).getTime() >= from)

export function accuracyStats(points: ForecastAccuracyPoint[]): AccuracyStats {
  if (!points.length) return { hours: 0, averageMiss: null, typicalError: null, bias: null }
  let abs = 0
  let sq = 0
  let sum = 0
  for (const p of points) {
    const miss = p.predictedPrice - p.actualPrice
    abs += Math.abs(miss)
    sq += miss * miss
    sum += miss
  }
  const n = points.length
  return { hours: n, averageMiss: abs / n, typicalError: Math.sqrt(sq / n), bias: sum / n }
}

/** One axis range for both forecast and price, rounded out to a tidy step, so y = x runs at 45°. */
export function scatterDomain(points: ForecastAccuracyPoint[]): [number, number] | null {
  if (!points.length) return null
  const values = points.flatMap((p) => [p.predictedPrice, p.actualPrice])
  const lo = Math.min(...values)
  const hi = Math.max(...values)
  const step = [1, 2, 5, 10, 20, 50, 100, 200, 500].find((s) => (hi - lo) / s <= 6) ?? 1000
  const min = Math.floor(lo / step) * step
  const max = Math.ceil(hi / step) * step
  return [min, max > min ? max : min + step]
}

/** An hour of the day needs this many points before its average is shown. */
export const MIN_HOUR_POINTS = 3

export interface HourMissRow {
  /** CT hour of the day, 0-23. */
  hour: number
  /** Null when the hour has fewer than MIN_HOUR_POINTS points. */
  averageMiss: number | null
  bias: number | null
  count: number
}

/** Average miss and bias for each CT hour of the day: where in the day the forecast fails. */
export function missByHour(points: ForecastAccuracyPoint[]): HourMissRow[] {
  const sums = Array.from({ length: 24 }, () => ({ abs: 0, sum: 0, count: 0 }))
  for (const p of points) {
    const s = sums[getMarketHoursMinutes(p.intervalStartUtc).hours]
    const miss = p.predictedPrice - p.actualPrice
    s.abs += Math.abs(miss)
    s.sum += miss
    s.count++
  }
  return sums.map(({ abs, sum, count }, hour) => count < MIN_HOUR_POINTS
    ? { hour, averageMiss: null, bias: null, count }
    : { hour, averageMiss: abs / count, bias: sum / count, count })
}

/** The running sum of forecast minus price, hour by hour. Rising: the forecast keeps running high. */
export function runningMiss(points: ForecastAccuracyPoint[]): Array<{ t: number; miss: number; total: number }> {
  let total = 0
  return points
    .map((p) => ({ t: parseIsoDate(p.intervalStartUtc).getTime(), miss: p.predictedPrice - p.actualPrice }))
    .sort((a, b) => a.t - b.t)
    .map((r) => ({ ...r, total: (total += r.miss) }))
}

/**
 * A CT day needs this many hours with both a forecast and a price to be checked. It allows the 23- and
 * 25-hour DST days and a few missing hours.
 */
// ponytail: fixed cut-off; make it relative to the day's length if DST days look wrong.
export const MIN_DAY_HOURS = 20

export interface CheapHoursDay {
  /** CT date, YYYY-MM-DD. */
  day: string
  /** Picked hours that were really among the day's cheapest. */
  hits: number
  /** Mean price over the picked hours minus the mean over the really cheapest hours, $/MWh. 0 or more. */
  extraCost: number
  /** Start times of the hours the forecast picked, and of the really cheapest hours. */
  picked: number[]
  best: number[]
}

/**
 * Did the forecast pick each day's cheapest hours, as a plan would? Per CT day: the LOW_HOURS_PER_DAY
 * hours with the lowest forecast against the ones with the lowest price.
 */
export function cheapHoursCheck(points: ForecastAccuracyPoint[]): { days: CheapHoursDay[]; hits: number; picks: number } {
  const byDay = new Map<string, ForecastAccuracyPoint[]>()
  for (const p of points) {
    const day = getMarketDateString(p.intervalStartUtc)
    byDay.set(day, [...(byDay.get(day) ?? []), p])
  }
  const lowest = (hours: ForecastAccuracyPoint[], key: (p: ForecastAccuracyPoint) => number) =>
    [...hours].sort((a, b) => key(a) - key(b)).slice(0, LOW_HOURS_PER_DAY)
  const meanPrice = (hours: ForecastAccuracyPoint[]) => hours.reduce((sum, p) => sum + p.actualPrice, 0) / hours.length
  const times = (hours: ForecastAccuracyPoint[]) => hours.map((p) => parseIsoDate(p.intervalStartUtc).getTime()).sort((a, b) => a - b)

  const days: CheapHoursDay[] = []
  for (const [day, hours] of [...byDay].sort(([a], [b]) => a.localeCompare(b))) {
    if (hours.length < MIN_DAY_HOURS) continue
    const picked = lowest(hours, (p) => p.predictedPrice)
    const best = lowest(hours, (p) => p.actualPrice)
    // A pick counts when its price is as low as the dearest of the really cheapest hours, so ties count.
    const cutoff = best[best.length - 1].actualPrice
    days.push({
      day,
      hits: picked.filter((p) => p.actualPrice <= cutoff).length,
      extraCost: meanPrice(picked) - meanPrice(best),
      picked: times(picked),
      best: times(best),
    })
  }
  return { days, hits: days.reduce((sum, d) => sum + d.hits, 0), picks: days.length * LOW_HOURS_PER_DAY }
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
