"use client"

import * as React from "react"
import {
  Line,
  Area,
  XAxis,
  YAxis,
  CartesianGrid,
  ResponsiveContainer,
  ComposedChart,
  ReferenceArea,
  ReferenceLine,
  Tooltip as RechartsTooltip,
} from "recharts"
import { RefreshCw } from "lucide-react"
import { Card, CardContent, CardFooter, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { priceApi, type PricePoint, type PriceForecastPoint } from "@/lib/api"
import { aggregateConfidence, confidenceBand, type ConfidenceBand } from "@/lib/forecast-confidence"
import { formatMarketDateTime, formatMarketChartTick, marketZoneAbbrev, MARKET_TIMEZONE } from "@/lib/date-utils"

/** How much recorded history the chart shows. The forecast part always covers the next 72 hours. */
export type HorizonPreset = "24h" | "7d"

const HISTORY_DAYS: Record<HorizonPreset, number> = { "24h": 1, "7d": 7 }
/** The chart always loads the longest window. A horizon change only moves the axes. */
const MAX_HISTORY_DAYS = HISTORY_DAYS["7d"]
const AXIS_TWEEN_MS = 450
const FORECAST_HOURS = 72
const HOUR_MS = 3_600_000
const DAY_MS = 24 * HOUR_MS

export interface ForecastTelemetry {
  isLoading: boolean
  avgLmp: number | null
  currentLmp: number | null
  rollingHistoryAvg: number | null
  peakForecastPrice: number | null
  peakForecastTime: string | null
  peakForecastWindow: string | null
  lowestOpportunityPrice: number | null
  lowestOpportunityTime: string | null
  lowestOpportunityWindow: string | null
  avgCleanShare: number | null
  estWindShare: number | null
  estSolarShare: number | null
  maxCleanShare: number | null
  totalIntervals: number
  hubLocation: string
}

export interface UnifiedPriceTimelineProps {
  hubLocation?: string
  onLocationChange?: (location: string) => void
  variant?: "full" | "compact"
  className?: string
  height?: number
  onTelemetryChange?: (telemetry: ForecastTelemetry) => void
}

export interface TimelineDataPoint {
  key: string
  rawUtc: string
  /** The time in milliseconds. The x-axis is numeric, so one hour has the same width everywhere. */
  t: number
  timeLabel: string
  fullTimeLocal: string
  actualPrice: number | null
  forecastPrice: number | null
  forecastUpper: number | null
  forecastLower: number | null
  isForecast: boolean
  /** 0-1 for a forecast interval; null for an actual, or when the model has no error reference. */
  confidenceScore: number | null
  isTransition?: boolean
  cleanSharePercent: number | null
  modelVersion?: string
}

const withZ = (utc: string) => (utc.endsWith("Z") ? utc : `${utc}Z`)

/** Tick positions on whole hours, with a step that gives at most 7 labels. */
function timeTicks(min: number, max: number): number[] {
  const span = max - min
  const step = [1, 2, 3, 6, 12, 24, 48].map((h) => h * HOUR_MS).find((s) => span / s <= 7) ?? 48 * HOUR_MS
  const ticks: number[] = []
  for (let t = Math.ceil(min / step) * step; t <= max; t += step) ticks.push(t)
  return ticks
}

/**
 * Moves a list of numbers to a new target. A change of `animateKey` plays an ease-out move.
 * Any other change of `target` (new data, a clock tick) applies at once.
 */
function useTweened(target: number[], animateKey: string): number[] {
  // `anim` holds the in-between value while a move plays. It is null otherwise, and the target shows.
  const [anim, setAnim] = React.useState<number[] | null>(null)
  const shownRef = React.useRef(target)
  const keyRef = React.useRef(animateKey)

  // A layout effect runs before the browser paints, so the first frame shows the old axes and not the new ones.
  React.useLayoutEffect(() => {
    const keyChanged = keyRef.current !== animateKey
    keyRef.current = animateKey
    if (!keyChanged || window.matchMedia("(prefers-reduced-motion: reduce)").matches) {
      setAnim(null)
      return
    }
    const from = shownRef.current
    const start = performance.now()
    let frame = 0
    const step = (now: number) => {
      const progress = Math.min(1, (now - start) / AXIS_TWEEN_MS)
      if (progress >= 1) {
        setAnim(null)
        return
      }
      const eased = 1 - Math.pow(1 - progress, 3)
      setAnim(target.map((to, i) => from[i] + (to - from[i]) * eased))
      frame = requestAnimationFrame(step)
    }
    setAnim(from)
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [target, animateKey])

  const shown = anim ?? target
  React.useLayoutEffect(() => {
    shownRef.current = shown
  })
  return shown
}

/** The typical gap between forecast points. The strip cells use it as their width. */
function intervalMs(times: number[]): number {
  const gaps = times.slice(1).map((t, i) => t - times[i]).filter((g) => g > 0).sort((a, b) => a - b)
  return gaps.length ? gaps[Math.floor(gaps.length / 2)] : HOUR_MS
}

const confidenceText = (score: number | null | undefined) => {
  const band = confidenceBand(score)
  return band === "Not available" ? band : `${band} (${Math.round((score as number) * 100)}%)`
}

// Each confidence band has its own fill pattern. Colour is never the only cue.
const BANDS: Array<{ band: ConfidenceBand; label: string; pattern: string }> = [
  { band: "High", label: "High", pattern: "high" },
  { band: "Moderate", label: "Moderate", pattern: "moderate" },
  { band: "Low", label: "Low", pattern: "low" },
  { band: "Not available", label: "No score", pattern: "none" },
]

function ConfidencePatterns({ id }: { id: string }) {
  return (
    <>
      <pattern id={`${id}-high`} width="6" height="6" patternUnits="userSpaceOnUse">
        <rect width="6" height="6" fill="var(--primary)" />
      </pattern>
      <pattern id={`${id}-moderate`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
        <line x1="0" y1="0" x2="0" y2="6" stroke="var(--primary)" strokeWidth="3" />
      </pattern>
      <pattern id={`${id}-low`} width="6" height="6" patternUnits="userSpaceOnUse">
        <circle cx="3" cy="3" r="1.2" fill="var(--primary)" />
      </pattern>
      <pattern id={`${id}-none`} width="5" height="5" patternUnits="userSpaceOnUse">
        <path d="M0 0L5 5M5 0L0 5" stroke="var(--muted-foreground)" strokeWidth="0.8" />
      </pattern>
    </>
  )
}

function ConfidenceSwatch({ pattern }: { pattern: string }) {
  const id = `sw${React.useId().replace(/:/g, "")}`
  return (
    <svg width="16" height="10" aria-hidden="true" className="shrink-0">
      <defs><ConfidencePatterns id={id} /></defs>
      <rect width="16" height="10" rx="2" fill={`url(#${id}-${pattern})`} stroke="var(--border)" />
    </svg>
  )
}

export function UnifiedPriceTimeline({
  hubLocation: controlledHub,
  onLocationChange,
  variant = "full",
  className = "",
  height,
  onTelemetryChange,
}: UnifiedPriceTimelineProps) {
  const [internalHub, setInternalHub] = React.useState("LZ_NORTH")
  const activeHub = controlledHub ?? internalHub
  const chartId = `tl${React.useId().replace(/:/g, "")}`

  const handleHubChange = (newHub: string) => {
    if (onLocationChange) {
      onLocationChange(newHub)
    } else {
      setInternalHub(newHub)
    }
  }

  const [horizon, setHorizon] = React.useState<HorizonPreset>("24h")
  const [historyPoints, setHistoryPoints] = React.useState<PricePoint[]>([])
  const [forecastPoints, setForecastPoints] = React.useState<PriceForecastPoint[]>([])
  const [error, setError] = React.useState<string | null>(null)
  const [refreshTrigger, setRefreshTrigger] = React.useState(0)
  const [loadedQuery, setLoadedQuery] = React.useState<string | null>(null)
  const queryKey = `${activeHub}:${refreshTrigger}`
  const isLoading = loadedQuery !== queryKey
  const [nowMs, setNowMs] = React.useState(() => Date.now())
  React.useEffect(() => {
    const timer = window.setInterval(() => setNowMs(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])

  const historyDays = HISTORY_DAYS[horizon]

  // Fetch both historical settlement actuals and ML forecast
  React.useEffect(() => {
    let cancelled = false

    const now = new Date()
    const startDate = new Date(now)
    startDate.setUTCDate(startDate.getUTCDate() - MAX_HISTORY_DAYS)

    const fetchHistory = priceApi.getHistory({
      location: activeHub,
      startDate: startDate.toISOString().slice(0, 10),
      endDate: now.toISOString().slice(0, 10),
    })

    const fetchForecast = priceApi.getForecast({
      location: activeHub,
      hours: FORECAST_HOURS,
    })

    Promise.allSettled([fetchHistory, fetchForecast])
      .then(([historyRes, forecastRes]) => {
        if (cancelled) return

        setError(null)
        let hist: PricePoint[] = []
        let fore: PriceForecastPoint[] = []

        if (historyRes.status === "fulfilled" && historyRes.value.success && historyRes.value.data) {
          hist = historyRes.value.data.points.filter((p) => p.sppUsdMwh != null)
        }

        if (forecastRes.status === "fulfilled" && forecastRes.value.success && forecastRes.value.data) {
          fore = forecastRes.value.data.points.filter((p) => p.predictedPrice != null)
        }

        setHistoryPoints(hist)
        setForecastPoints(fore)

        if (hist.length === 0 && fore.length === 0) {
          if (historyRes.status === "rejected") {
            setError(historyRes.reason instanceof Error ? historyRes.reason.message : "Failed to load settlement data")
          } else if (forecastRes.status === "rejected") {
            setError(forecastRes.reason instanceof Error ? forecastRes.reason.message : "Failed to load forecast data")
          }
        }
      })
      .catch((err: unknown) => {
        if (!cancelled) {
          setError(err instanceof Error ? err.message : "Failed to load timeline data")
        }
      })
      .finally(() => {
        if (!cancelled) setLoadedQuery(queryKey)
      })

    return () => {
      cancelled = true
    }
  }, [activeHub, queryKey])

  // Construct continuous unified dataset
  const { chartData, forecastCells, axisTarget, summaryStats, telemetry } = React.useMemo(() => {
    const data: TimelineDataPoint[] = []

    // 1. Recorded points inside the longest past window, oldest first. The query reads whole UTC days,
    //    so it can return more than the window. The window is exact.
    //    The chart holds all of them. The selected horizon only sets the axes, the statistics and the counts.
    const windowStart = nowMs - historyDays * DAY_MS
    const sortedHistory = historyPoints
      .map((p) => ({ p, t: new Date(withZ(p.intervalStartUtc)).getTime() }))
      .filter(({ t }) => Number.isFinite(t) && t >= nowMs - MAX_HISTORY_DAYS * DAY_MS)
      .sort((a, b) => a.t - b.t)

    for (const { p, t } of sortedHistory) {
      if (p.sppUsdMwh == null) continue
      const rawUtc = withZ(p.intervalStartUtc)
      data.push({
        key: rawUtc,
        rawUtc,
        t,
        timeLabel: formatMarketChartTick(rawUtc, true),
        fullTimeLocal: formatMarketDateTime(rawUtc),
        actualPrice: Number(p.sppUsdMwh),
        forecastPrice: null,
        forecastUpper: null,
        forecastLower: null,
        isForecast: false,
        confidenceScore: null,
        cleanSharePercent: null,
      })
    }

    // 2. Forecast points strictly after the last recorded point, oldest first
    const latestHistoryTime = data.length > 0 ? data[data.length - 1].t : 0
    const futureForecasts = forecastPoints
      .map((f) => ({ f, t: new Date(withZ(f.intervalStartUtc)).getTime() }))
      .filter(({ f, t }) => f.predictedPrice != null && Number.isFinite(t) && t > latestHistoryTime)
      .sort((a, b) => a.t - b.t)

    // Display-only anchor: the forecast line starts at the last published price.
    // The point stays a settlement point; source forecasts and summary values are unchanged.
    if (data.length > 0 && futureForecasts.length > 0) {
      const lastPublished = data[data.length - 1]
      lastPublished.forecastPrice = lastPublished.actualPrice
      lastPublished.isTransition = true
    }

    // 3. Map future forecast points
    for (const { f, t } of futureForecasts) {
      const rawUtc = withZ(f.intervalStartUtc)
      data.push({
        key: rawUtc,
        rawUtc,
        t,
        timeLabel: formatMarketChartTick(rawUtc, true),
        fullTimeLocal: formatMarketDateTime(rawUtc),
        actualPrice: null,
        forecastPrice: Number(f.predictedPrice),
        forecastUpper: null,
        forecastLower: null,
        isForecast: true,
        confidenceScore: f.confidenceScore ?? null,
        modelVersion: f.modelVersion,
        cleanSharePercent: null,
      })
    }

    // Calculate summary statistics & telemetry
    const historyPrices = sortedHistory.filter(({ t }) => t >= windowStart)
      .map(({ p }) => p.sppUsdMwh).filter((v): v is number => v != null)
    const allPrices = [
      ...historyPrices,
      ...forecastPoints.map((f) => f.predictedPrice).filter((v): v is number => v != null),
    ]

    const rollingHistoryAvg = historyPrices.length > 0
      ? historyPrices.reduce((a, b) => a + b, 0) / historyPrices.length
      : null

    const lastRecorded = sortedHistory.length > 0 ? sortedHistory[sortedHistory.length - 1].p.sppUsdMwh : null
    const currentLmp = lastRecorded != null ? lastRecorded : null

    const avgLmp = allPrices.length > 0 ? allPrices.reduce((a, b) => a + b, 0) / allPrices.length : null

    // Evaluate future actionable intervals (at or after current time), including settled actuals and forecasts
    const futureTimelinePoints = data.filter((d) => d.t >= nowMs && !d.isTransition)
    // Fallback if nowMs is beyond the data window (e.g. historical archive view)
    const actionableSource = futureTimelinePoints.length > 0
      ? futureTimelinePoints
      : data.filter((d) => d.isForecast && !d.isTransition)

    // Impending Peak Spike and Lowest Opportunity Price (lowest upcoming price)
    let peakPrice: number | null = null
    let peakUtc: string | null = null
    let lowestPrice: number | null = null
    let lowestUtc: string | null = null
    for (const pt of actionableSource) {
      const val = pt.actualPrice ?? pt.forecastPrice
      if (val == null) continue
      if (peakPrice == null || val > peakPrice) { peakPrice = val; peakUtc = pt.rawUtc }
      if (lowestPrice == null || val < lowestPrice) { lowestPrice = val; lowestUtc = pt.rawUtc }
    }

    const peakWindow = peakUtc ? `${formatMarketChartTick(peakUtc, true)} ${marketZoneAbbrev(peakUtc)}` : "—"
    const lowestWindow = lowestUtc ? `${formatMarketChartTick(lowestUtc, true)} ${marketZoneAbbrev(lowestUtc)}` : "—"

    const computedTelemetry: ForecastTelemetry = {
      isLoading,
      avgLmp,
      currentLmp,
      rollingHistoryAvg,
      peakForecastPrice: peakPrice,
      peakForecastTime: peakUtc,
      peakForecastWindow: peakWindow,
      lowestOpportunityPrice: lowestPrice,
      lowestOpportunityTime: lowestUtc,
      lowestOpportunityWindow: lowestWindow,
      avgCleanShare: null,
      estWindShare: null,
      estSolarShare: null,
      maxCleanShare: null,
      totalIntervals: data.filter((d) => d.t >= windowStart).length,
      hubLocation: activeHub,
    }

    // The confidence strip: one cell for each forecast interval, centred on its x position.
    const forecastTimes = data.filter((d) => d.isForecast).map((d) => d.t)
    const step = intervalMs(forecastTimes)
    const cells = data
      .filter((d) => d.isForecast)
      .map((d) => ({ x1: d.t - step * 0.46, x2: d.t + step * 0.46, band: confidenceBand(d.confidenceScore) }))

    // The axes show the selected window: [x min, x max, y min, y max].
    // The y-axis leaves room under the lowest price for the strip.
    const visible = data.filter((d) => d.t >= windowStart)
    const shown = visible.flatMap((d) => [d.actualPrice, d.forecastPrice]).filter((v): v is number => v != null)
    const lo = shown.length ? Math.min(...shown) : 0
    const hi = shown.length ? Math.max(...shown) : 1
    const range = Math.max(hi - lo, 1)
    const axisTarget = [
      (visible[0] ?? data[0])?.t ?? 0,
      data[data.length - 1]?.t ?? 1,
      lo - range * (cells.length ? 0.22 : 0.08),
      hi + range * 0.08,
    ]

    return {
      chartData: data,
      forecastCells: cells,
      axisTarget,
      summaryStats: {
        avgLmp: avgLmp ?? 0,
        maxLmp: peakPrice ?? 0,
        maxTime: peakWindow,
        minLmp: lowestPrice ?? 0,
        minTime: lowestWindow,
      },
      telemetry: computedTelemetry,
    }
  }, [historyPoints, forecastPoints, activeHub, isLoading, nowMs, historyDays])

  // A horizon change moves both axes together. Everything else sets them at once.
  const [xMin, xMax, yMin, yMax] = useTweened(axisTarget, horizon)
  const xTicks = React.useMemo(() => (xMax > xMin ? timeTicks(xMin, xMax) : []), [xMin, xMax])

  /**
   * Run-level confidence over the forecast intervals only. Averaged across the intervals that carry
   * a score, with the unscored ones counted separately rather than folded in as zeros, so a horizon
   * the model has little error history for cannot read as confident (contract FR-10, FR-04).
   */
  const forecastConfidence = React.useMemo(
    () => aggregateConfidence(forecastPoints.map((f) => f.confidenceScore)),
    [forecastPoints],
  )

  const onTelemetryChangeRef = React.useRef(onTelemetryChange)
  React.useEffect(() => {
    onTelemetryChangeRef.current = onTelemetryChange
  }, [onTelemetryChange])

  React.useEffect(() => {
    onTelemetryChangeRef.current?.(telemetry)
  }, [telemetry])

  const chartHeight = height ?? (variant === "compact" ? 220 : 380)
  const hasForecast = forecastCells.length > 0
  const lastRecordedUtc = historyPoints[historyPoints.length - 1]?.intervalStartUtc

  return (
    <Card className={`min-w-0 overflow-hidden ${className}`}>
      <CardHeader className="space-y-0 p-4 pb-2 sm:p-5 sm:pb-2">
        {/* The title block shrinks and truncates. The controls never wrap one by one: below sm they move under the title as one group. */}
        <div className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div className="flex min-w-0 flex-1 items-center gap-2">
            <CardTitle className="min-w-0 truncate text-sm font-semibold leading-5" title={`${activeHub} Price`}>
              {activeHub} Price
            </CardTitle>
          </div>

          <div className="flex shrink-0 flex-nowrap items-center gap-2 self-start sm:self-auto">
            {/* The status badge sits in the control group, left of the 24h/7d toggle */}
            {!isLoading && (
              <DataStatusBadge
                size="sm"
                status={error ? "unavailable" : historyPoints.length > 0 || forecastPoints.length > 0 ? "live" : "unavailable"}
                source={`ERCOT settlements and stored model predictions (${activeHub})`}
                updatedAt={lastRecordedUtc ? withZ(lastRecordedUtc) : null}
              />
            )}

            {/* Hub Selector (if not controlled externally) */}
            {!controlledHub && (
              <select
                aria-label="Settlement Hub Location"
                value={activeHub}
                onChange={(e) => handleHubChange(e.target.value)}
                className="h-8 shrink-0 rounded-md border border-input bg-background px-2.5 text-xs font-semibold text-foreground"
              >
                <option value="LZ_NORTH">LZ_NORTH</option>
                <option value="LZ_HOUSTON">LZ_HOUSTON</option>
                <option value="LZ_SOUTH">LZ_SOUTH</option>
                <option value="LZ_WEST">LZ_WEST</option>
              </select>
            )}

            <ToggleGroup
              type="single"
              variant="outline"
              size="sm"
              value={horizon}
              onValueChange={(value) => { if (value) setHorizon(value as HorizonPreset) }}
              aria-label="Past window"
              className="shrink-0 gap-0"
            >
              <ToggleGroupItem value="24h" aria-label="Past 24 hours" className="h-8 rounded-r-none px-2.5 text-xs font-semibold">24h</ToggleGroupItem>
              <ToggleGroupItem value="7d" aria-label="Past 7 days" className="h-8 rounded-l-none border-l-0 px-2.5 text-xs font-semibold">7d</ToggleGroupItem>
            </ToggleGroup>

            <TooltipProvider>
              <Tooltip>
                <TooltipTrigger asChild>
                  <Button
                    variant="ghost"
                    size="icon"
                    className="h-8 w-8 shrink-0"
                    aria-label="Refresh price data"
                    onClick={() => setRefreshTrigger((k) => k + 1)}
                    disabled={isLoading}
                  >
                    <RefreshCw className={`h-4 w-4 ${isLoading ? "animate-spin" : ""}`} aria-hidden="true" />
                  </Button>
                </TooltipTrigger>
                <TooltipContent side="bottom" collisionPadding={8}>Refresh</TooltipContent>
              </Tooltip>
            </TooltipProvider>
          </div>
        </div>
      </CardHeader>

      {/* Main Chart Canvas */}
      <CardContent className="min-w-0 p-2 pt-0 sm:p-4 sm:pt-0">
        {isLoading && chartData.length === 0 ? (
          <Skeleton className="w-full" style={{ height: chartHeight }} role="status" aria-label="Loading price chart" />
        ) : error && chartData.length === 0 ? (
          <div className="py-16 text-center text-sm leading-5 text-destructive-foreground">
            <p>Could not load the price timeline. Use refresh to try again.</p>
          </div>
        ) : chartData.length === 0 ? (
          <div className="py-16 text-center text-sm leading-5 text-muted-foreground">
            <p>No price records found for {activeHub} in this window.</p>
          </div>
        ) : (
          <>
            <div className="min-w-0" style={{ width: "100%", height: chartHeight }}>
              <ResponsiveContainer width="100%" height="100%" minWidth={0}>
                <ComposedChart data={chartData} margin={{ top: 12, right: 24, left: -4, bottom: 4 }}>
                  <defs>
                    <linearGradient id={`${chartId}-area`} x1="0" y1="0" x2="0" y2="1">
                      <stop offset="5%" style={{ stopColor: "var(--primary)", stopOpacity: 0.25 }} />
                      <stop offset="95%" style={{ stopColor: "var(--primary)", stopOpacity: 0 }} />
                    </linearGradient>
                    <ConfidencePatterns id={chartId} />
                  </defs>

                  <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />

                  <XAxis
                    dataKey="t"
                    type="number"
                    scale="time"
                    domain={[xMin, xMax]}
                    allowDataOverflow
                    ticks={xTicks}
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
                    minTickGap={24}
                    tickFormatter={(t: number) => formatMarketChartTick(new Date(t).toISOString(), true)}
                    tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
                  />

                  <YAxis
                    domain={[yMin, yMax]}
                    allowDataOverflow
                    tickLine={false}
                    axisLine={false}
                    tickMargin={6}
                    tickCount={5}
                    tickFormatter={(val: number) => `$${Math.round(val)}`}
                    tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
                  />
                  {/* Hidden axis: it places the confidence strip in a band at the bottom of the plot. */}
                  <YAxis yAxisId="strip" hide domain={[0, 1]} />

                  <RechartsTooltip
                    cursor={{ stroke: "var(--muted-foreground)", strokeWidth: 1, strokeDasharray: "3 3" }}
                    isAnimationActive={false}
                    content={({ active, payload }) => {
                      if (!active || !payload || !payload.length) return null
                      const pt = payload.find((item) => item?.payload != null)?.payload as TimelineDataPoint | undefined
                      if (!pt) return null
                      const isFc = pt.isForecast && !pt.isTransition
                      const price = isFc ? pt.forecastPrice : pt.actualPrice

                      return (
                        <div className="w-max max-w-[calc(100vw-2rem)] space-y-1 rounded-lg border border-border bg-popover p-3 text-xs leading-4 text-popover-foreground shadow-lg">
                          <div className="flex items-center justify-between gap-4">
                            <span className="font-semibold">{pt.timeLabel}</span>
                            <span className="text-muted-foreground">{isFc ? "Forecast" : "Recorded"}</span>
                          </div>
                          <div className="flex items-baseline justify-between gap-4">
                            <span className="text-muted-foreground">Price</span>
                            <span className="font-semibold tabular-nums">
                              ${price?.toFixed(2)} <span className="font-normal text-muted-foreground">/MWh</span>
                            </span>
                          </div>
                          {isFc && (
                            <div className="flex items-baseline justify-between gap-4">
                              <span className="text-muted-foreground">Confidence</span>
                              <span className="font-semibold">{confidenceText(pt.confidenceScore)}</span>
                            </div>
                          )}
                          <div className="text-muted-foreground">{pt.fullTimeLocal}</div>
                        </div>
                      )
                    }}
                  />

                  {/* Confidence strip: one cell per forecast interval, same x positions as the forecast line */}
                  {forecastCells.map((cell) => (
                    <ReferenceArea
                      key={cell.x1}
                      yAxisId="strip"
                      x1={cell.x1}
                      x2={cell.x2}
                      y1={0}
                      y2={0.07}
                      ifOverflow="hidden"
                      fill={`url(#${chartId}-${BANDS.find((b) => b.band === cell.band)?.pattern ?? "none"})`}
                      fillOpacity={1}
                      stroke="none"
                    />
                  ))}

                  {/* The current time: a dotted line with no label */}
                  <ReferenceLine x={nowMs} stroke="var(--foreground)" strokeDasharray="3 3" strokeWidth={1.5} />

                  {/* Recorded: solid line with a soft fill */}
                  <Area
                    type="monotone"
                    dataKey="actualPrice"
                    stroke="var(--primary)"
                    strokeWidth={2}
                    fill={`url(#${chartId}-area)`}
                    connectNulls={false}
                    isAnimationActive={false}
                    activeDot={{ r: 4, stroke: "var(--primary)", strokeWidth: 2, fill: "var(--card)" }}
                    name="Recorded price"
                  />

                  {/* Forecast: dashed line in the same colour */}
                  <Line
                    type="monotone"
                    dataKey="forecastPrice"
                    stroke="var(--primary)"
                    strokeWidth={2}
                    strokeDasharray="6 4"
                    dot={false}
                    activeDot={{ r: 4, stroke: "var(--primary)", strokeWidth: 2, fill: "var(--card)" }}
                    connectNulls={false}
                    isAnimationActive={false}
                    name="Forecast price"
                  />
                </ComposedChart>
              </ResponsiveContainer>
            </div>

            {/* Two legends: the lines on the left, the confidence strip in the right corner. Each wraps by whole item. */}
            <div className="mt-2 flex flex-wrap items-center justify-between gap-x-6 gap-y-1 px-2 text-xs leading-4 text-muted-foreground">
              <ul className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <li className="inline-flex items-center gap-1.5 whitespace-nowrap">
                  <span className="h-0.5 w-5 shrink-0 bg-primary" aria-hidden="true" />Recorded
                </li>
                <li className="inline-flex items-center gap-1.5 whitespace-nowrap">
                  <svg width="20" height="2" aria-hidden="true" className="shrink-0"><line x1="0" y1="1" x2="20" y2="1" stroke="var(--primary)" strokeWidth="2" strokeDasharray="5 3" /></svg>Forecast
                </li>
              </ul>
              {hasForecast && (
                <ul className="ml-auto flex flex-wrap items-center justify-end gap-x-4 gap-y-1">
                  <li className="whitespace-nowrap">Confidence</li>
                  {BANDS.map(({ band, label, pattern }) => (
                    <li key={band} className="inline-flex items-center gap-1.5 whitespace-nowrap">
                      <ConfidenceSwatch pattern={pattern} />{label}
                    </li>
                  ))}
                </ul>
              )}
            </div>
          </>
        )}
      </CardContent>

      {/* Full Variant: Footer Metric Strip */}
      {variant === "full" && (
        <CardFooter className="flex flex-col items-start gap-x-6 gap-y-2 border-t border-border p-3 text-xs leading-4 sm:flex-row sm:flex-wrap sm:items-center sm:justify-between sm:px-5">
          <div className="flex min-w-0 flex-wrap items-center gap-x-4 gap-y-1">
            <div className="whitespace-nowrap">
              <span className="text-muted-foreground">Rolling Mean </span>
              <span className="font-semibold tabular-nums">${summaryStats.avgLmp.toFixed(2)}/MWh</span>
            </div>
            <div>
              <span className="text-muted-foreground">Peak Spike </span>
              <span className="font-semibold tabular-nums">${summaryStats.maxLmp.toFixed(2)}</span>
              <span className="ml-1 text-muted-foreground">({summaryStats.maxTime})</span>
            </div>
            <div>
              <span className="text-muted-foreground">Lowest Valley </span>
              <span className="font-semibold tabular-nums">${summaryStats.minLmp.toFixed(2)}</span>
              <span className="ml-1 text-muted-foreground">({summaryStats.minTime})</span>
            </div>
          </div>

          <div className="min-w-0 text-muted-foreground">
            {chartData.length} intervals in <span className="font-semibold text-foreground">{MARKET_TIMEZONE}</span>.{" "}
            {forecastConfidence.mean != null ? (
              <>
                Model confidence <span className="font-semibold text-foreground">{confidenceText(forecastConfidence.mean)}</span>
                {forecastConfidence.missing > 0 && <> with {forecastConfidence.missing} unscored</>}.
              </>
            ) : forecastConfidence.missing > 0 ? (
              <>Model confidence <span className="font-semibold text-foreground">not available</span>.</>
            ) : null}
          </div>
        </CardFooter>
      )}
    </Card>
  )
}
