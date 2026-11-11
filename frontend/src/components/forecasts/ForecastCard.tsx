"use client"

import * as React from "react"
import {
  Area,
  CartesianGrid,
  ComposedChart,
  Line,
  ReferenceArea,
  ReferenceLine,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { DataStatusBadge, type DataStatusType } from "@/components/ui/data-status-badge"
import { Segmented } from "@/components/ui/segmented"
import { Skeleton } from "@/components/ui/skeleton"
import type { ForecastModel } from "@/lib/api"
import { formatMarketChartTick, formatShortDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { formatConfidence } from "@/lib/forecast-confidence"
import { money } from "@/lib/format"
import { hourSpans, LOW_HOURS_PER_DAY, MODEL_LABEL, MODEL_OPTIONS, MODEL_ROLE, timeTicks, type ForecastRow } from "@/lib/price-forecast"

const CHART_HEIGHT = 320
/** The unit after a price, in caption style (STYLE.md §2). */
export const PER_MWH = <span className="font-normal text-muted-foreground"> /MWh</span>

export type PastWindow = "24h" | "7d"
const PAST_OPTIONS = [{ value: "24h", label: "24h" }, { value: "7d", label: "7d" }] as const

interface ForecastCardProps {
  zone: string
  rows: ForecastRow[]
  nowMs: number
  from: number
  to: number
  model: ForecastModel
  /** Leave out when the page owns the model switch: the card then hides its own. */
  onModelChange?: (model: ForecastModel) => void
  pastWindow: PastWindow
  onPastWindowChange: (window: PastWindow) => void
  /** The shown model's latest run, shown in the status badge's hover text. */
  run: { generatedAt: string } | null
  status: DataStatusType
  /** Start times of the cheapest hours of each day, shaded on the chart. */
  lowHours: Set<number>
  loading: boolean
  error: boolean
  onRetry: () => void
  /** A link at the bottom right, after the legend, for example Home's link to the Forecast page. */
  footerAction?: React.ReactNode
}

export function DashSwatch({ color, dash = "5 3" }: { color: string; dash?: string }) {
  return (
    <svg width="20" height="2" aria-hidden="true" className="shrink-0">
      <line x1="0" y1="1" x2="20" y2="1" stroke={color} strokeWidth="2" strokeDasharray={dash} />
    </svg>
  )
}

export function LegendItem({ swatch, children }: { swatch: React.ReactNode; children: React.ReactNode }) {
  return <li className="inline-flex items-center gap-1.5 whitespace-nowrap">{swatch}{children}</li>
}

/** The next 72 hours with the model's typical error as a band. Home and the Forecast page both show it; `useForecastCard` feeds it. */
export function ForecastCard({
  zone, rows, nowMs, from, to, model, onModelChange, pastWindow, onPastWindowChange, run, status, lowHours, loading, error, onRetry, footerAction,
}: ForecastCardProps) {
  const ticks = React.useMemo(() => timeTicks(from, to), [from, to])
  const lowSpans = React.useMemo(() => hourSpans(lowHours), [lowHours])
  const name = MODEL_LABEL[model]

  return (
    <Card className="min-w-0 overflow-hidden">
      {/* Below sm the switches move under the title as one group; they never wrap one by one. */}
      <div className="flex min-w-0 flex-col gap-3 p-4 pb-2 sm:flex-row sm:items-start sm:justify-between sm:p-5 sm:pb-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold leading-5">Price Forecast, next 72 hours</h2>
          <p className="text-xs leading-4 text-muted-foreground">Day-ahead prices so far, and the model&apos;s forecast with its typical error.</p>
        </div>
        <div className="flex shrink-0 flex-nowrap items-center gap-2">
          {!loading && (
            <DataStatusBadge size="sm" status={status} updatedAt={run?.generatedAt ?? null} source={`${name} forecast for ${zone}, ${MODEL_ROLE[model]}`} />
          )}
          {onModelChange && <Segmented label="Forecast model" value={model} onChange={onModelChange} options={MODEL_OPTIONS} />}
          <Segmented label="Past window" value={pastWindow} onChange={onPastWindowChange} options={PAST_OPTIONS} />
        </div>
      </div>

      <div className="min-w-0 px-2 pb-4 sm:px-4 sm:pb-5">
        {loading ? (
          <Skeleton className="w-full" style={{ height: CHART_HEIGHT }} role="status" aria-label="Loading the forecast" />
        ) : error ? (
          <div className="flex items-center justify-between gap-2 px-2 py-6">
            <span className="text-sm leading-5 text-muted-foreground">Could not load the forecast.</span>
            <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry}>Retry</Button>
          </div>
        ) : rows.length === 0 ? (
          <p className="px-2 py-6 text-sm leading-5 text-muted-foreground">No prices for {zone} in this window yet.</p>
        ) : (
          <>
            <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
              <ResponsiveContainer width="100%" height="100%" minWidth={0}>
                <ComposedChart data={rows} margin={{ top: 20, right: 16, left: -4, bottom: 4 }}>
                  <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
                  <XAxis
                    dataKey="t"
                    type="number"
                    scale="time"
                    domain={[from, to]}
                    allowDataOverflow
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
                    tickFormatter={(v: number) => `$${Math.round(v)}`}
                    tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
                  />
                  <Tooltip
                    cursor={{ stroke: "var(--muted-foreground)", strokeWidth: 1, strokeDasharray: "3 3" }}
                    isAnimationActive={false}
                    content={({ active, payload }) => {
                      const row = active ? (payload?.find((p) => p?.payload)?.payload as ForecastRow | undefined) : undefined
                      if (!row) return null
                      const lines: Array<[string, React.ReactNode]> = []
                      if (row.price != null) lines.push([row.t > nowMs ? "Day-ahead price (known)" : "Day-ahead price", <>{money(row.price)}{PER_MWH}</>])
                      if (row.price == null && row.forecast != null) {
                        lines.push([`Forecast (${name})`, <>{money(row.forecast)}{PER_MWH}</>])
                        if (row.typicalError != null) lines.push(["Typical error", <>± {money(row.typicalError)}{PER_MWH}</>])
                        lines.push(["Confidence", formatConfidence(row.confidence)])
                      }
                      return (
                        <div className="w-max max-w-[calc(100vw-2rem)] space-y-1 rounded-lg border border-border bg-popover p-3 text-xs leading-4 text-popover-foreground shadow-lg">
                          <div className="font-semibold">{formatShortDateTime(row.t)} {MARKET_ZONE_LABEL}</div>
                          {lowHours.has(row.t) && (
                            <div className="text-muted-foreground">One of the day&apos;s {LOW_HOURS_PER_DAY} cheapest hours</div>
                          )}
                          {lines.map(([label, value]) => (
                            <div key={label} className="flex items-baseline justify-between gap-4">
                              <span className="text-muted-foreground">{label}</span>
                              <span className="font-medium tabular-nums">{value}</span>
                            </div>
                          ))}
                        </div>
                      )
                    }}
                  />

                  {/* Cheapest hours of each day: neutral full-height columns behind the lines. */}
                  {lowSpans.map(([x1, x2]) => (
                    <ReferenceArea key={x1} x1={x1} x2={x2} ifOverflow="hidden" fill="var(--muted-foreground)" fillOpacity={0.12} stroke="none" />
                  ))}
                  <Area dataKey="band" stroke="none" fill="var(--primary)" fillOpacity={0.15} isAnimationActive={false} activeDot={false} />
                  <ReferenceLine
                    x={nowMs}
                    stroke="var(--foreground)"
                    strokeDasharray="2 3"
                    label={{ value: "Now", position: "top", fontSize: 12, fill: "var(--muted-foreground)" }}
                  />
                  <Line dataKey="price" stroke="var(--primary)" strokeWidth={2} dot={false} isAnimationActive={false}
                    activeDot={{ r: 4, stroke: "var(--primary)", strokeWidth: 2, fill: "var(--card)" }} />
                  <Line dataKey="forecast" stroke="var(--primary)" strokeWidth={2} strokeDasharray="6 4" dot={false} isAnimationActive={false}
                    activeDot={{ r: 4, stroke: "var(--primary)", strokeWidth: 2, fill: "var(--card)" }} />
                </ComposedChart>
              </ResponsiveContainer>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 px-2 text-xs leading-4 text-muted-foreground">
              <ul className="flex flex-wrap items-center gap-x-4 gap-y-1">
                <LegendItem swatch={<span className="h-0.5 w-5 shrink-0 bg-primary" aria-hidden="true" />}>Day-ahead price</LegendItem>
                <LegendItem swatch={<DashSwatch color="var(--primary)" />}>Forecast ({name})</LegendItem>
                <LegendItem swatch={<span className="h-3 w-5 shrink-0 rounded-sm bg-primary/15" aria-hidden="true" />}>Typical error</LegendItem>
                {lowSpans.length > 0 && (
                  <LegendItem swatch={<span className="h-3 w-5 shrink-0 rounded-sm bg-muted-foreground/15" aria-hidden="true" />}>
                    Cheapest {LOW_HOURS_PER_DAY} h each day
                  </LegendItem>
                )}
                <LegendItem swatch={<span className="h-3 w-0 shrink-0 border-l border-dashed border-foreground" aria-hidden="true" />}>Now</LegendItem>
                <li className="whitespace-nowrap">$/MWh, {MARKET_ZONE_LABEL}</li>
              </ul>
              {footerAction && <span className="ml-auto shrink-0">{footerAction}</span>}
            </div>
          </>
        )}
      </div>
    </Card>
  )
}
