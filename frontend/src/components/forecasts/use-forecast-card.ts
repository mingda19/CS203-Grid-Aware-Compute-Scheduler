"use client"

import * as React from "react"
import { unwrap, useLoad } from "@/components/home/use-load"
import type { PastWindow } from "@/components/forecasts/ForecastCard"
import type { DataStatusType } from "@/components/ui/data-status-badge"
import { priceApi, type ForecastModel, type PriceForecastPoint } from "@/lib/api"
import { currentMarketHourStart, parseIsoDate } from "@/lib/date-utils"
import { buildForecastRows, cheapestHours, FORECAST_HOURS, HOUR_MS } from "@/lib/price-forecast"

const DEFAULT_ZONE = "LZ_NORTH"
/** Days of recorded prices loaded: enough for the 7d window. */
export const HISTORY_DAYS = 7
const PAST_HOURS: Record<PastWindow, number> = { "24h": 24, "7d": HISTORY_DAYS * 24 }
/** A forecast run older than this counts as delayed. The models run once a day. */
export const STALE_AFTER_MS = 26 * HOUR_MS

const isoDate = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** Recorded prices from a week ago up to the hours the day-ahead market has already cleared. */
function loadHistory(zone: string) {
  const now = Date.now()
  return unwrap(
    priceApi.getHistory({ location: zone, startDate: isoDate(now - HISTORY_DAYS * 24 * HOUR_MS), endDate: isoDate(now + 3 * 24 * HOUR_MS) }),
    "Unable to load prices",
  )
}

function loadForecast(zone: string, model: ForecastModel) {
  return unwrap(priceApi.getForecast({ location: zone, hours: FORECAST_HOURS, model }), "Unable to load the forecast")
}

/** When the newest of these forecast points was made. */
export function latestRun(points: PriceForecastPoint[]): string | null {
  return points.reduce<string | null>(
    (latest, p) => (latest == null || parseIsoDate(p.generatedAt) > parseIsoDate(latest) ? p.generatedAt : latest),
    null,
  )
}

/**
 * Loads and shapes everything `ForecastCard` needs, so Home and the Forecast page show the same card.
 * `zone` is null while the facility loads. Both models load up front, so the model switch is instant.
 * Pass `pageModel` when the page owns the model switch; the card then hides its own.
 */
export function useForecastCard(zone: string | null, pageModel?: ForecastModel) {
  const [ownModel, setModel] = React.useState<ForecastModel>("lear")
  const model = pageModel ?? ownModel
  const [pastWindow, setPastWindow] = React.useState<PastWindow>("24h")

  const history = useLoad(zone && `history:${zone}`, () => loadHistory(zone ?? DEFAULT_ZONE))
  const learForecast = useLoad(zone && `lear:${zone}`, () => loadForecast(zone ?? DEFAULT_ZONE, "lear"))
  const lstmForecast = useLoad(zone && `lstm:${zone}`, () => loadForecast(zone ?? DEFAULT_ZONE, "lstm"))
  const forecast = model === "lear" ? learForecast : lstmForecast

  const [nowMs, setNowMs] = React.useState(() => Date.now())
  React.useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const from = nowMs - PAST_HOURS[pastWindow] * HOUR_MS
  const to = nowMs + FORECAST_HOURS * HOUR_MS
  const historyPoints = history.data?.points
  const forecastPoints = forecast.data?.points
  const rows = React.useMemo(
    () => buildForecastRows(historyPoints ?? [], forecastPoints ?? [], from, to),
    [historyPoints, forecastPoints, from, to],
  )
  const hourStart = currentMarketHourStart(nowMs).getTime()
  const lowHours = React.useMemo(() => cheapestHours(rows, hourStart), [rows, hourStart])

  // The badge follows the shown model.
  const run = latestRun(forecastPoints ?? [])
  const stale = run != null && nowMs - parseIsoDate(run).getTime() > STALE_AFTER_MS
  const status: DataStatusType = forecast.error || !run ? "unavailable" : stale ? "cached" : "live"

  const loading = !zone || history.loading || forecast.loading
  const reload = () => {
    history.reload()
    learForecast.reload()
    lstmForecast.reload()
  }

  return {
    /** Spread onto `ForecastCard`. */
    card: {
      zone: zone ?? DEFAULT_ZONE,
      rows,
      nowMs,
      from,
      to,
      model,
      onModelChange: pageModel ? undefined : setModel,
      pastWindow,
      onPastWindowChange: setPastWindow,
      run: run ? { generatedAt: run } : null,
      status,
      lowHours,
      loading,
      error: history.error && forecast.error,
      onRetry: reload,
    },
    /** LEAR's load, whatever the card shows: plans use LEAR, so page alerts read it. */
    learForecast,
    nowMs,
    loading,
    reload,
  }
}
