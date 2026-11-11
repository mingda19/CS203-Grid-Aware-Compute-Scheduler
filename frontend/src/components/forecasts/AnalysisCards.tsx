"use client"

import * as React from "react"
import {
  Bar, BarChart, CartesianGrid, ComposedChart, Line, ReferenceLine, ResponsiveContainer, Scatter, ScatterChart, Tooltip, XAxis, YAxis,
} from "recharts"
import { DashSwatch, LegendItem, PER_MWH } from "@/components/forecasts/ForecastCard"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import type { ForecastAccuracyPoint, ForecastModel } from "@/lib/api"
import {
  formatMarketChartTick, formatMarketTime, formatShortDateTime, MARKET_ZONE_LABEL, marketDateLabelAnchor, parseIsoDate,
} from "@/lib/date-utils"
import { money } from "@/lib/format"
import {
  accuracyStats, LOW_HOURS_PER_DAY, MIN_DAY_HOURS, MIN_HOUR_POINTS, missByHour, MODEL_LABEL, runningMiss, scatterDomain, timeTicks,
  type CheapHoursDay, type cheapHoursCheck, type HourMissRow,
} from "@/lib/price-forecast"

const CHART_HEIGHT = 240

/** "+$1.30", "−$0.40", "$0.00": the sign says too high or too low. */
const signedMoney = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}${money(Math.abs(v))}`

interface ChartCardProps {
  title: string
  /** One plain line under the title saying what the chart shows. */
  description: string
  /** Switches on the right of the title. Below sm they move under it as one group. */
  controls?: React.ReactNode
  /** The chart's height, so the loading skeleton keeps the card's size. */
  height: number
  /** What the card loads, for "Could not load …" and the skeleton's label, for example "forecast accuracy". */
  subject: string
  loading: boolean
  error: boolean
  onRetry: () => void
  /** One muted line shown instead of the chart, or null when there is something to chart. */
  empty?: string | null
  children: React.ReactNode
}

/** A chart card on the Forecast page: a title and description, then the chart or its loading, error or empty state (STYLE §7). */
export function ChartCard({ title, description, controls, height, subject, loading, error, onRetry, empty, children }: ChartCardProps) {
  return (
    <Card className="min-w-0 overflow-hidden">
      <div className="flex min-w-0 flex-col gap-3 p-4 pb-2 sm:flex-row sm:items-start sm:justify-between sm:p-5 sm:pb-2">
        <div className="min-w-0 flex-1">
          <h2 className="truncate text-sm font-semibold leading-5" title={title}>{title}</h2>
          <p className="text-xs leading-4 text-muted-foreground">{description}</p>
        </div>
        {controls && <div className="flex shrink-0 flex-nowrap items-center gap-2">{controls}</div>}
      </div>
      {loading ? (
        <div className="px-4 pb-4 sm:px-5 sm:pb-5">
          <Skeleton className="w-full" style={{ height }} role="status" aria-label={`Loading ${subject}`} />
        </div>
      ) : error ? (
        <div className="flex min-w-0 items-center justify-between gap-2 px-4 pb-4 sm:px-5 sm:pb-5">
          <span className="min-w-0 text-sm leading-5 text-muted-foreground">Could not load {subject}.</span>
          <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry}>Retry</Button>
        </div>
      ) : empty ? (
        <p className="px-4 pb-4 text-sm leading-5 text-muted-foreground sm:px-5 sm:pb-5">{empty}</p>
      ) : (
        <div className="min-w-0 px-2 pb-4 sm:px-4 sm:pb-5">{children}</div>
      )}
    </Card>
  )
}

/** The legend row under a chart. The unit comes last, once. */
export function ChartLegend({ children }: { children: React.ReactNode }) {
  return (
    <ul className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 px-2 text-xs leading-4 sm:px-1 text-muted-foreground">
      {children}
      <li className="whitespace-nowrap">$/MWh, {MARKET_ZONE_LABEL}</li>
    </ul>
  )
}

/** The legend swatch for a bar series. */
export const BAR_SWATCH = <span className="h-3 w-2 shrink-0 rounded-sm bg-primary" aria-hidden="true" />

/** The hover box on every Forecast page chart. */
export function TooltipBox({ title, children }: { title: React.ReactNode; children: React.ReactNode }) {
  return (
    <div className="w-max max-w-[calc(100vw-2rem)] space-y-1 rounded-lg border border-border bg-popover p-3 text-xs leading-4 text-popover-foreground shadow-lg">
      <div className="font-semibold">{title}</div>
      {children}
    </div>
  )
}

export function TooltipRow({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-4">
      <span className="text-muted-foreground">{label}</span>
      <span className="font-medium tabular-nums">{children}</span>
    </div>
  )
}

type CheapHours = ReturnType<typeof cheapHoursCheck>

interface KeyFiguresProps {
  model: ForecastModel
  /** The chosen model's past hours inside the page window. */
  points: ForecastAccuracyPoint[]
  cheap: CheapHours
  loading: boolean
  error: boolean
  onRetry: () => void
}

/** The unit after a key figure, in caption size (STYLE §2). It keeps to the number's line unless the cell is too narrow. */
const FIGURE_UNIT = <span className="text-xs font-normal leading-4 text-muted-foreground"> /MWh</span>

function Figure({ label, loading, value, unit, caption }: { label: string; loading: boolean; value: string; unit?: boolean; caption?: string }) {
  return (
    <div className="min-w-0 bg-card p-4 sm:p-5">
      <dt className="text-xs leading-4 text-muted-foreground">{label}</dt>
      <dd className="mt-1">
        {loading ? (
          <Skeleton className="h-8 w-24 sm:h-9" role="status" aria-label={`Loading ${label.toLowerCase()}`} />
        ) : (
          <>
            <div className="text-2xl font-semibold leading-8 tabular-nums sm:text-3xl sm:leading-9"><span className="whitespace-nowrap">{value}</span>{unit && FIGURE_UNIT}</div>
            {caption && <div className="text-xs leading-4 text-muted-foreground">{caption}</div>}
          </>
        )}
      </dd>
    </div>
  )
}

/** Four headline numbers for the window. No title: the labels are the content. */
export function KeyFigures({ model, points, cheap, loading, error, onRetry }: KeyFiguresProps) {
  const stats = React.useMemo(() => accuracyStats(points), [points])

  if (error) {
    return (
      <Card className="flex min-w-0 items-center justify-between gap-2 p-4 sm:p-5">
        <span className="min-w-0 text-sm leading-5 text-muted-foreground">Could not load the key figures.</span>
        <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry}>Retry</Button>
      </Card>
    )
  }
  if (!loading && stats.hours === 0) {
    return (
      <Card className="min-w-0 p-4 sm:p-5">
        <p className="text-sm leading-5 text-muted-foreground">No past {MODEL_LABEL[model]} forecasts in this window yet.</p>
      </Card>
    )
  }

  const bias = stats.bias ?? 0
  const lean = Math.abs(bias) < 0.005 ? "no lean either way" : bias > 0 ? "too high on average" : "too low on average"
  return (
    <Card className="min-w-0 overflow-hidden">
      {/* The 1px gaps show the border colour behind the cells, so every divider runs edge to edge. */}
      <dl className="grid grid-cols-2 gap-px bg-border sm:grid-cols-4" aria-busy={loading}>
        <Figure label="Average miss" loading={loading} value={money(stats.averageMiss ?? 0)} unit caption={`over ${stats.hours} hours`} />
        <Figure label="Typical miss" loading={loading} value={`± ${money(stats.typicalError ?? 0)}`} unit />
        <Figure label="Bias" loading={loading} value={signedMoney(bias)} unit caption={lean} />
        <Figure
          label="Cheap hours hit"
          loading={loading}
          value={cheap.picks ? `${Math.round((100 * cheap.hits) / cheap.picks)}%` : "—"}
          caption={cheap.picks ? `${cheap.hits} of ${cheap.picks} hours` : `no day with ${MIN_DAY_HOURS} hours yet`}
        />
      </dl>
    </Card>
  )
}

/** "Thu 12 Nov": the date part of formatShortDateTime, for a CT day. */
const dayLabel = (day: string) => formatShortDateTime(marketDateLabelAnchor(day)).split(",")[0]
const dayTick = (day: string) =>
  new Intl.DateTimeFormat("en-GB", { day: "numeric", month: "short", timeZone: "UTC" }).format(marketDateLabelAnchor(day))
const hourList = (times: number[]) => times.map((t) => formatMarketTime(t, false)).join(", ")

interface CheapHoursCardProps {
  model: ForecastModel
  cheap: CheapHours
  loading: boolean
  error: boolean
  onRetry: () => void
}

/** Per CT day: what the forecast's cheapest-hour picks cost over the really cheapest hours. */
export function CheapHoursCard({ model, cheap, loading, error, onRetry }: CheapHoursCardProps) {
  return (
    <ChartCard
      title="Cheap Hours Check"
      description={`Whether the forecast picked each day's ${LOW_HOURS_PER_DAY} cheapest hours, and what wrong picks cost.`}
      height={CHART_HEIGHT}
      subject="the cheap hours check"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={cheap.days.length === 0 ? `No day in this window has ${MIN_DAY_HOURS} hours of past ${MODEL_LABEL[model]} forecasts yet.` : null}
    >
      <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
        <ResponsiveContainer width="100%" height="100%" minWidth={0}>
          <BarChart data={cheap.days} margin={{ top: 8, right: 8, left: -4, bottom: 4 }}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
            <XAxis
              dataKey="day"
              tickLine={false}
              axisLine={false}
              tickMargin={8}
              minTickGap={8}
              tickFormatter={dayTick}
              tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
            />
            <YAxis
              tickLine={false}
              axisLine={false}
              tickMargin={6}
              tickCount={5}
              tickFormatter={(v: number) => money(v)}
              tick={{ fontSize: 12, fill: "var(--muted-foreground)" }}
            />
            <Tooltip
              cursor={{ fill: "var(--muted)", opacity: 0.6 }}
              isAnimationActive={false}
              content={({ active, payload }) => {
                const day = active ? (payload?.find((p) => p?.payload)?.payload as CheapHoursDay | undefined) : undefined
                if (!day) return null
                return (
                  <TooltipBox title={`${dayLabel(day.day)} ${MARKET_ZONE_LABEL}`}>
                    <div className="font-medium">{day.hits} of {LOW_HOURS_PER_DAY} cheapest hours</div>
                    <TooltipRow label="Extra cost">{money(day.extraCost)}{PER_MWH}</TooltipRow>
                    <TooltipRow label="Forecast picked">{hourList(day.picked)}</TooltipRow>
                    <TooltipRow label="Cheapest">{hourList(day.best)}</TooltipRow>
                  </TooltipBox>
                )
              }}
            />
            <Bar dataKey="extraCost" fill="var(--primary)" maxBarSize={48} isAnimationActive={false} />
          </BarChart>
        </ResponsiveContainer>
      </div>
      <ChartLegend>
        <LegendItem swatch={BAR_SWATCH}>Extra cost of the forecast&apos;s picks</LegendItem>
      </ChartLegend>
    </ChartCard>
  )
}

