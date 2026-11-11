import type { PriceForecastPoint, PricePoint } from "./api"
import { parseIsoDate } from "./date-utils"

export const HOUR_MS = 3_600_000
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
  /** The comparison model's forecast, when it is loaded. */
  compare: number | null
}

/**
 * Merges known prices and forecasts into one row per hour inside [from, to].
 * A known price always wins: forecasts start after the last known hour. That last known hour also
 * carries the forecast values, so the dashed lines start where the solid one ends.
 */
export function buildForecastRows(
  history: PricePoint[],
  forecast: PriceForecastPoint[],
  compare: PriceForecastPoint[] | null,
  from: number,
  to: number,
): ForecastRow[] {
  const rows = new Map<number, ForecastRow>()
  const row = (t: number) => {
    let r = rows.get(t)
    if (!r) {
      r = { t, price: null, forecast: null, band: null, typicalError: null, confidence: null, compare: null }
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
  for (const p of compare ?? []) {
    const t = parseIsoDate(p.intervalStartUtc).getTime()
    if (p.predictedPrice == null || !inWindow(t) || t <= lastKnown) continue
    row(t).compare = p.predictedPrice
  }

  const anchor = rows.get(lastKnown)
  if (anchor?.price != null) {
    const after = [...rows.values()].filter((r) => r.t > lastKnown)
    if (after.some((r) => r.forecast != null)) {
      anchor.forecast = anchor.price
      if (after.some((r) => r.band != null)) anchor.band = [anchor.price, anchor.price]
    }
    if (after.some((r) => r.compare != null)) anchor.compare = anchor.price
  }

  return [...rows.values()].sort((a, b) => a.t - b.t)
}

/** Tick positions on whole hours, with a step that gives at most 7 labels. */
export function timeTicks(min: number, max: number): number[] {
  const span = max - min
  const step = [1, 2, 3, 6, 12, 24, 48].map((h) => h * HOUR_MS).find((s) => span / s <= 7) ?? 48 * HOUR_MS
  const ticks: number[] = []
  for (let t = Math.ceil(min / step) * step; t <= max; t += step) ticks.push(t)
  return ticks
}
