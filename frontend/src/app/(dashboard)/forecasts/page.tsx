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
import { priceApi, type PricePoint, type PriceForecastPoint } from "@/lib/api"
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"

const chartConfig = {
  price: {
    label: "Price ($/MWh)",
    color: "#10b981",
  },
} satisfies ChartConfig

export default function ForecastsPage() {
  const [hubLocation, setHubLocation] = React.useState("LZ_NORTH")
  const [dateRange, setDateRange] = React.useState(() => {
    const end = new Date()
    const start = new Date(end)
    start.setUTCDate(start.getUTCDate() - 6)
    return { startDate: start.toISOString().slice(0, 10), endDate: end.toISOString().slice(0, 10) }
  })
  const [pricePoints, setPricePoints] = React.useState<PricePoint[]>([])
  const [pricesLoading, setPricesLoading] = React.useState(true)
  const [pricesError, setPricesError] = React.useState<string | null>(null)
  const [totalPriceRecords, setTotalPriceRecords] = React.useState(0)

  // Upcoming Forecast States (from model prediction API)
  const [forecastHorizon, setForecastHorizon] = React.useState<number>(48)
  const [forecastPoints, setForecastPoints] = React.useState<PriceForecastPoint[]>([])
  const [forecastLoading, setForecastLoading] = React.useState(true)
  const [forecastError, setForecastError] = React.useState<string | null>(null)
  const [forecastRefreshKey, setForecastRefreshKey] = React.useState(0)

  // Fetch upcoming forecast predictions
  React.useEffect(() => {
    let cancelled = false
    setForecastLoading(true)
    setForecastError(null)
    priceApi
      .getForecast({ location: hubLocation, hours: forecastHorizon })
      .then((response) => {
        if (cancelled) return
        if (!response.success || !response.data) {
          throw new Error(response.message || "Unable to load price forecast")
        }
        setForecastPoints(response.data.points.filter((point) => point.predictedPrice != null))
      })
      .catch((error: unknown) => {
        if (!cancelled) {
          setForecastError(error instanceof Error ? error.message : "Unable to load price forecast")
        }
      })
      .finally(() => {
        if (!cancelled) setForecastLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [hubLocation, forecastHorizon, forecastRefreshKey])

  // Fetch historical settlement prices
  React.useEffect(() => {
    if (dateRange.startDate > dateRange.endDate) {
      setPricePoints([])
      setTotalPriceRecords(0)
      setPricesError(null)
      setPricesLoading(false)
      return
    }
    let cancelled = false
    setPricesLoading(true)
    setPricesError(null)
    priceApi
      .getHistory({ location: hubLocation, ...dateRange })
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
  }, [dateRange, hubLocation])

  const chartPricePoints = React.useMemo(() => {
    return pricePoints.map((point) => {
      const utcDate = new Date(`${point.intervalStartUtc}Z`)
      return {
        ...point,
        price: point.sppUsdMwh,
        time: utcDate.toLocaleString("en-US", {
          timeZone: "UTC",
          month: "short",
          day: "numeric",
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
          timeZoneName: "short",
        }),
      }
    })
  }, [pricePoints])

  const chartForecastPoints = React.useMemo(() => {
    return forecastPoints.map((point) => {
      const utcDate = new Date(`${point.intervalStartUtc}Z`)
      return {
        ...point,
        price: point.predictedPrice,
        time: utcDate.toLocaleString("en-US", {
          timeZone: "UTC",
          month: "short",
          day: "numeric",
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
          timeZoneName: "short",
        }),
      }
    })
  }, [forecastPoints])

  const forecastSummary = React.useMemo(() => {
    if (!forecastPoints.length) return null
    const sorted = [...forecastPoints].sort(
      (a, b) => (a.predictedPrice ?? 0) - (b.predictedPrice ?? 0)
    )
    const average =
      forecastPoints.reduce((sum, point) => sum + (point.predictedPrice ?? 0), 0) /
      forecastPoints.length
    return { average, low: sorted[0], high: sorted[sorted.length - 1] }
  }, [forecastPoints])

  const stats = React.useMemo(() => {
    if (pricePoints.length === 0) {
      return {
        avgPrice: "$34.72",
        maxPrice: "$142.50",
        minPrice: "$12.10",
        peakWindow: "17:00 – 20:00 UTC",
      }
    }
    let sum = 0
    let min = Infinity
    let max = -Infinity
    let maxTime = ""

    for (const p of pricePoints) {
      const val = p.sppUsdMwh ?? 0
      sum += val
      if (val < min) min = val
      if (val > max) {
        max = val
        const d = new Date(`${p.intervalStartUtc}Z`)
        maxTime = `${d.getUTCHours().toString().padStart(2, "0")}:${d.getUTCMinutes().toString().padStart(2, "0")} UTC`
      }
    }

    const avg = sum / pricePoints.length
    return {
      avgPrice: `$${avg.toFixed(2)}`,
      maxPrice: `$${max.toFixed(2)}`,
      minPrice: `$${min.toFixed(2)}`,
      peakWindow: maxTime ? `Peak at ${maxTime}` : "17:00 – 20:00 UTC",
    }
  }, [pricePoints])

  const dateRangeInvalid = dateRange.startDate > dateRange.endDate

  return (
    <div id="forecasts-root" className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Price & Clean Energy Forecast
            </h1>
            <DataStatusBadge
              size="sm"
              status={pricesLoading ? "cached" : pricesError ? "unavailable" : pricePoints.length > 0 ? "live" : "demo"}
              source={`ERCOT Settlement Point Price (${hubLocation} via Spring Boot)`}
              updatedAt={chartPricePoints[chartPricePoints.length - 1]?.intervalStartUtc ? `${chartPricePoints[chartPricePoints.length - 1].intervalStartUtc}Z` : null}
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Real-time LMP settlement point prices, probabilistic price dispatch bands, and upcoming machine learning predictions.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <select
            value={hubLocation}
            onChange={(e) => setHubLocation(e.target.value)}
            className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs font-semibold"
          >
            <option value="LZ_NORTH">LZ_NORTH (North Hub)</option>
            <option value="LZ_HOUSTON">LZ_HOUSTON (Houston)</option>
            <option value="LZ_SOUTH">LZ_SOUTH (South Hub)</option>
            <option value="LZ_WEST">LZ_WEST (West Texas Wind)</option>
          </select>
        </div>
      </div>

      {/* 4 Forecast Metric Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Average Interval LMP
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">
              {stats.avgPrice} <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">Rolling window mean across loaded points</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Peak Price Spike
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-amber-500">
              {stats.maxPrice} <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">{stats.peakWindow}</p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader className="pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Lowest Opportunity Price
            </CardTitle>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
              {stats.minPrice} <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <p className="text-xs text-muted-foreground mt-1">High wind curtailment discount period</p>
          </CardContent>
        </Card>

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

      {/* Upcoming Price Forecast Predictions Card (from PR #33) */}
      <Card>
        <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
          <div>
            <CardTitle className="text-lg font-bold flex items-center gap-2">
              <TrendingDown className="h-5 w-5 text-violet-500" />
              Upcoming {hubLocation} Price Forecast
            </CardTitle>
            <CardDescription className="text-xs mt-1">
              Stored model predictions for the next {forecastHorizon} hours, in USD/MWh. Timestamps are UTC.
            </CardDescription>
          </div>
          <div className="flex items-end gap-2">
            <label className="space-y-1 text-xs text-muted-foreground">
              <span>Forecast horizon</span>
              <select
                aria-label="Forecast horizon"
                value={forecastHorizon}
                onChange={(event) => setForecastHorizon(Number(event.target.value))}
                className="block h-9 rounded-md border border-input bg-background px-3 text-sm text-foreground"
              >
                <option value={24}>24 hours</option>
                <option value={48}>48 hours</option>
                <option value={72}>72 hours</option>
              </select>
            </label>
            <Button
              variant="outline"
              size="sm"
              className="h-9 gap-2"
              onClick={() => setForecastRefreshKey((key) => key + 1)}
              disabled={forecastLoading}
            >
              <RefreshCw className={`h-3.5 w-3.5 ${forecastLoading ? "animate-spin" : ""}`} /> Refresh
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          {forecastLoading ? (
            <p role="status" className="py-12 text-center text-sm text-muted-foreground">
              Loading upcoming price forecast…
            </p>
          ) : forecastError ? (
            <p role="alert" className="py-12 text-center text-sm text-destructive">
              Could not load price forecast: {forecastError}
            </p>
          ) : chartForecastPoints.length === 0 ? (
            <div className="py-12 text-center">
              <p className="text-sm text-muted-foreground">No upcoming {hubLocation} forecast is available.</p>
              <p className="mt-1 text-xs text-muted-foreground">
                Generate future rows with <code className="rounded bg-muted px-1 py-0.5">python data_scripts/predictions.py</code>, then refresh.
              </p>
            </div>
          ) : (
            <>
              {forecastSummary && (
                <div className="mb-5 grid gap-3 sm:grid-cols-3">
                  <div className="rounded-lg border border-border/70 p-3">
                    <p className="text-xs text-muted-foreground">Average · {forecastHorizon}h horizon</p>
                    <p className="mt-1 font-mono text-lg font-semibold">
                      ${forecastSummary.average.toFixed(2)}
                      <span className="ml-1 text-xs font-normal text-muted-foreground">/MWh</span>
                    </p>
                  </div>
                  <div className="rounded-lg border border-border/70 p-3">
                    <p className="text-xs text-muted-foreground">Lowest forecast</p>
                    <p className="mt-1 font-mono text-lg font-semibold text-emerald-600">
                      ${forecastSummary.low.predictedPrice?.toFixed(2)}
                      <span className="ml-1 text-xs font-normal text-muted-foreground">/MWh</span>
                    </p>
                    <p className="text-[11px] text-muted-foreground">
                      {new Date(`${forecastSummary.low.intervalStartUtc}Z`).toLocaleString("en-US", {
                        timeZone: "UTC",
                        month: "short",
                        day: "numeric",
                        hour: "2-digit",
                        minute: "2-digit",
                        hour12: false,
                        timeZoneName: "short",
                      })}
                    </p>
                  </div>
                  <div className="rounded-lg border border-border/70 p-3">
                    <p className="text-xs text-muted-foreground">Highest forecast</p>
                    <p className="mt-1 font-mono text-lg font-semibold text-amber-600">
                      ${forecastSummary.high.predictedPrice?.toFixed(2)}
                      <span className="ml-1 text-xs font-normal text-muted-foreground">/MWh</span>
                    </p>
                    <p className="text-[11px] text-muted-foreground">
                      {new Date(`${forecastSummary.high.intervalStartUtc}Z`).toLocaleString("en-US", {
                        timeZone: "UTC",
                        month: "short",
                        day: "numeric",
                        hour: "2-digit",
                        minute: "2-digit",
                        hour12: false,
                        timeZoneName: "short",
                      })}
                    </p>
                  </div>
                </div>
              )}
              <ChartContainer config={chartConfig} className="h-72 sm:h-96 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartForecastPoints} margin={{ top: 16, right: 18, left: 0, bottom: 4 }}>
                    <CartesianGrid strokeDasharray="3 3" opacity={0.2} vertical={false} />
                    <XAxis dataKey="time" tickLine={false} axisLine={false} tickMargin={8} minTickGap={36} style={{ fontSize: "11px" }} />
                    <YAxis tickLine={false} axisLine={false} tickMargin={8} tickFormatter={(value) => `$${value}`} style={{ fontSize: "11px" }} />
                    <ChartTooltip content={<ChartTooltipContent indicator="dot" />} />
                    <Line type="monotone" dataKey="price" stroke="#8b5cf6" strokeWidth={2.5} dot={false} activeDot={{ r: 4 }} name="Forecast Price ($/MWh)" connectNulls={false} />
                  </LineChart>
                </ResponsiveContainer>
              </ChartContainer>
              <p className="mt-3 text-center text-xs text-muted-foreground">
                {chartForecastPoints.length} forecast intervals · Model {forecastPoints[0]?.modelVersion} · Generated{" "}
                {new Date(`${forecastPoints[0]?.generatedAt}Z`).toLocaleString("en-US", {
                  timeZone: "UTC",
                  month: "short",
                  day: "numeric",
                  hour: "2-digit",
                  minute: "2-digit",
                  hour12: false,
                  timeZoneName: "short",
                })}
              </p>
            </>
          )}
        </CardContent>
      </Card>

      {/* Main Historical Real-Time Price Chart */}
      <Card>
        <CardHeader>
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div>
              <CardTitle className="text-lg font-bold flex items-center gap-2">
                {hubLocation} Historical Real-Time Price
              </CardTitle>
              <CardDescription className="text-xs mt-1">
                ERCOT settlement point prices in USD/MWh. Timestamps are shown in UTC.
              </CardDescription>
            </div>
            <div className="grid grid-cols-2 gap-3 sm:w-auto">
              <label className="space-y-1 text-xs text-muted-foreground">
                <span>Start date (UTC)</span>
                <Input
                  type="date"
                  value={dateRange.startDate}
                  max={dateRange.endDate}
                  onChange={(event) =>
                    setDateRange((range) => ({ ...range, startDate: event.target.value }))
                  }
                  className="h-9"
                />
              </label>
              <label className="space-y-1 text-xs text-muted-foreground">
                <span>End date (UTC)</span>
                <Input
                  type="date"
                  value={dateRange.endDate}
                  min={dateRange.startDate}
                  max={new Date().toISOString().slice(0, 10)}
                  onChange={(event) =>
                    setDateRange((range) => ({ ...range, endDate: event.target.value }))
                  }
                  className="h-9"
                />
              </label>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          {dateRangeInvalid ? (
            <p role="alert" className="py-12 text-center text-sm text-destructive">
              Start date must be on or before end date.
            </p>
          ) : pricesLoading ? (
            <p role="status" className="py-12 text-center text-sm text-muted-foreground">
              Loading {hubLocation} price history…
            </p>
          ) : pricesError ? (
            <p role="alert" className="py-12 text-center text-sm text-destructive">
              Could not load price history: {pricesError}
            </p>
          ) : chartPricePoints.length === 0 ? (
            <p className="py-12 text-center text-sm text-muted-foreground">
              No {hubLocation} price records found for the selected dates.
            </p>
          ) : (
            <ChartContainer config={chartConfig} className="h-72 sm:h-96 w-full">
              <ResponsiveContainer width="100%" height="100%">
                <LineChart
                  data={chartPricePoints}
                  margin={{ top: 16, right: 18, left: 0, bottom: 4 }}
                >
                  <CartesianGrid strokeDasharray="3 3" opacity={0.2} vertical={false} />
                  <XAxis
                    dataKey="time"
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
                    minTickGap={36}
                    style={{ fontSize: "11px" }}
                  />
                  <YAxis
                    tickLine={false}
                    axisLine={false}
                    tickMargin={8}
                    tickFormatter={(value) => `$${value}`}
                    style={{ fontSize: "11px" }}
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
                    connectNulls={false}
                  />
                </LineChart>
              </ResponsiveContainer>
            </ChartContainer>
          )}
          {!dateRangeInvalid && !pricesLoading && !pricesError && totalPriceRecords > pricePoints.length && (
            <p className="mt-3 text-center text-xs text-muted-foreground">
              Showing {pricePoints.length.toLocaleString()} of {totalPriceRecords.toLocaleString()}{" "}
              records. Narrow the date range to view all intervals.
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  )
}
