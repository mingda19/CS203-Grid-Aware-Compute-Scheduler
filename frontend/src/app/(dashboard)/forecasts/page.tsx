"use client"

import * as React from "react"
import { AlertTriangle, GitCompareArrows, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Toggle } from "@/components/ui/toggle"
import { unwrap, useLoad } from "@/components/home/use-load"
import { ForecastCard } from "@/components/forecasts/ForecastCard"
import { facilityApi, priceApi, type ForecastModel } from "@/lib/api"
import { currentMarketHourStart, formatRelativeAgo, parseIsoDate } from "@/lib/date-utils"
import { buildForecastRows, cheapestHours, FORECAST_HOURS, HOUR_MS } from "@/lib/price-forecast"
import { cn } from "@/lib/utils"

const DEFAULT_ZONE = "LZ_NORTH"
/** Days of recorded prices the page loads. Later cards use the whole week. */
const HISTORY_DAYS = 7
/** Past hours Card 1 shows before now, for context. */
const PAST_HOURS = 24
/** A forecast run older than this counts as delayed. The models run once a day. */
const STALE_AFTER_MS = 26 * HOUR_MS

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

export default function ForecastsPage() {
  const facility = useLoad("facility", () => unwrap(facilityApi.get(), "Unable to load the facility"))
  // Prices exist for the facility's zone only. If the facility cannot load, the default zone still gives a page.
  const zone = facility.loading ? null : facility.data?.location ?? DEFAULT_ZONE
  const [compare, setCompare] = React.useState(false)

  const history = useLoad(zone && `history:${zone}`, () => loadHistory(zone ?? DEFAULT_ZONE))
  const lear = useLoad(zone && `lear:${zone}`, () => loadForecast(zone ?? DEFAULT_ZONE, "lear"))
  const lstm = useLoad(zone && compare ? `lstm:${zone}` : null, () => loadForecast(zone ?? DEFAULT_ZONE, "lstm"))

  const [nowMs, setNowMs] = React.useState(() => Date.now())
  React.useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const loading = !zone || history.loading || lear.loading
  const refresh = () => {
    history.reload()
    lear.reload()
    if (compare) lstm.reload()
  }

  const from = nowMs - PAST_HOURS * HOUR_MS
  const to = nowMs + FORECAST_HOURS * HOUR_MS
  const rows = React.useMemo(
    () => buildForecastRows(history.data?.points ?? [], lear.data?.points ?? [], compare ? lstm.data?.points ?? null : null, from, to),
    [history.data, lear.data, lstm.data, compare, from, to],
  )
  const hourStart = currentMarketHourStart(nowMs).getTime()
  const lowHours = React.useMemo(() => cheapestHours(rows, hourStart), [rows, hourStart])

  // The latest LEAR run decides the status and the alert.
  const learPoints = lear.data?.points ?? []
  const generatedAt = learPoints.reduce<string | null>(
    (latest, p) => (latest == null || parseIsoDate(p.generatedAt) > parseIsoDate(latest) ? p.generatedAt : latest),
    null,
  )
  const stale = generatedAt != null && nowMs - parseIsoDate(generatedAt).getTime() > STALE_AFTER_MS
  const status = lear.error || !generatedAt ? "unavailable" : stale ? "cached" : "live"
  const shortCoverage = !lear.loading && !lear.error && learPoints.length < FORECAST_HOURS

  return (
    <div id="forecasts-root" className="min-w-0 space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Price forecast</h1>

      <div className="flex items-center justify-between gap-2">
        <Toggle
          size="sm"
          pressed={compare}
          onPressedChange={setCompare}
          aria-label="Compare with LSTM"
          title="Compare with LSTM"
          className="data-[state=on]:bg-primary/15 data-[state=on]:text-foreground"
        >
          <GitCompareArrows aria-hidden="true" />
          Compare LSTM
        </Toggle>
        <Button variant="ghost" size="icon" className="size-9" aria-label="Refresh" title="Refresh" onClick={refresh} disabled={loading}>
          <RefreshCw className={cn("size-4", loading && "animate-spin")} aria-hidden="true" />
        </Button>
      </div>

      {(stale || shortCoverage) && (
        <Alert variant="warning">
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          <AlertDescription className="min-w-0 break-words">
            {stale && generatedAt && <>The LEAR forecast was made {formatRelativeAgo(generatedAt, nowMs)}. New plans use this older forecast. </>}
            {shortCoverage && <>LEAR covers {learPoints.length} of the next {FORECAST_HOURS} hours. Plans price the rest from LSTM or from estimates.</>}
          </AlertDescription>
        </Alert>
      )}

      <ForecastCard
        zone={zone ?? DEFAULT_ZONE}
        rows={rows}
        nowMs={nowMs}
        from={from}
        to={to}
        run={generatedAt ? { generatedAt } : null}
        status={status}
        lowHours={lowHours}
        showCompare={compare && lstm.data != null}
        loading={loading}
        error={history.error && lear.error}
        onRetry={refresh}
      />
    </div>
  )
}
