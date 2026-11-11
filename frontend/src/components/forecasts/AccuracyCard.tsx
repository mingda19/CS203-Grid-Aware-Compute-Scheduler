"use client"

import * as React from "react"
import { Bar, CartesianGrid, ComposedChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts"
import { BAR_SWATCH, ChartCard, ChartLegend, TooltipBox, TooltipRow } from "@/components/forecasts/AnalysisCards"
import { DashSwatch, LegendItem, PER_MWH } from "@/components/forecasts/ForecastCard"
import type { ForecastAccuracyPoint, ForecastModel } from "@/lib/api"
import { formatMarketChartTick, formatShortDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { money } from "@/lib/format"
import { accuracyStats, deviationRows, MODEL_LABEL, timeTicks, type DeviationRow } from "@/lib/price-forecast"

const CHART_HEIGHT = 240

/** "+$3", "−$2", "$0": the sign says over or under. */
const signedDollars = (v: number) => `${v > 0 ? "+" : v < 0 ? "−" : ""}$${Math.abs(Math.round(v))}`

interface AccuracyCardProps {
  model: ForecastModel
  /** The chosen model's past hours inside the page window. */
  points: ForecastAccuracyPoint[]
  /** Start of the page window. The chart runs from here to now. */
  from: number
  nowMs: number
  loading: boolean
  error: boolean
  onRetry: () => void
}

/** How far each past forecast was from the day-ahead price, one bar per hour. */
export function AccuracyCard({ model, points, from, nowMs, loading, error, onRetry }: AccuracyCardProps) {
  const name = MODEL_LABEL[model]
  const ticks = React.useMemo(() => timeTicks(from, nowMs), [from, nowMs])
  const rows = React.useMemo(() => deviationRows(points, from, nowMs), [points, from, nowMs])
  const { typicalError } = React.useMemo(() => accuracyStats(points), [points])

  return (
    <ChartCard
      title="Price Deviation"
      description="How far off the forecast was, compared with the actual day-ahead price."
      height={CHART_HEIGHT}
      subject="forecast accuracy"
      loading={loading}
      error={error}
      onRetry={onRetry}
      empty={points.length === 0 ? `No past ${name} forecasts in this window yet.` : null}
    >
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
                  <TooltipBox title={`${formatShortDateTime(row.t)} ${MARKET_ZONE_LABEL}`}>
                    <div className="font-medium">{verdict}</div>
                    <TooltipRow label={`Forecast (${name})`}>{money(row.forecast)}{PER_MWH}</TooltipRow>
                    <TooltipRow label="Day-ahead price">{money(row.actual)}{PER_MWH}</TooltipRow>
                  </TooltipBox>
                )
              }}
            />
            {typicalError != null && (
              <>
                <ReferenceLine y={typicalError} stroke="var(--muted-foreground)" strokeDasharray="2 3" />
                <ReferenceLine y={-typicalError} stroke="var(--muted-foreground)" strokeDasharray="2 3" />
              </>
            )}
            <ReferenceLine y={0} stroke="var(--foreground)" strokeOpacity={0.6} />
            <Bar dataKey="deviation" fill="var(--primary)" isAnimationActive={false} />
          </ComposedChart>
        </ResponsiveContainer>
      </div>
      <ChartLegend>
        <LegendItem swatch={BAR_SWATCH}>Forecast minus day-ahead price</LegendItem>
        <LegendItem swatch={<DashSwatch color="var(--muted-foreground)" dash="2 3" />}>± typical miss</LegendItem>
      </ChartLegend>
    </ChartCard>
  )
}
