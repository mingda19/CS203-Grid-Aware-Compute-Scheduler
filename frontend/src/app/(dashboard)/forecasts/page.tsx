"use client"

import * as React from "react"
import { AlertTriangle, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { unwrap, useLoad } from "@/components/home/use-load"
import { AccuracyCard } from "@/components/forecasts/AccuracyCard"
import { ForecastCard } from "@/components/forecasts/ForecastCard"
import { HISTORY_DAYS, latestRun, STALE_AFTER_MS, useForecastCard } from "@/components/forecasts/use-forecast-card"
import { facilityApi, priceApi, type ForecastModel } from "@/lib/api"
import { formatRelativeAgo, parseIsoDate } from "@/lib/date-utils"
import { FORECAST_HOURS } from "@/lib/price-forecast"
import { cn } from "@/lib/utils"

const DEFAULT_ZONE = "LZ_NORTH"

function loadAccuracy(zone: string, model: ForecastModel) {
  return unwrap(priceApi.getForecastAccuracy({ location: zone, model, days: HISTORY_DAYS }), "Unable to load forecast accuracy")
}

export default function ForecastsPage() {
  const facility = useLoad("facility", () => unwrap(facilityApi.get(), "Unable to load the facility"))
  // Prices exist for the facility's zone only. If the facility cannot load, the default zone still gives a page.
  const zone = facility.loading ? null : facility.data?.location ?? DEFAULT_ZONE

  const forecast = useForecastCard(zone)
  const { nowMs, learForecast } = forecast

  // The accuracy card has its own model switch. Both models load up front, so a switch is instant.
  const [accuracyModel, setAccuracyModel] = React.useState<ForecastModel>("lear")
  const learAccuracy = useLoad(zone && `lear-accuracy:${zone}`, () => loadAccuracy(zone ?? DEFAULT_ZONE, "lear"))
  const lstmAccuracy = useLoad(zone && `lstm-accuracy:${zone}`, () => loadAccuracy(zone ?? DEFAULT_ZONE, "lstm"))
  const shownAccuracy = accuracyModel === "lear" ? learAccuracy : lstmAccuracy
  const accuracyLoading = !zone || shownAccuracy.loading

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

      <div className="flex items-center justify-end gap-2">
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
        model={accuracyModel}
        onModelChange={setAccuracyModel}
        data={shownAccuracy.data}
        days={HISTORY_DAYS}
        nowMs={nowMs}
        loading={accuracyLoading}
        error={shownAccuracy.error}
        onRetry={shownAccuracy.reload}
      />
    </div>
  )
}
