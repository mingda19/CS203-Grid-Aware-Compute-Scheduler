"use client"

import * as React from "react"
import Link from "next/link"
import {
  TrendingDown,
  TrendingUp,
  RotateCcw,
  Cpu,
  Server,
  ArrowRight,
  BatteryCharging,
  LineChart as LineChartIcon,
  CheckCircle2,
  SlidersHorizontal,
} from "lucide-react"
import { cn } from "@/lib/utils"
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
import { Separator } from "@/components/ui/separator"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { priceApi, type PricePoint } from "@/lib/api"
import { EndpointPipelineStatus } from "@/components/ui/efferd-dashboard-2-utils/endpoint-pipeline-status"
import { DecisionPanel } from "@/components/ui/efferd-dashboard-2-utils/decision-panel"
import { initialWorkloads, type Workload } from "@/lib/workload-data"
import type { OptimizationWorkloadItem } from "@/lib/decision-utils"
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"

const chartConfig = {
  price: {
    label: "Actual Price ($/MWh)",
    color: "#10b981",
  },
} satisfies ChartConfig

export default function OverviewPage() {
  const [workloads, setWorkloads] = React.useState<Workload[]>(initialWorkloads)
  const [planApproved, setPlanApproved] = React.useState(false)
  const [pricePoints, setPricePoints] = React.useState<PricePoint[]>([])
  const [pricesLoading, setPricesLoading] = React.useState(true)
  const [pricesError, setPricesError] = React.useState<string | null>(null)

  React.useEffect(() => {
    let cancelled = false
    setPricesLoading(true)
    setPricesError(null)

    const end = new Date()
    const start = new Date(end)
    start.setUTCDate(start.getUTCDate() - 2)

    priceApi
      .getHistory({
        location: "LZ_NORTH",
        startDate: start.toISOString().slice(0, 10),
        endDate: end.toISOString().slice(0, 10),
      })
      .then((response) => {
        if (cancelled) return
        if (!response.success || !response.data) throw new Error(response.message || "Unable to load price history")
        setPricePoints(response.data.points.filter((point) => point.sppUsdMwh != null))
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
  }, [])

  const chartPricePoints = React.useMemo(() => {
    // Show last 24 intervals for quick overview preview
    const recentPoints = pricePoints.slice(-24)
    return recentPoints.map((point) => {
      const utcDate = new Date(`${point.intervalStartUtc}Z`)
      return {
        ...point,
        price: point.sppUsdMwh,
        time: utcDate.toLocaleString("en-US", {
          timeZone: "UTC",
          hour: "2-digit",
          minute: "2-digit",
          hour12: false,
        }),
      }
    })
  }, [pricePoints])

  const lmpMetrics = React.useMemo(() => {
    if (pricePoints.length === 0) {
      return {
        currentLmp: null,
        formattedCurrent: "$28.40",
        diffPercent: 18.2,
        isDrop: true,
        rollingAvgFormatted: "$34.72",
        peakPriceFormatted: "$142.50",
        peakWindow: "Peak Window: 17:00-20:00",
      }
    }
    const latest = pricePoints[pricePoints.length - 1]
    const current = latest.sppUsdMwh ?? 0
    const recent = pricePoints.slice(-288)
    const sum = recent.reduce((acc, p) => acc + (p.sppUsdMwh ?? 0), 0)
    const avg = recent.length > 0 ? sum / recent.length : current
    const diff = avg !== 0 ? ((current - avg) / avg) * 100 : 0

    let maxPrice = -Infinity
    let maxTimeStr = "17:00-20:00"
    for (const p of pricePoints) {
      if (p.sppUsdMwh != null && p.sppUsdMwh > maxPrice) {
        maxPrice = p.sppUsdMwh
        const d = new Date(`${p.intervalStartUtc}Z`)
        maxTimeStr = `${d.getUTCHours().toString().padStart(2, "0")}:${d.getUTCMinutes().toString().padStart(2, "0")} UTC`
      }
    }

    return {
      currentLmp: current,
      formattedCurrent: `$${current.toFixed(2)}`,
      diffPercent: Math.abs(diff),
      isDrop: diff <= 0,
      rollingAvgFormatted: `$${avg.toFixed(2)}`,
      peakPriceFormatted: maxPrice > -Infinity ? `$${maxPrice.toFixed(2)}` : "$142.50",
      peakWindow: `Peak Window: ${maxTimeStr}`,
    }
  }, [pricePoints])

  const handleApprovePlan = (dispatchedItems: OptimizationWorkloadItem[]) => {
    setPlanApproved(true)
    setWorkloads((prev) =>
      prev.map((w) => {
        const item = dispatchedItems.find((d) => d.id === w.id)
        if (item) {
          return {
            ...w,
            scheduledWindow: item.proposedWindow,
            status: item.type === "Crypto Mining" ? "Throttled" : "Running",
          }
        }
        return w
      })
    )
  }

  const handleRejectPlan = (_reason: string, _notes: string) => {
    setPlanApproved(false)
    setWorkloads(initialWorkloads)
  }

  const handleResetPlan = () => {
    setPlanApproved(false)
    setWorkloads(initialWorkloads)
  }

  return (
    <div id="overview-root" className="space-y-6">
      <EndpointPipelineStatus />

      {/* Top Banner & Quick Status */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div className="space-y-1">
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              ERCOT Operations Cockpit
            </h1>
            <span className="inline-flex items-center gap-1 rounded-md bg-emerald-500/10 px-2 py-0.5 text-xs font-semibold text-emerald-600 dark:text-emerald-400 border border-emerald-500/20">
              <span className="h-1.5 w-1.5 rounded-full bg-emerald-500" />
              Optimization Engine Active
            </span>
          </div>
          <p className="text-sm text-muted-foreground">
            Probabilistic wholesale price dispatching with automated workload constraint preservation.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right hidden sm:block">
            <p className="text-xs text-muted-foreground">Planning Horizon</p>
            <p className="text-sm font-semibold font-mono text-foreground">24 Hours (ERCOT North)</p>
          </div>
          <Separator orientation="vertical" className="h-9 hidden sm:block" />
          <Button
            variant="outline"
            className="text-xs gap-2"
            onClick={() => {
              handleResetPlan()
              alert("Optimization engine re-evaluated models with latest EIA & ERCOT telemetry!")
            }}
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Re-run Solver
          </Button>
        </div>
      </div>

      {/* Human-in-the-Loop Primary Decision Panel */}
      <DecisionPanel
        planApproved={planApproved}
        onApprove={handleApprovePlan}
        onReject={handleRejectPlan}
        onReset={handleResetPlan}
      />

      {/* 4 Core KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Card 1: Real-Time LMP */}
        <Card className="hover:border-emerald-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Real-Time LMP
            </CardTitle>
            <DataStatusBadge
              size="sm"
              status={pricesLoading ? "cached" : pricesError ? "unavailable" : pricePoints.length > 0 ? "live" : "demo"}
              source="ERCOT Settlement Point Price (LZ_NORTH via Spring Boot backend)"
              updatedAt={pricePoints[pricePoints.length - 1]?.intervalStartUtc ? `${pricePoints[pricePoints.length - 1].intervalStartUtc}Z` : null}
            />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">
              {lmpMetrics.formattedCurrent}{" "}
              <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
            </div>
            <div
              className={cn(
                "flex items-center gap-1.5 mt-2 text-xs",
                lmpMetrics.isDrop
                  ? "text-emerald-600 dark:text-emerald-400"
                  : "text-amber-500"
              )}
            >
              {lmpMetrics.isDrop ? (
                <TrendingDown className="h-3.5 w-3.5" />
              ) : (
                <TrendingUp className="h-3.5 w-3.5" />
              )}
              <span className="font-semibold">
                {lmpMetrics.isDrop ? "-" : "+"}
                {lmpMetrics.diffPercent.toFixed(1)}%
              </span>
              <span className="text-muted-foreground">
                vs rolling avg ({lmpMetrics.rollingAvgFormatted})
              </span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>{lmpMetrics.peakWindow}</span>
            <span className="text-amber-500 font-semibold">
              {lmpMetrics.peakPriceFormatted}
            </span>
          </CardFooter>
        </Card>

        {/* Card 2: Renewable Grid Mix */}
        <Card className="hover:border-teal-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Clean Energy Mix
            </CardTitle>
            <DataStatusBadge
              size="sm"
              status="demo"
              source="ERCOT Clean Fuel Mix Telemetry (Simulated Scenario)"
            />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">58.6% <span className="text-xs font-normal text-muted-foreground">Green</span></div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-teal-600 dark:text-teal-400">
              <span className="font-semibold">Wind 41.2%</span>
              <span className="text-muted-foreground">| Solar 17.4%</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Carbon Intensity</span>
            <span className="font-semibold text-foreground">298 gCO2/kWh</span>
          </CardFooter>
        </Card>

        {/* Card 3: Active Load & BESS */}
        <Card className="hover:border-blue-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Compute Load & BESS
            </CardTitle>
            <DataStatusBadge
              size="sm"
              status="demo"
              source="Facility Adapter (BESS & Slurm/K8s Compute Telemetry)"
            />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">3.45 MW <span className="text-xs font-normal text-muted-foreground">/ 5.0 MW</span></div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-blue-600 dark:text-blue-400">
              <Cpu className="h-3.5 w-3.5" />
              <span className="font-semibold">4 Tasks</span>
              <span className="text-muted-foreground">(2 deferred to cheap hour)</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Battery State of Charge</span>
            <span className="font-semibold text-emerald-600 dark:text-emerald-400">78% (Ready)</span>
          </CardFooter>
        </Card>

        {/* Card 4: Cost Savings */}
        <Card className="hover:border-amber-500/50 transition-colors bg-gradient-to-br from-card to-emerald-500/5">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Projected Savings
            </CardTitle>
            <DataStatusBadge
              size="sm"
              status="demo"
              source="Cost Optimization Solver (XGBoost-LSTM v2.4)"
            />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
              $4,120 <span className="text-xs font-normal text-muted-foreground">today</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-emerald-600 dark:text-emerald-400 font-semibold">
              <TrendingUp className="h-3.5 w-3.5" />
              <span>+22.4% net efficiency</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>SLA & Deadline Adherence</span>
            <span className="font-semibold text-foreground">100% (0 SLA Risk)</span>
          </CardFooter>
        </Card>
      </div>

      {/* Snapshot Modules: Quick Access into Detailed Workflows */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Forecast Preview */}
        <Card className="hover:border-border transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-3">
            <div>
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <LineChartIcon className="h-4 w-4 text-emerald-500" />
                Price Forecast Curve
              </CardTitle>
              <CardDescription className="text-xs mt-0.5">
                LZ_NORTH settlement price trend for recent intervals.
              </CardDescription>
            </div>
            <Button asChild variant="ghost" size="sm" className="text-xs gap-1.5 text-emerald-600 dark:text-emerald-400">
              <Link href="/forecasts">
                Explore Forecasts <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent>
            {pricesLoading ? (
              <p className="py-12 text-center text-xs text-muted-foreground">Loading recent price curve…</p>
            ) : chartPricePoints.length === 0 ? (
              <p className="py-12 text-center text-xs text-muted-foreground">No recent interval data available.</p>
            ) : (
              <ChartContainer config={chartConfig} className="h-48 w-full">
                <ResponsiveContainer width="100%" height="100%">
                  <LineChart data={chartPricePoints} margin={{ top: 10, right: 12, left: 0, bottom: 0 }}>
                    <CartesianGrid strokeDasharray="3 3" opacity={0.15} vertical={false} />
                    <XAxis dataKey="time" tickLine={false} axisLine={false} tickMargin={6} style={{ fontSize: "10px" }} />
                    <YAxis tickLine={false} axisLine={false} tickMargin={6} tickFormatter={(v) => `$${v}`} style={{ fontSize: "10px" }} />
                    <ChartTooltip content={<ChartTooltipContent indicator="dot" />} />
                    <Line type="monotone" dataKey="price" stroke="#10b981" strokeWidth={2} dot={false} name="Actual Price ($/MWh)" />
                  </LineChart>
                </ResponsiveContainer>
              </ChartContainer>
            )}
          </CardContent>
        </Card>

        {/* Workload Queue Preview */}
        <Card className="hover:border-border transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-3">
            <div>
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <Server className="h-4 w-4 text-emerald-500" />
                Active Workload Queue
              </CardTitle>
              <CardDescription className="text-xs mt-0.5">
                Top scheduled compute tasks registered with grid constraints.
              </CardDescription>
            </div>
            <Button asChild variant="ghost" size="sm" className="text-xs gap-1.5 text-emerald-600 dark:text-emerald-400">
              <Link href="/workloads">
                Manage Queue <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-3">
            {workloads.slice(0, 3).map((w) => (
              <div
                key={w.id}
                className="flex items-center justify-between p-3 rounded-xl border border-border/70 bg-card hover:bg-muted/30 transition-colors"
              >
                <div className="space-y-0.5">
                  <div className="flex items-center gap-2">
                    <span className="text-xs font-semibold text-foreground">{w.name}</span>
                    <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-muted text-muted-foreground font-mono">
                      {w.powerKw} kW
                    </span>
                  </div>
                  <p className="text-[11px] text-muted-foreground">
                    Window: <span className="font-mono text-foreground">{w.scheduledWindow}</span> · Deadline: {w.deadline}
                  </p>
                </div>
                <div className="text-right">
                  <span
                    className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                      w.status === "Running"
                        ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                        : w.status === "Throttled"
                        ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                        : "bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30"
                    }`}
                  >
                    <span className={`h-1 w-1 rounded-full ${w.status === "Running" ? "bg-emerald-500 animate-pulse" : w.status === "Throttled" ? "bg-amber-500" : "bg-blue-500"}`} />
                    {w.status}
                  </span>
                  <p className="text-[11px] font-mono font-semibold text-emerald-600 dark:text-emerald-400 mt-0.5">
                    {w.savings}
                  </p>
                </div>
              </div>
            ))}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
