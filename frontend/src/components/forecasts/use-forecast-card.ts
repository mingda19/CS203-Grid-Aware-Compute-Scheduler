"use client"

import * as React from "react"
import { unwrap, useLoad } from "@/components/home/use-load"
import type { PastWindow } from "@/components/forecasts/ForecastCard"
import type { DataStatusType } from "@/components/ui/data-status-badge"
import { priceApi, type ForecastModel, type PriceForecastPoint } from "@/lib/api"
import { currentMarketHourStart, parseIsoDate } from "@/lib/date-utils"
import { buildForecastRows, cardStatus, cheapestHours, FORECAST_HOURS, HOUR_MS } from "@/lib/price-forecast"

export const DEFAULT_ZONE = "LZ_NORTH"
/** Days of recorded prices loaded: enough for the 7d window. */
export const HISTORY_DAYS = 7
const PAST_HOURS: Record<PastWindow, number> = { "24h": 24, "7d": HISTORY_DAYS * 24 }
/** A forecast run older than this counts as delayed. The models run once a day. */
export const STALE_AFTER_MS = 26 * HOUR_MS
/** Prices and forecasts reload quietly this often, so a page left open stays current. Matches Home's "Price now". */
export const REFRESH_MS = 15 * 60_000

const isoDate = (ms: number) => new Date(ms).toISOString().slice(0, 10)

/** Recorded prices from a week ago up to the hours the day-ahead market has already cleared. */
function loadHistory(zone: string) {
  const now = Date.now()
  return unwrap(
    priceApi.getHistory({ location: zone, startDate: isoDate(now - HISTORY_DAYS * 24 * HOUR_MS), endDate: isoDate(now + 3 * 24 * HOUR_MS) }),
    "Unable to load prices",
  )
}

/** What each past hour in the 7d window had been forecast, for the past-forecast overlay. */
function loadPastForecasts(zone: string, model: ForecastModel) {
  return unwrap(priceApi.getForecastAccuracy({ location: zone, model, days: HISTORY_DAYS }), "Unable to load past forecasts")
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
  const [showPast, setShowPast] = React.useState(false)

  const history = useLoad(zone && `history:${zone}`, () => loadHistory(zone ?? DEFAULT_ZONE), REFRESH_MS)
  const learForecast = useLoad(zone && `lear:${zone}`, () => loadForecast(zone ?? DEFAULT_ZONE, "lear"), REFRESH_MS)
  const lstmForecast = useLoad(zone && `lstm:${zone}`, () => loadForecast(zone ?? DEFAULT_ZONE, "lstm"), REFRESH_MS)
  const forecast = model === "lear" ? learForecast : lstmForecast
  // Loads only once the overlay is turned on, and again for each model.
  const past = useLoad(showPast && zone ? `past:${model}:${zone}` : null, () => loadPastForecasts(zone ?? DEFAULT_ZONE, model), REFRESH_MS)

  const [nowMs, setNowMs] = React.useState(() => Date.now())
  React.useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const from = nowMs - PAST_HOURS[pastWindow] * HOUR_MS
  const to = nowMs + FORECAST_HOURS * HOUR_MS
  const historyPoints = history.data?.points
  const forecastPoints = forecast.data?.points
  // Old data stays while a new model loads, so check the model to keep the other model's line off the chart.
  const pastPoints = showPast && past.data?.modelKind === model ? past.data.points : undefined
  const rows = React.useMemo(
    () => buildForecastRows(historyPoints ?? [], forecastPoints ?? [], from, to, pastPoints),
    [historyPoints, forecastPoints, from, to, pastPoints],
  )
  const hourStart = currentMarketHourStart(nowMs).getTime()
  const lowHours = React.useMemo(() => cheapestHours(rows, hourStart), [rows, hourStart])

  // The badge follows the shown model.
  const run = latestRun(forecastPoints ?? [])
  const status: DataStatusType = cardStatus({
    failed: !!(forecast.error || history.error),
    runAgeMs: run == null ? null : nowMs - parseIsoDate(run).getTime(),
    loadAgesMs: [history, forecast].map((l) => (l.updatedAt == null ? null : nowMs - l.updatedAt)),
    staleAfterMs: STALE_AFTER_MS,
    refreshMs: REFRESH_MS,
  })

  const loading = !zone || history.loading || forecast.loading
  const reload = () => {
    history.reload()
    learForecast.reload()
    lstmForecast.reload()
    if (showPast) past.reload()
  }
  // What failed while the rest still draws, so the card names it instead of dropping it silently.
  const failed = [
    history.error && "recorded prices",
    forecast.error && "forecast",
    showPast && past.error && "past forecasts",
  ].filter((s): s is string => !!s)
  // Retry only what failed, so the data that loaded stays.
  const retry = () => {
    for (const l of [history, learForecast, lstmForecast]) if (l.error) l.reload()
    if (showPast && past.error) past.reload()
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
      showPast,
      onShowPastChange: setShowPast,
      run: run ? { generatedAt: run } : null,
      status,
      lowHours,
      loading,
      error: history.error && forecast.error,
      failed,
      onRetry: retry,
    },
    /** LEAR's load, whatever the card shows: plans use LEAR, so page alerts read it. */
    learForecast,
    nowMs,
    loading,
    reload,
  }
}
