"use client"

import * as React from "react"
import {
  TrendingDown,
  TrendingUp,
  Wind,
  Sun,
  Zap,
  Calendar,
  AlertTriangle,
  Info,
  Clock,
  ArrowUpRight,
  Sparkles,
  RefreshCw,
  Search,
} from "lucide-react"
import {
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  ResponsiveContainer,
  LineChart,
} from "recharts"
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { priceApi, type PricePoint } from "@/lib/api"
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"
import { UnifiedPriceTimeline } from "@/components/forecasts/UnifiedPriceTimeline"
import {
  formatSgtDateTime,
  formatSgtChartTick,
  formatSgtDate,
  formatSgtTime,
  sgtDateToUtcIso,
} from "@/lib/date-utils"

const chartConfig = {
  price: {
    label: "Price ($/MWh)",
    color: "#10b981",
  },
} satisfies ChartConfig

export default function ForecastsPage() {
  const [hubLocation, setHubLocation] = React.useState("LZ_NORTH")
  const [showAdvancedHistory, setShowAdvancedHistory] = React.useState(false)

  // Custom historical date query state in SGT
  const [dateRange, setDateRange] = React.useState(() => {
    const end = new Date()
    const start = new Date(end)
    start.setUTCDate(start.getUTCDate() - 6)
    return {
      startDate: start.toISOString().slice(0, 10),
      endDate: end.toISOString().slice(0, 10),
    }
  })

  const [pricePoints, setPricePoints] = React.useState<PricePoint[]>([])
  const [pricesLoading, setPricesLoading] = React.useState(false)
  const [pricesError, setPricesError] = React.useState<string | null>(null)
  const [totalPriceRecords, setTotalPriceRecords] = React.useState(0)

  // Fetch custom historical range if advanced drawer is opened
  React.useEffect(() => {
    if (!showAdvancedHistory) return
    if (dateRange.startDate > dateRange.endDate) {
      setPricePoints([])
      setTotalPriceRecords(0)
      setPricesError(null)
      return
    }

    let cancelled = false
    setPricesLoading(true)
    setPricesError(null)

    // Convert SGT date range boundaries to UTC ISO strings for backend
    const startUtc = sgtDateToUtcIso(dateRange.startDate, false).slice(0, 10)
    const endUtc = sgtDateToUtcIso(dateRange.endDate, true).slice(0, 10)

    priceApi
      .getHistory({
        location: hubLocation,
        startDate: startUtc,
        endDate: endUtc,
      })
      .then((response) => {
        if (cancelled) return
        if (!response.success || !response.data) throw new Error(response.message || "Unable to load price history")
        setPricePoints(response.data.points.filter((point) => point.sppUsdMwh != null))
        setTotalPriceRecords(response.data.totalRecords)
      })
      .catch((error: unknown) => {
        if (!cancelled) setPricesError(error instanceof Error ? error.message : "Unable to load price history")
      })
      .finally(() => {
        if (!cancelled) setPricesLoading(false)
      })

    return () => {
      cancelled = true
    }
  }, [showAdvancedHistory, dateRange, hubLocation])

  const chartCustomPricePoints = React.useMemo(() => {
    return pricePoints.map((point) => {
      const rawUtc = `${point.intervalStartUtc}Z`
      return {
        ...point,
        price: point.sppUsdMwh,
        time: formatSgtChartTick(rawUtc, true),
      }
    })
  }, [pricePoints])

  const dateRangeInvalid = dateRange.startDate > dateRange.endDate

  return (
    <div id="forecasts-root" className="space-y-6">
      {/* Workstation Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2 flex-wrap">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Price & Clean Energy Forecast
            </h1>
            <DataStatusBadge
              size="sm"
              status="live"
              source={`ERCOT Settlement Point Price & XGBoost ML Predictions (${hubLocation})`}
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Continuous LMP timeline bridging historical settlement actuals and upcoming ML forecasts in standardized <strong>Singapore Time (SGT / UTC+8)</strong>.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <label htmlFor="hub-select" className="text-xs font-medium text-muted-foreground hidden sm:inline">
            Load Zone:
          </label>
          <select
            id="hub-select"
            value={hubLocation}
            onChange={(e) => setHubLocation(e.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs font-semibold text-foreground focus:ring-1 focus:ring-primary"
          >
            <option value="LZ_NORTH">LZ_NORTH (North Hub)</option>
            <option value="LZ_HOUSTON">LZ_HOUSTON (Houston)</option>
            <option value="LZ_SOUTH">LZ_SOUTH (South Hub)</option>
            <option value="LZ_WEST">LZ_WEST (West Texas Wind)</option>
          </select>
        </div>
      </div>

      {/* 4 Forecast Telemetry Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Card 1: Interval LMP Mean */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Average Interval LMP
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">
              $34.72 <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">Rolling 24h operational window mean</p>
          </CardContent>
        </Card>

        {/* Card 2: Peak Spike in SGT */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Impending Peak Spike
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-amber-500">
              $142.50 <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">
              Peak window: <strong className="text-foreground">01:00 – 04:00 SGT</strong>
            </p>
          </CardContent>
        </Card>

        {/* Card 3: Opportunity Valley in SGT */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Lowest Opportunity Price
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
              $14.80 <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">
              Wind corridor: <strong className="text-foreground">09:30 – 13:30 SGT</strong>
            </p>
          </CardContent>
        </Card>

        {/* Card 4: Renewable Grid Generation */}
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Renewable Output
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-teal-600 dark:text-teal-400">
              58.6% <span className="text-xs font-normal text-muted-foreground">Clean</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">Wind 41.2% · Solar 17.4%</p>
          </CardContent>
        </Card>
      </div>

      {/* Primary Workstation: Continuous Unified Price Timeline */}
      <UnifiedPriceTimeline
        hubLocation={hubLocation}
        onLocationChange={setHubLocation}
        variant="full"
        height={380}
      />

      {/* Collapsible Regulatory & Custom Historical Inspection Drawer */}
      <div className="pt-2">
        <div className="flex items-center justify-between mb-3">
          <Button
            variant="outline"
            size="sm"
            onClick={() => setShowAdvancedHistory((prev) => !prev)}
            className="text-xs gap-1.5"
          >
            <Search className="h-3.5 w-3.5" />
            {showAdvancedHistory ? "Hide Custom Date Inspector" : "Inspect Custom Historical Date Range (SGT)"}
          </Button>
          <span className="text-xs text-muted-foreground font-mono">
            Timezone standard: Asia/Singapore (UTC+8)
          </span>
        </div>

        {showAdvancedHistory && (
          <Card className="border border-border">
            <CardHeader className="pb-3">
              <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
                <div>
                  <CardTitle className="text-sm font-bold flex items-center gap-2">
                    <Calendar className="h-4 w-4 text-primary" />
                    Custom Historical Settlement Range Inspector
                  </CardTitle>
                  <CardDescription className="text-xs mt-0.5">
                    Query historical 15-minute settlement intervals by Singapore calendar date.
                  </CardDescription>
                </div>

                <div className="grid grid-cols-2 gap-3 sm:w-auto">
                  <label className="space-y-1 text-xs text-muted-foreground">
                    <span>Start date (SGT)</span>
                    <Input
                      type="date"
                      value={dateRange.startDate}
                      max={dateRange.endDate}
                      onChange={(event) =>
                        setDateRange((range) => ({ ...range, startDate: event.target.value }))
                      }
                      className="h-8 text-xs"
                    />
                  </label>
                  <label className="space-y-1 text-xs text-muted-foreground">
                    <span>End date (SGT)</span>
                    <Input
                      type="date"
                      value={dateRange.endDate}
                      min={dateRange.startDate}
                      max={new Date().toISOString().slice(0, 10)}
                      onChange={(event) =>
                        setDateRange((range) => ({ ...range, endDate: event.target.value }))
                      }
                      className="h-8 text-xs"
                    />
                  </label>
                </div>
              </div>
            </CardHeader>
            <CardContent>
              {dateRangeInvalid ? (
                <p role="alert" className="py-8 text-center text-xs text-destructive">
                  Start date must be on or before end date.
                </p>
              ) : pricesLoading ? (
                <p role="status" className="py-8 text-center text-xs text-muted-foreground flex items-center justify-center gap-2">
                  <RefreshCw className="h-3.5 w-3.5 animate-spin text-emerald-500" />
                  Loading {hubLocation} custom price history…
                </p>
              ) : pricesError ? (
                <p role="alert" className="py-8 text-center text-xs text-destructive">
                  Could not load price history: {pricesError}
                </p>
              ) : chartCustomPricePoints.length === 0 ? (
                <p className="py-8 text-center text-xs text-muted-foreground">
                  No {hubLocation} price records found for the selected dates.
                </p>
              ) : (
                <ChartContainer config={chartConfig} className="h-64 w-full">
                  <ResponsiveContainer width="100%" height="100%">
                    <LineChart
                      data={chartCustomPricePoints}
                      margin={{ top: 12, right: 18, left: 0, bottom: 4 }}
                    >
                      <CartesianGrid strokeDasharray="3 3" opacity={0.15} vertical={false} />
                      <XAxis
                        dataKey="time"
                        tickLine={false}
                        axisLine={false}
                        tickMargin={8}
                        minTickGap={36}
                        style={{ fontSize: "10px" }}
                      />
                      <YAxis
                        tickLine={false}
                        axisLine={false}
                        tickMargin={8}
                        tickFormatter={(value) => `$${value}`}
                        style={{ fontSize: "10px" }}
                      />
                      <ChartTooltip content={<ChartTooltipContent indicator="dot" />} />
                      <Line
                        type="monotone"
                        dataKey="price"
                        stroke="#10b981"
                        strokeWidth={2}
                        dot={false}
                        activeDot={{ r: 4 }}
                        name="Actual Price ($/MWh)"
                      />
                    </LineChart>
                  </ResponsiveContainer>
                </ChartContainer>
              )}
            </CardContent>
          </Card>
        )}
      </div>
    </div>
  )
}
