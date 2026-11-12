"use client"

import * as React from "react"
import { Bar, CartesianGrid, ComposedChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Segmented } from "@/components/ui/segmented"
import { Skeleton } from "@/components/ui/skeleton"
import { PER_MWH } from "@/components/forecasts/ForecastCard"
import type { ForecastAccuracy, ForecastModel } from "@/lib/api"
import { formatMarketChartTick, formatMarketDateTime, formatShortDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { money } from "@/lib/format"
import {
  accuracyStats, deviationRows, HOUR_MS, MODEL_LABEL, MODEL_OPTIONS, MODEL_ROLE, timeTicks, type DeviationRow,
} from "@/lib/price-forecast"

const CHART_HEIGHT = 240

/** "+$3", "−$2", "$0": the sign says over or under. */
const signedDollars = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}$${Math.abs(Math.round(v))}`

interface AccuracyCardProps {
  model: ForecastModel
  onModelChange: (model: ForecastModel) => void
  /** Accuracy for the chosen model. */
  data: ForecastAccuracy | null
  days: number
  nowMs: number
  loading: boolean
  error: boolean
  onRetry: () => void
}

function Stat({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="min-w-0">
      <dt className="text-xs leading-4 text-muted-foreground">{label}</dt>
      <dd className="text-sm font-medium leading-5 tabular-nums">{children}</dd>
    </div>
  )
}

/** Card 2 of the Forecast page: how far each past forecast was from the day-ahead price. */
export function AccuracyCard({ model, onModelChange, data, days, nowMs, loading, error, onRetry }: AccuracyCardProps) {
  const name = MODEL_LABEL[model]
  const subtitle = `${name}, ${MODEL_ROLE[model]} · forecast minus the day-ahead price, last ${days} days`
  const from = nowMs - days * 24 * HOUR_MS
  const ticks = React.useMemo(() => timeTicks(from, nowMs), [from, nowMs])
  const rows = React.useMemo(() => deviationRows(data?.points ?? [], from, nowMs), [data, from, nowMs])
  const stats = React.useMemo(() => accuracyStats(data?.points ?? []), [data])

  return (
    <Card className="min-w-0 overflow-hidden">
      <div className="flex min-w-0 flex-col gap-3 p-4 pb-2 sm:flex-row sm:items-start sm:justify-between sm:p-5 sm:pb-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold leading-5">How far off the forecast was</h2>
          <p className="truncate text-xs leading-4 text-muted-foreground" title={subtitle}>{subtitle}</p>
        </div>
        <Segmented label="Forecast model" value={model} onChange={onModelChange} options={MODEL_OPTIONS} className="shrink-0 self-start" />
      </div>

      {loading ? (
        <div className="px-4 pb-4 sm:px-5 sm:pb-5">
          <Skeleton className="w-full" style={{ height: CHART_HEIGHT }} role="status" aria-label="Loading forecast accuracy" />
        </div>
      ) : error ? (
        <div className="flex items-center justify-between gap-2 px-4 pb-4 sm:px-5 sm:pb-5">
          <span className="text-sm leading-5 text-muted-foreground">Could not load forecast accuracy.</span>
          <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry}>Retry</Button>
        </div>
      ) : stats.hours === 0 ? (
        <p className="px-4 pb-4 text-sm leading-5 text-muted-foreground sm:px-5 sm:pb-5">No past {name} forecasts to check yet.</p>
      ) : (
        // Chart on the left, the figures on the right behind a divider. Below md the figures move under the chart.
        <div className="grid min-w-0 md:grid-cols-[minmax(0,1fr)_13rem]">
          <div className="min-w-0 px-2 pb-4 sm:px-4 sm:pb-5">
            <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
              <ResponsiveContainer width="100%" height="100%" minWidth={0}>
                <ComposedChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 4 }}>
                  <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="t"
                    type="number"
                    scale="time"
                    domain={[from, nowMs]}
                    ticks={ticks}
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
                    minTickGap={24}
                    tickFormatter={(t: number) => formatMarketChartTick(new Date(t).toISOString(), true)}
                    tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
                  />
                  <YAxis
                    domain={["auto", "auto"]}
                    tickLine={false}
                    axisLine={false}
                    tickMargin={6}
                    tickCount={5}
                    tickFormatter={signedDollars}
                    tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
                  />
                  <Tooltip
                    cursor={{ fill: "var(--muted)", opacity: 0.6 }}
                    isAnimationActive={false}
                    content={({ active, payload }) => {
                      const row = active ? (payload?.find((p) => p?.payload)?.payload as DeviationRow | undefined) : undefined
                      if (!row || row.deviation == null || row.forecast == null || row.actual == null) return null
                      const verdict = Math.abs(row.deviation) < 0.005
                        ? "Spot on"
                        : `Too ${row.deviation > 0 ? "high" : "low"} by ${money(Math.abs(row.deviation))}`
                      return (
                        <div className="w-max max-w-[calc(100vw-2rem)] space-y-1 rounded-lg border border-border bg-popover p-3 text-xs leading-4 text-popover-foreground shadow-lg">
                          <div className="font-semibold">{formatShortDateTime(row.t)} {MARKET_ZONE_LABEL}</div>
                          <div className="font-medium">{verdict}</div>
                          {([[`Forecast (${name})`, row.forecast], ["Day-ahead price", row.actual]] as const).map(([label, value]) => (
                            <div key={label} className="flex items-baseline justify-between gap-4">
                              <span className="text-muted-foreground">{label}</span>
                              <span className="font-medium tabular-nums">{money(value)}{PER_MWH}</span>
                            </div>
                          ))}
                        </div>
                      )
                    }}
                  />
                  {stats.typicalError != null && (
                    <>
                      <ReferenceLine y={stats.typicalError} stroke="var(--muted-foreground)" strokeDasharray="2 3" />
                      <ReferenceLine y={-stats.typicalError} stroke="var(--muted-foreground)" strokeDasharray="2 3" />
                    </>
                  )}
                  <ReferenceLine y={0} stroke="var(--foreground)" strokeOpacity={0.6} />
                  <Bar dataKey="deviation" fill="var(--primary)" isAnimationActive={false} />
                </ComposedChart>
              </ResponsiveContainer>
            </div>
            <p className="mt-2 px-2 text-xs leading-4 text-muted-foreground">
              Above 0: the forecast was too high. Below 0: too low. Dotted lines: ± typical error. $/MWh · {MARKET_ZONE_LABEL}
            </p>
          </div>

          <dl className="grid min-w-0 grid-cols-2 content-start gap-x-4 gap-y-3 border-t p-4 sm:p-5 md:grid-cols-1 md:border-l md:border-t-0">
            <Stat label="Typical error">± {money(stats.typicalError as number)}{PER_MWH}</Stat>
            <Stat label="Average miss">{money(stats.averageMiss as number)}{PER_MWH}</Stat>
            <Stat label="Biggest miss">
              {stats.biggestMiss && (
                <span title={formatMarketDateTime(stats.biggestMiss.t)}>
                  {money(stats.biggestMiss.miss)}{PER_MWH}
                  <span className="block text-xs font-normal leading-4 text-muted-foreground">{formatShortDateTime(stats.biggestMiss.t)}</span>
                </span>
              )}
            </Stat>
            <Stat label="Hours checked">{stats.hours} of {rows.length}</Stat>
          </dl>
        </div>
      )}
    </Card>
  )
}