/** "+$3", "−$2", "$0": axis ticks where the sign says too high or too low. */
export const signedDollars = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}$${Math.abs(Math.round(v))}`

/** The axis look shared by the analysis charts. */
const AXIS = { tickLine: false, axisLine: false, tick: { fontSize: 12, fill: "var(--muted-foreground)" } } as const
const AXIS_LABEL = { fontSize: 12, fill: "var(--muted-foreground)" } as const

interface PointsCardProps {
  model: ForecastModel
  /** The chosen model's past hours inside the page window. */
  points: ForecastAccuracyPoint[]
  loading: boolean
  error: boolean
  onRetry: () => void
}

/** The scatter needs this many hours before a pattern means anything. */
const MIN_SCATTER_POINTS = 24

/** Each past hour as a dot: forecast across, day-ahead price up. On the dashed line the forecast was exact. */
export function ScatterCard({ points, loading, error, onRetry }: PointsCardProps) {
  const domain = React.useMemo(() => scatterDomain(points), [points])
  const dots = React.useMemo(
    () => points.map((p) => ({
      t: parseIsoDate(p.intervalStartUtc).getTime(), forecast: p.predictedPrice, actual: p.actualPrice, miss: p.predictedPrice - p.actualPrice,
    })),
    [points],
  )

  return (
    <ChartCard
      title="Forecast vs Actual"
      description="Each hour's forecast against its actual price."
      height={CHART_HEIGHT}
      subject="forecast accuracy"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={points.length < MIN_SCATTER_POINTS || !domain ? "Not enough past forecasts yet." : null}
    >
      {domain && (
        <>
          <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
            <ResponsiveContainer width="100%" height="100%" minWidth={0}>
              <ScatterChart margin={{ top: 8, right: 8, left: 8, bottom: 12 }}>
                <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" />
                <XAxis
                  {...AXIS}
                  dataKey="forecast"
                  type="number"
                  domain={domain}
                  tickCount={5}
                  tickMargin={6}
                  tickFormatter={(v: number) => `$${Math.round(v)}`}
                  label={{ value: "Forecast", position: "insideBottom", offset: -10, ...AXIS_LABEL }}
                />
                <YAxis
                  {...AXIS}
                  dataKey="actual"
                  type="number"
                  domain={domain}
                  tickCount={5}
                  tickMargin={6}
                  tickFormatter={(v: number) => `$${Math.round(v)}`}
                  label={{ value: "Day-ahead price", angle: -90, position: "insideLeft", offset: 0, style: { textAnchor: "middle" }, ...AXIS_LABEL }}
                />
                <Tooltip
                  cursor={{ stroke: "var(--muted-foreground)", strokeDasharray: "3 3" }}
                  isAnimationActive={false}
                  content={({ active, payload }) => {
                    const dot = active ? (payload?.find((p) => p?.payload)?.payload as (typeof dots)[number] | undefined) : undefined
                    if (!dot) return null
                    return (
                      <TooltipBox title={`${formatShortDateTime(dot.t)} ${MARKET_ZONE_LABEL}`}>
                        <TooltipRow label="Forecast">{money(dot.forecast)}{PER_MWH}</TooltipRow>
                        <TooltipRow label="Day-ahead price">{money(dot.actual)}{PER_MWH}</TooltipRow>
                        <TooltipRow label="Miss">{signedMoney(dot.miss)}{PER_MWH}</TooltipRow>
                      </TooltipBox>
                    )
                  }}
                />
                <ReferenceLine
                  segment={[{ x: domain[0], y: domain[0] }, { x: domain[1], y: domain[1] }]}
                  stroke="var(--muted-foreground)"
                  strokeDasharray="5 3"
                  ifOverflow="hidden"
                />
                <Scatter data={dots} fill="var(--primary)" fillOpacity={0.5} isAnimationActive={false} />
              </ScatterChart>
            </ResponsiveContainer>
          </div>
          <ChartLegend>
            <LegendItem swatch={<span className="size-2 shrink-0 rounded-full bg-primary/50" aria-hidden="true" />}>One hour</LegendItem>
            <LegendItem swatch={<DashSwatch color="var(--muted-foreground)" />}>Perfect forecast</LegendItem>
          </ChartLegend>
        </>
      )}
    </ChartCard>
  )
}

const hourTick = (hour: number) => String(hour).padStart(2, "0")

/** Average miss and lean for each CT hour of the day. */
export function HourOfDayCard({ points, loading, error, onRetry }: PointsCardProps) {
  const rows = React.useMemo(() => missByHour(points), [points])

  return (
    <ChartCard
      title="Miss by Hour of Day"
      description="Where in the day the forecast misses most, in Central time."
      height={CHART_HEIGHT}
      subject="forecast accuracy"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={rows.every((r) => r.averageMiss == null) ? `No hour of the day has ${MIN_HOUR_POINTS} past forecasts yet.` : null}
    >
      <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
        <ResponsiveContainer width="100%" height="100%" minWidth={0}>
          <ComposedChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 4 }}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
            <XAxis {...AXIS} dataKey="hour" ticks={[0, 3, 6, 9, 12, 15, 18, 21]} interval={0} tickMargin={8} tickFormatter={hourTick} />
            <YAxis {...AXIS} tickCount={5} tickMargin={6} tickFormatter={signedDollars} />
            <Tooltip
              cursor={{ fill: "var(--muted)", opacity: 0.6 }}
              isAnimationActive={false}
              content={({ active, payload }) => {
                const row = active ? (payload?.find((p) => p?.payload)?.payload as HourMissRow | undefined) : undefined
                if (!row) return null
                return (
                  <TooltipBox title={`${hourTick(row.hour)}:00 ${MARKET_ZONE_LABEL}`}>
                    {row.averageMiss == null || row.bias == null ? (
                      <div className="text-muted-foreground">{row.count} of {MIN_HOUR_POINTS} hours needed</div>
                    ) : (
                      <>
                        <TooltipRow label="Average miss">{money(row.averageMiss)}{PER_MWH}</TooltipRow>
                        <TooltipRow label="Bias">{signedMoney(row.bias)}{PER_MWH}</TooltipRow>
                        <TooltipRow label="Hours counted">{row.count}</TooltipRow>
                      </>
                    )}
                  </TooltipBox>
                )
              }}
            />
            <ReferenceLine y={0} stroke="var(--foreground)" strokeOpacity={0.6} />
            <Bar dataKey="averageMiss" fill="var(--primary)" isAnimationActive={false} />
            <Line
              dataKey="bias"
              stroke="var(--foreground)"
              strokeWidth={2}
              dot={{ r: 2.5, fill: "var(--foreground)", strokeWidth: 0 }}
              activeDot={{ r: 4, stroke: "var(--foreground)", strokeWidth: 2, fill: "var(--card)" }}
              isAnimationActive={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <ChartLegend>
        <LegendItem swatch={BAR_SWATCH}>Average miss</LegendItem>
        <LegendItem swatch={<span className="h-0.5 w-5 shrink-0 bg-foreground" aria-hidden="true" />}>Bias (above 0: too high)</LegendItem>
      </ChartLegend>
    </ChartCard>
  )
}

/** The running total needs this many hours before a trend shows. */
const MIN_DRIFT_POINTS = 48

interface DriftCardProps extends PointsCardProps {
  /** The page window, so the line spans the same dates as the deviation chart. */
  from: number
  nowMs: number
}

/** The running sum of forecast minus price. Rising: the forecast keeps running high. */
export function DriftCard({ points, from, nowMs, loading, error, onRetry }: DriftCardProps) {
  const rows = React.useMemo(() => runningMiss(points), [points])
  const ticks = React.useMemo(() => timeTicks(from, nowMs), [from, nowMs])

  return (
    <ChartCard
      title="Forecast Drift"
      description="Running total of the miss. Rising means the forecast keeps running high."
      height={CHART_HEIGHT}
      subject="forecast accuracy"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={rows.length < MIN_DRIFT_POINTS ? "Not enough past forecasts yet." : null}
    >
      <div className="min-w-0" style={{ height: CHART_HEIGHT }}>
        <ResponsiveContainer width="100%" height="100%" minWidth={0}>
          <ComposedChart data={rows} margin={{ top: 8, right: 8, left: -4, bottom: 4 }}>
            <CartesianGrid stroke="var(--border)" strokeDasharray="3 3" vertical={false} />
            <XAxis
              {...AXIS}
              dataKey="t"
              type="number"
              scale="time"
              domain={[from, nowMs]}
              ticks={ticks}
              tickMargin={8}
              minTickGap={24}
              tickFormatter={(t: number) => formatMarketChartTick(new Date(t).toISOString(), true)}
            />
            <YAxis {...AXIS} tickCount={5} tickMargin={6} tickFormatter={signedDollars} />
            <Tooltip
              cursor={{ stroke: "var(--muted-foreground)", strokeWidth: 1, strokeDasharray: "3 3" }}
              isAnimationActive={false}
              content={({ active, payload }) => {
                const row = active ? (payload?.find((p) => p?.payload)?.payload as (typeof rows)[number] | undefined) : undefined
                if (!row) return null
                return (
                  <TooltipBox title={`${formatShortDateTime(row.t)} ${MARKET_ZONE_LABEL}`}>
                    <TooltipRow label="Running total">{signedMoney(row.total)}{PER_MWH}</TooltipRow>
                    <TooltipRow label="This hour's miss">{signedMoney(row.miss)}{PER_MWH}</TooltipRow>
                  </TooltipBox>
                )
              }}
            />
            <ReferenceLine y={0} stroke="var(--foreground)" strokeOpacity={0.6} />
            <Line
              dataKey="total"
              stroke="var(--primary)"
              strokeWidth={2}
              dot={false}
              activeDot={{ r: 4, stroke: "var(--primary)", strokeWidth: 2, fill: "var(--card)" }}
              isAnimationActive={false}
            />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <ChartLegend>
        <LegendItem swatch={<span className="h-0.5 w-5 shrink-0 bg-primary" aria-hidden="true" />}>Running total of forecast minus price</LegendItem>
      </ChartLegend>
    </ChartCard>
  )
}
