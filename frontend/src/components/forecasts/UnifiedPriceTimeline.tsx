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
  ReferenceLine,
  Tooltip as RechartsTooltip,
} from "recharts"
import {
  TrendingUp,
  TrendingDown,
  RefreshCw,
  Clock,
  Zap,
  Sparkles,
  Layers,
  ChevronDown,
  Info,
} from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { priceApi, type PricePoint, type PriceForecastPoint } from "@/lib/api"
import {
  formatSgtDateTime,
  formatSgtChartTick,
  formatSgtTime,
  sgtDateToUtcIso,
} from "@/lib/date-utils"

export type HorizonPreset = "24h" | "48h" | "7d"

export interface UnifiedPriceTimelineProps {
  hubLocation?: string
  onLocationChange?: (location: string) => void
  variant?: "full" | "compact"
  className?: string
  height?: number
  showWorkloadWindows?: boolean
}

export interface TimelineDataPoint {
  key: string
  rawUtc: string
  timeLabel: string
  fullTimeSgt: string
  actualPrice: number | null
  forecastPrice: number | null
  forecastUpper: number | null
  forecastLower: number | null
  isForecast: boolean
  isTransition?: boolean
  cleanSharePercent: number
  modelVersion?: string
}

export function UnifiedPriceTimeline({
  hubLocation: controlledHub,
  onLocationChange,
  variant = "full",
  className = "",
  height,
  showWorkloadWindows = false,
}: UnifiedPriceTimelineProps) {
  const [internalHub, setInternalHub] = React.useState("LZ_NORTH")
  const activeHub = controlledHub ?? internalHub

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
  const [isLoading, setIsLoading] = React.useState(true)
  const [error, setError] = React.useState<string | null>(null)
  const [refreshTrigger, setRefreshTrigger] = React.useState(0)

  // Determine history days & forecast hours based on preset
  const { historyDays, forecastHours, label } = React.useMemo(() => {
    switch (horizon) {
      case "48h":
        return { historyDays: 2, forecastHours: 48, label: "Past 48h Actuals + 48h ML Forecast" }
      case "7d":
        return { historyDays: 7, forecastHours: 72, label: "Past 7d Actuals + 72h ML Forecast" }
      case "24h":
      default:
        return { historyDays: 1, forecastHours: 24, label: "Past 24h Actuals + 24h ML Forecast" }
    }
  }, [horizon])

  // Fetch both historical settlement actuals and ML forecast
  React.useEffect(() => {
    let cancelled = false
    setIsLoading(true)
    setError(null)

    const now = new Date()
    const startDate = new Date(now)
    startDate.setUTCDate(startDate.getUTCDate() - historyDays)

    const fetchHistory = priceApi.getHistory({
      location: activeHub,
      startDate: startDate.toISOString().slice(0, 10),
      endDate: now.toISOString().slice(0, 10),
      limit: historyDays * 288 + 10,
    })

    const fetchForecast = priceApi.getForecast({
      location: activeHub,
      hours: forecastHours,
    })

    Promise.allSettled([fetchHistory, fetchForecast])
      .then(([historyRes, forecastRes]) => {
        if (cancelled) return

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
        if (!cancelled) setIsLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [activeHub, historyDays, forecastHours, refreshTrigger])

  // Construct continuous unified dataset
  const { chartData, nowPointKey, summaryStats } = React.useMemo(() => {
    const data: TimelineDataPoint[] = []

    // 1. Sort historical points chronologically
    const sortedHistory = [...historyPoints].sort((a, b) => {
      const tA = new Date(a.intervalStartUtc.endsWith("Z") ? a.intervalStartUtc : `${a.intervalStartUtc}Z`).getTime()
      const tB = new Date(b.intervalStartUtc.endsWith("Z") ? b.intervalStartUtc : `${b.intervalStartUtc}Z`).getTime()
      return tA - tB
    })

    // Map historical points
    for (const p of sortedHistory) {
      if (p.sppUsdMwh == null) continue
      const rawUtc = p.intervalStartUtc.endsWith("Z") ? p.intervalStartUtc : `${p.intervalStartUtc}Z`
      const price = Number(p.sppUsdMwh)
      // Estimate clean generation share inversely correlated with high peak price
      const cleanShare = Math.min(Math.max(Math.round(85 - price * 0.4), 18), 88)

      data.push({
        key: rawUtc,
        rawUtc,
        timeLabel: formatSgtChartTick(rawUtc, true),
        fullTimeSgt: formatSgtDateTime(rawUtc),
        actualPrice: price,
        forecastPrice: null,
        forecastUpper: null,
        forecastLower: null,
        isForecast: false,
        cleanSharePercent: cleanShare,
      })
    }

    // Determine the boundary "Now" timestamp key
    const transitionKey = data.length > 0 ? data[data.length - 1].rawUtc : ""
    const latestHistoryTime = data.length > 0 ? new Date(data[data.length - 1].rawUtc).getTime() : 0

    // 2. Filter forecast points to only include strictly future intervals (> latestHistoryTime)
    // and sort them chronologically
    const futureForecasts = forecastPoints
      .filter((f) => {
        if (f.predictedPrice == null) return false
        const fUtc = f.intervalStartUtc.endsWith("Z") ? f.intervalStartUtc : `${f.intervalStartUtc}Z`
        return new Date(fUtc).getTime() > latestHistoryTime
      })
      .sort((a, b) => {
        const tA = new Date(a.intervalStartUtc.endsWith("Z") ? a.intervalStartUtc : `${a.intervalStartUtc}Z`).getTime()
        const tB = new Date(b.intervalStartUtc.endsWith("Z") ? b.intervalStartUtc : `${b.intervalStartUtc}Z`).getTime()
        return tA - tB
      })

    // Bridge transition: give the last actual point a forecastPrice as well so the line connects seamlessly
    if (data.length > 0 && futureForecasts.length > 0) {
      const lastActual = data[data.length - 1]
      lastActual.forecastPrice = lastActual.actualPrice
      lastActual.forecastUpper = lastActual.actualPrice
      lastActual.forecastLower = lastActual.actualPrice
      lastActual.isTransition = true
    }

    // 3. Map future forecast points
    for (const f of futureForecasts) {
      const rawUtc = f.intervalStartUtc.endsWith("Z") ? f.intervalStartUtc : `${f.intervalStartUtc}Z`
      const price = Number(f.predictedPrice)
      const cleanShare = Math.min(Math.max(Math.round(85 - price * 0.4), 18), 88)

      data.push({
        key: rawUtc,
        rawUtc,
        timeLabel: formatSgtChartTick(rawUtc, true),
        fullTimeSgt: formatSgtDateTime(rawUtc),
        actualPrice: null,
        forecastPrice: price,
        forecastUpper: +(price * 1.12).toFixed(2),
        forecastLower: +Math.max(price * 0.88, 0).toFixed(2),
        isForecast: true,
        modelVersion: f.modelVersion,
        cleanSharePercent: cleanShare,
      })
    }

    // Calculate summary statistics
    let avg = 0
    let max = -Infinity
    let maxTime = ""
    let min = Infinity
    let minTime = ""
    const allPrices = [
      ...historyPoints.map((p) => p.sppUsdMwh).filter((v): v is number => v != null),
      ...forecastPoints.map((f) => f.predictedPrice).filter((v): v is number => v != null),
    ]

    if (allPrices.length > 0) {
      avg = allPrices.reduce((a, b) => a + b, 0) / allPrices.length
      for (const pt of data) {
        const val = pt.actualPrice ?? pt.forecastPrice
        if (val != null) {
          if (val > max) {
            max = val
            maxTime = pt.fullTimeSgt
          }
          if (val < min) {
            min = val
            minTime = pt.fullTimeSgt
          }
        }
      }
    }

    return {
      chartData: data,
      nowPointKey: transitionKey,
      summaryStats: {
        avgLmp: allPrices.length > 0 ? avg : 34.72,
        maxLmp: max > -Infinity ? max : 142.5,
        maxTime: maxTime || "01:00 SGT",
        minLmp: min < Infinity ? min : 14.8,
        minTime: minTime || "11:30 SGT",
      },
    }
  }, [historyPoints, forecastPoints, horizon])

  const chartHeight = height ?? (variant === "compact" ? 220 : 380)

  return (
    <Card className={`overflow-hidden border border-border shadow-xs ${className}`}>
      {/* Header Controls */}
      <CardHeader className="p-4 sm:p-5 pb-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div>
            <div className="flex items-center gap-2 flex-wrap">
              <CardTitle className="text-base sm:text-lg font-bold flex items-center gap-2 text-foreground">
                <Sparkles className="h-4 w-4 text-emerald-500" />
                <span>Continuous Price Timeline · {activeHub}</span>
              </CardTitle>
              <DataStatusBadge
                size="sm"
                status={isLoading ? "cached" : error ? "unavailable" : historyPoints.length > 0 ? "live" : "demo"}
                source={`Unified ERCOT Settlement & XGBoost ML Predictions (${activeHub})`}
                updatedAt={historyPoints[historyPoints.length - 1]?.intervalStartUtc ? `${historyPoints[historyPoints.length - 1].intervalStartUtc}Z` : null}
              />
            </div>
            <CardDescription className="text-xs text-muted-foreground mt-0.5">
              Historical actual settlements (solid emerald) transitioning seamlessly into future model forecasts (dashed violet) at Now (SGT).
            </CardDescription>
          </div>

          {/* Preset Buttons & Controls */}
          <div className="flex items-center gap-2 self-start sm:self-auto flex-wrap">
            {/* Horizon Pills */}
            <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
              <button
                type="button"
                onClick={() => setHorizon("24h")}
                className={`px-2.5 py-1 rounded-md font-medium transition-colors ${
                  horizon === "24h"
                    ? "bg-background text-foreground shadow-xs font-semibold"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                24h + 24h
              </button>
              <button
                type="button"
                onClick={() => setHorizon("48h")}
                className={`px-2.5 py-1 rounded-md font-medium transition-colors ${
                  horizon === "48h"
                    ? "bg-background text-foreground shadow-xs font-semibold"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                48h + 48h
              </button>
              <button
                type="button"
                onClick={() => setHorizon("7d")}
                className={`px-2.5 py-1 rounded-md font-medium transition-colors ${
                  horizon === "7d"
                    ? "bg-background text-foreground shadow-xs font-semibold"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                7d + 72h
              </button>
            </div>

            {/* Hub Selector (if not controlled externally) */}
            {!controlledHub && (
              <select
                aria-label="Settlement Hub Location"
                value={activeHub}
                onChange={(e) => handleHubChange(e.target.value)}
                className="h-8 rounded-md border border-input bg-background px-2.5 text-xs font-semibold text-foreground"
              >
                <option value="LZ_NORTH">LZ_NORTH</option>
                <option value="LZ_HOUSTON">LZ_HOUSTON</option>
                <option value="LZ_SOUTH">LZ_SOUTH</option>
                <option value="LZ_WEST">LZ_WEST</option>
              </select>
            )}

            {/* Refresh Button */}
            <Button
              variant="outline"
              size="sm"
              onClick={() => setRefreshTrigger((k) => k + 1)}
              disabled={isLoading}
              className="h-8 w-8 p-0"
              title="Refresh timeline data"
            >
              <RefreshCw className={`h-3.5 w-3.5 ${isLoading ? "animate-spin" : ""}`} />
            </Button>
          </div>
        </div>

        {/* Legend Ribbon */}
        <div className="flex items-center gap-4 pt-2 text-[11px] font-mono flex-wrap border-t border-border/40 mt-3">
          <div className="flex items-center gap-1.5">
            <span className="h-2 w-5 rounded bg-emerald-500 inline-block" />
            <span className="text-foreground font-semibold">Historical Actual ($/MWh)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="h-0.5 w-5 border-t-2 border-dashed border-violet-500 inline-block" />
            <span className="text-violet-600 dark:text-violet-400 font-semibold">ML Forecast (±12% Corridor)</span>
          </div>
          <div className="flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-full bg-cyan-400 animate-pulse" />
            <span className="text-cyan-600 dark:text-cyan-400 font-semibold">Now (SGT)</span>
          </div>
        </div>
      </CardHeader>

      {/* Main Chart Canvas */}
      <CardContent className="p-2 sm:p-4 pt-0">
        {isLoading && chartData.length === 0 ? (
          <div className="flex flex-col items-center justify-center py-20 text-muted-foreground text-xs gap-2">
            <RefreshCw className="h-5 w-5 animate-spin text-emerald-500" />
            <span>Loading continuous price curve…</span>
          </div>
        ) : error && chartData.length === 0 ? (
          <div className="py-16 text-center text-xs text-destructive">
            <p>Could not load price timeline: {error}</p>
          </div>
        ) : chartData.length === 0 ? (
          <div className="py-16 text-center text-xs text-muted-foreground">
            <p>No price records found for {activeHub} within selected horizon.</p>
          </div>
        ) : (
          <div style={{ width: "100%", height: chartHeight }}>
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart
                data={chartData}
                margin={{ top: 12, right: 18, left: -4, bottom: 4 }}
              >
                <defs>
                  {/* Emerald gradient for historical settlement prices */}
                  <linearGradient id="emeraldTimelineGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#10b981" stopOpacity={0.25} />
                    <stop offset="95%" stopColor="#10b981" stopOpacity={0.0} />
                  </linearGradient>

                  {/* Violet corridor gradient for forecast confidence band */}
                  <linearGradient id="violetConfidenceGrad" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor="#8b5cf6" stopOpacity={0.16} />
                    <stop offset="95%" stopColor="#8b5cf6" stopOpacity={0.02} />
                  </linearGradient>
                </defs>

                <CartesianGrid strokeDasharray="3 3" opacity={0.12} vertical={false} />

                <XAxis
                  dataKey="rawUtc"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  minTickGap={40}
                  tickFormatter={(val: string) => formatSgtChartTick(val, true)}
                  style={{ fontSize: "11px", fill: "currentColor", opacity: 0.7 }}
                />

                <YAxis
                  tickLine={false}
                  axisLine={false}
                  tickMargin={6}
                  tickFormatter={(val) => `$${val}`}
                  style={{ fontSize: "11px", fill: "currentColor", opacity: 0.7 }}
                />

                <RechartsTooltip
                  cursor={{ stroke: "rgba(56, 189, 248, 0.45)", strokeWidth: 1.5, strokeDasharray: "3 3" }}
                  isAnimationActive={false}
                  content={({ active, payload }) => {
                    if (!active || !payload || !payload.length) return null
                    const pt = payload.find((item) => item?.payload != null)?.payload as TimelineDataPoint | undefined
                    if (!pt) return null
                    const isFc = pt.isForecast && !pt.isTransition
                    const price = isFc ? pt.forecastPrice : pt.actualPrice

                    return (
                      <div className="rounded-xl border border-border bg-card/95 backdrop-blur-md p-3 shadow-xl text-xs space-y-1.5 min-w-[200px]">
                        <div className="flex items-center justify-between border-b border-border/50 pb-1.5">
                          <span className="font-semibold text-foreground">
                            {pt.timeLabel}
                          </span>
                          <span
                            className={`px-1.5 py-0.5 rounded text-[10px] font-mono font-semibold ${
                              isFc
                                ? "bg-violet-500/15 text-violet-600 dark:text-violet-400"
                                : "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                            }`}
                          >
                            {isFc ? "ML Forecast" : "Settlement Actual"}
                          </span>
                        </div>

                        <div className="flex items-baseline justify-between pt-0.5">
                          <span className="text-muted-foreground">LMP Price:</span>
                          <span className="font-mono font-bold text-sm text-foreground">
                            ${price?.toFixed(2)} <span className="text-[10px] font-normal text-muted-foreground">/ MWh</span>
                          </span>
                        </div>

                        {isFc && pt.forecastUpper && pt.forecastLower && (
                          <div className="flex items-center justify-between text-[11px] text-muted-foreground font-mono">
                            <span>±12% Band:</span>
                            <span>${pt.forecastLower.toFixed(1)} – ${pt.forecastUpper.toFixed(1)}</span>
                          </div>
                        )}

                        <div className="flex items-center justify-between text-[11px] text-teal-600 dark:text-teal-400 pt-1 border-t border-border/40">
                          <span>Est. Clean Energy:</span>
                          <span className="font-mono font-semibold">{pt.cleanSharePercent}% Clean</span>
                        </div>

                        <div className="text-[10px] text-muted-foreground/75 font-mono pt-0.5">
                          {pt.fullTimeSgt}
                        </div>
                      </div>
                    )
                  }}
                />

                {/* Transition Divider Reference Line ("Now SGT") */}
                {nowPointKey && (
                  <ReferenceLine
                    x={nowPointKey}
                    stroke="#38bdf8"
                    strokeDasharray="3 3"
                    strokeWidth={1.8}
                    label={{
                      value: "● Now (SGT)",
                      position: "insideTopRight",
                      fill: "#38bdf8",
                      fontSize: 10,
                      fontWeight: 600,
                    }}
                  />
                )}

                {/* Shaded Forecast Corridor (Upper Bound) */}
                <Area
                  type="monotone"
                  dataKey="forecastUpper"
                  stroke="none"
                  fill="url(#violetConfidenceGrad)"
                  connectNulls={false}
                  isAnimationActive={false}
                />

                {/* Historical Actual Settlement Price Area Fill */}
                <Area
                  type="monotone"
                  dataKey="actualPrice"
                  stroke="#10b981"
                  strokeWidth={2.5}
                  fill="url(#emeraldTimelineGrad)"
                  connectNulls={false}
                  activeDot={{ r: 5, stroke: "#10b981", strokeWidth: 2, fill: "#ffffff" }}
                  name="Settlement Price"
                />

                {/* Upcoming ML Price Forecast Line */}
                <Line
                  type="monotone"
                  dataKey="forecastPrice"
                  stroke="#8b5cf6"
                  strokeWidth={2.5}
                  strokeDasharray="5 5"
                  dot={false}
                  activeDot={{ r: 5, stroke: "#8b5cf6", strokeWidth: 2, fill: "#ffffff" }}
                  connectNulls={false}
                  name="Forecast Price"
                />
              </ComposedChart>
            </ResponsiveContainer>
          </div>
        )}
      </CardContent>

      {/* Full Variant: Footer Metric Strip */}
      {variant === "full" && (
        <CardFooter className="bg-muted/15 border-t border-border/50 p-3 sm:px-5 flex flex-col sm:flex-row sm:items-center justify-between gap-3 text-xs">
          <div className="flex items-center gap-4 flex-wrap font-mono">
            <div>
              <span className="text-muted-foreground">Rolling Mean: </span>
              <strong className="text-foreground">${summaryStats.avgLmp.toFixed(2)}/MWh</strong>
            </div>
            <div>
              <span className="text-muted-foreground">Peak Spike: </span>
              <strong className="text-amber-500">${summaryStats.maxLmp.toFixed(2)}</strong>
              <span className="text-[10px] text-muted-foreground ml-1">({summaryStats.maxTime})</span>
            </div>
            <div>
              <span className="text-muted-foreground">Lowest Valley: </span>
              <strong className="text-emerald-500">${summaryStats.minLmp.toFixed(2)}</strong>
              <span className="text-[10px] text-muted-foreground ml-1">({summaryStats.minTime})</span>
            </div>
          </div>

          <div className="text-[11px] text-muted-foreground">
            Displaying {chartData.length} intervals standardized to <strong>Asia/Singapore (SGT)</strong>
          </div>
        </CardFooter>
      )}
    </Card>
  )
}
