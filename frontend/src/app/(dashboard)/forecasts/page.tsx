"use client"

import * as React from "react"
import { AlertTriangle, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { unwrap, useLoad } from "@/components/home/use-load"
import { AccuracyCard } from "@/components/forecasts/AccuracyCard"
import { ForecastCard } from "@/components/forecasts/ForecastCard"
import { latestRun, STALE_AFTER_MS, useForecastCard } from "@/components/forecasts/use-forecast-card"
import { Segmented } from "@/components/ui/segmented"
import { facilityApi, priceApi, type ForecastModel } from "@/lib/api"
import { formatRelativeAgo, parseIsoDate } from "@/lib/date-utils"
import { FORECAST_HOURS, HOUR_MS, MODEL_OPTIONS, withinWindow } from "@/lib/price-forecast"
import { cn } from "@/lib/utils"

const DEFAULT_ZONE = "LZ_NORTH"

type AnalysisWindow = "7d" | "30d"
const WINDOW_OPTIONS = [{ value: "7d", label: "7d" }, { value: "30d", label: "30d" }] as const
const WINDOW_DAYS: Record<AnalysisWindow, number> = { "7d": 7, "30d": 30 }

/** Always the longest window (the backend's limit); the 7d window filters it, so switching is instant. */
function loadAccuracy(zone: string, model: ForecastModel) {
  return unwrap(priceApi.getForecastAccuracy({ location: zone, model, days: WINDOW_DAYS["30d"] }), "Unable to load forecast accuracy")
}

export default function ForecastsPage() {
  const facility = useLoad("facility", () => unwrap(facilityApi.get(), "Unable to load the facility"))
  // Prices exist for the facility's zone only. If the facility cannot load, the default zone still gives a page.
  const zone = facility.loading ? null : facility.data?.location ?? DEFAULT_ZONE

  // The toolbar's model switch drives every card on this page.
  const [model, setModel] = React.useState<ForecastModel>("lear")
  const [analysisWindow, setAnalysisWindow] = React.useState<AnalysisWindow>("7d")
  const forecast = useForecastCard(zone, model)
  const { nowMs, learForecast } = forecast

  // Both models load up front, so a switch is instant.
  const learAccuracy = useLoad(zone && `lear-accuracy:${zone}`, () => loadAccuracy(zone ?? DEFAULT_ZONE, "lear"))
  const lstmAccuracy = useLoad(zone && `lstm-accuracy:${zone}`, () => loadAccuracy(zone ?? DEFAULT_ZONE, "lstm"))
  const shownAccuracy = model === "lear" ? learAccuracy : lstmAccuracy
  const accuracyLoading = !zone || shownAccuracy.loading
  const from = nowMs - WINDOW_DAYS[analysisWindow] * 24 * HOUR_MS
  const accuracyPoints = shownAccuracy.data?.points
  const points = React.useMemo(() => withinWindow(accuracyPoints ?? [], from), [accuracyPoints, from])

  const busy = forecast.loading || accuracyLoading
  const refresh = () => {
    forecast.reload()
    learAccuracy.reload()
    lstmAccuracy.reload()
  }

  // The alert is about LEAR whichever model the card shows: plans use it.
  const learPoints = learForecast.data?.points ?? []
  const learRun = latestRun(learPoints)
  const learStale = learRun != null && nowMs - parseIsoDate(learRun).getTime() > STALE_AFTER_MS
  const shortCoverage = !learForecast.loading && !learForecast.error && learPoints.length < FORECAST_HOURS

  return (
    <div id="forecasts-root" className="min-w-0 space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Price forecast</h1>

      {/* One row at every width: the two switches stay together on the left, Refresh on the right. */}
      <div className="flex items-center justify-between gap-2">
        <div className="flex min-w-0 flex-nowrap items-center gap-2">
          <Segmented label="Forecast model" value={model} onChange={setModel} options={MODEL_OPTIONS} />
          <Segmented label="Window" value={analysisWindow} onChange={setAnalysisWindow} options={WINDOW_OPTIONS} />
        </div>
        <Button variant="ghost" size="icon" className="size-9" aria-label="Refresh" title="Refresh" onClick={refresh} disabled={busy}>
          <RefreshCw className={cn("size-4", busy && "animate-spin")} aria-hidden="true" />
        </Button>
      </div>

      {(learStale || shortCoverage) && (
        <Alert variant="warning">
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          <AlertDescription className="min-w-0 break-words">
            {learStale && learRun && <>The LEAR forecast was made {formatRelativeAgo(learRun, nowMs)}. New plans use this older forecast. </>}
            {shortCoverage && <>LEAR covers {learPoints.length} of the next {FORECAST_HOURS} hours. Plans price the rest from LSTM or from estimates.</>}
          </AlertDescription>
        </Alert>
      )}

      <ForecastCard {...forecast.card} />

      <AccuracyCard
        model={model}
        points={points}
        from={from}
        nowMs={nowMs}
        loading={accuracyLoading}
        error={shownAccuracy.error}
        onRetry={shownAccuracy.reload}
      />
    </div>
  )
}
