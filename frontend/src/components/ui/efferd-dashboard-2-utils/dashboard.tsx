"use client"

import * as React from "react"
import {
  Activity,
  AlertCircle,
  Zap,
  TrendingDown,
  TrendingUp,
  AlertTriangle,
  CheckCircle2,
  Clock,
  Cpu,
  Wind,
  Sun,
  Battery,
  ShieldAlert,
  ArrowUpRight,
  Info,
  Server,
  Play,
  Pause,
  RotateCcw,
  Sparkles,
  Layers,
  ChevronRight,
  CircleDashed,
  RefreshCw,
  XCircle,
} from "lucide-react"
import {
  AreaChart,
  Area,
  Line,
  Bar,
  XAxis,
  YAxis,
  CartesianGrid,
  ResponsiveContainer,
  ComposedChart,
  Tooltip as RechartsTooltip,
} from "recharts"

import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import {
  Sheet,
  SheetContent,
  SheetDescription,
  SheetHeader,
  SheetTitle,
  SheetTrigger,
  SheetFooter,
} from "@/components/ui/sheet"
import {
  ChartContainer,
  ChartTooltip,
  ChartTooltipContent,
  type ChartConfig,
} from "@/components/ui/chart"

// 24-hour ERCOT wholesale price and compute schedule dataset
const forecastData = [
  { time: "00:00", forecastPrice: 22.4, actualPrice: 21.8, scheduledMW: 4.8, baselineMW: 3.2, windGen: 72 },
  { time: "02:00", forecastPrice: 16.5, actualPrice: 15.9, scheduledMW: 5.0, baselineMW: 3.2, windGen: 84 },
  { time: "04:00", forecastPrice: 14.8, actualPrice: 14.2, scheduledMW: 5.0, baselineMW: 3.2, windGen: 89 },
  { time: "06:00", forecastPrice: 24.1, actualPrice: 25.0, scheduledMW: 3.8, baselineMW: 3.5, windGen: 65 },
  { time: "08:00", forecastPrice: 38.5, actualPrice: 37.1, scheduledMW: 2.1, baselineMW: 3.6, windGen: 45 },
  { time: "10:00", forecastPrice: 29.2, actualPrice: 28.0, scheduledMW: 3.5, baselineMW: 3.4, windGen: 38 },
  { time: "12:00", forecastPrice: 22.0, actualPrice: 23.4, scheduledMW: 4.2, baselineMW: 3.3, windGen: 30 },
  { time: "14:00", forecastPrice: 26.7, actualPrice: 27.5, scheduledMW: 3.9, baselineMW: 3.2, windGen: 32 },
  { time: "16:00", forecastPrice: 54.0, actualPrice: 58.2, scheduledMW: 2.2, baselineMW: 3.5, windGen: 28 },
  { time: "18:00", forecastPrice: 142.5, actualPrice: null, scheduledMW: 0.8, baselineMW: 3.6, windGen: 22 }, // Extreme Peak avoided!
  { time: "20:00", forecastPrice: 88.0, actualPrice: null, scheduledMW: 1.2, baselineMW: 3.5, windGen: 34 },
  { time: "22:00", forecastPrice: 32.5, actualPrice: null, scheduledMW: 4.5, baselineMW: 3.2, windGen: 68 },
]

const chartConfig = {
  forecastPrice: {
    label: "Forecast Price ($/MWh)",
    color: "#f59e0b", // Amber
  },
  actualPrice: {
    label: "Actual RT Price ($/MWh)",
    color: "#10b981", // Emerald
  },
  scheduledMW: {
    label: "Optimized Load (MW)",
    color: "#3b82f6", // Blue
  },
  baselineMW: {
    label: "Fixed Baseline (MW)",
    color: "#94a3b8", // Slate
  },
} satisfies ChartConfig

interface Workload {
  id: string
  name: string
  type: "ML Training" | "Crypto Mining" | "HPC Batch" | "HVAC Pre-Cool"
  powerKw: number
  scheduledWindow: string
  deadline: string
  savings: string
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  machineCluster: string
}

type EndpointState = "checking" | "online" | "offline"
type EndpointCheck = { name: string; path: string; state: EndpointState; latency?: number }

const monitoredEndpoints = [
  { name: "Authentication API", path: "/api/auth/me" },
  { name: "Admin API", path: "/api/admin/users" },
]
const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080"

function EndpointPipelineStatus() {
  const [checks, setChecks] = React.useState<EndpointCheck[]>(monitoredEndpoints.map((item) => ({ ...item, state: "checking" as const })))
  const [lastChecked, setLastChecked] = React.useState<Date | null>(null)
  const [refreshing, setRefreshing] = React.useState(false)

  const refresh = React.useCallback(async () => {
    setRefreshing(true)
    setChecks(monitoredEndpoints.map((item) => ({ ...item, state: "checking" as const })))
    const next = await Promise.all(monitoredEndpoints.map(async (item): Promise<EndpointCheck> => {
      const started = performance.now()
      try {
        const response = await fetch(`${apiBase}${item.path}`, { credentials: "include", cache: "no-store" })
        return {
          ...item,
          // These endpoints require authentication; 401/403 still confirms the API is reachable.
          state: response.ok || response.status === 401 || response.status === 403 ? "online" : "offline",
          latency: Math.round(performance.now() - started),
        }
      } catch {
        return { ...item, state: "offline", latency: Math.round(performance.now() - started) }
      }
    }))
    setChecks(next)
    setLastChecked(new Date())
    setRefreshing(false)
  }, [])

  React.useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(), 30_000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const onlineCount = checks.filter((check) => check.state === "online").length
  const pipelineStages = ["Grid data ingestion", "Forecast generation", "Workload optimization", "Schedule dispatch"]

  return (
    <Card>
      <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <CardTitle className="flex items-center gap-2"><Activity className="h-4 w-4 text-emerald-500" />Pipeline & endpoint status</CardTitle>
          <CardDescription className="mt-1">Backend connectivity checks and pipeline telemetry availability.</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => void refresh()} disabled={refreshing} className="gap-2">
          <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`} /> Refresh
        </Button>
      </CardHeader>
      <CardContent className="grid gap-5 lg:grid-cols-2">
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold uppercase tracking-wider text-muted-foreground">API endpoints</span>
            <span className="text-muted-foreground">{onlineCount}/{checks.length} reachable</span>
          </div>
          {checks.map((check) => (
            <div key={check.path} className="flex items-center justify-between rounded-lg border border-border/70 px-3 py-2.5">
              <div className="flex items-center gap-2.5">
                {check.state === "checking" ? <CircleDashed className="h-4 w-4 animate-spin text-muted-foreground" /> : check.state === "online" ? <CheckCircle2 className="h-4 w-4 text-emerald-500" /> : <XCircle className="h-4 w-4 text-red-500" />}
                <div><p className="text-sm font-medium">{check.name}</p><code className="text-[11px] text-muted-foreground">{check.path}</code></div>
              </div>
              <span className="text-xs text-muted-foreground">{check.state === "checking" ? "Checking" : check.state === "online" ? `${check.latency} ms` : "Offline"}</span>
            </div>
          ))}
          <p className="text-[11px] text-muted-foreground">Last checked {lastChecked?.toLocaleTimeString() ?? "—"} · auto refresh 30 sec</p>
        </div>
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold uppercase tracking-wider text-muted-foreground">Pipeline stages</span>
            <span className="inline-flex items-center gap-1 text-amber-500"><AlertCircle className="h-3.5 w-3.5" />No telemetry API</span>
          </div>
          {pipelineStages.map((stage, index) => (
            <div key={stage} className="flex items-center gap-2.5 rounded-lg border border-border/70 px-3 py-2.5">
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[10px] text-muted-foreground">{index + 1}</span>
              <span className="text-sm">{stage}</span>
              <span className="ml-auto text-[11px] text-muted-foreground">No status data</span>
            </div>
          ))}
          <p className="text-[11px] text-muted-foreground">Pipeline status will appear when execution health endpoints are available.</p>
        </div>
      </CardContent>
    </Card>
  )
}

const initialWorkloads: Workload[] = [
  {
    id: "WL-409",
    name: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    powerKw: 1200,
    scheduledWindow: "01:30 – 05:30 UTC",
    deadline: "10:00 AM UTC",
    savings: "$1,840 (27%)",
    status: "Scheduled",
    machineCluster: "64x NVIDIA H100 SXM5",
  },
  {
    id: "WL-108",
    name: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    powerKw: 1800,
    scheduledWindow: "00:00 – 16:30 UTC",
    deadline: "Flexible Throughput",
    savings: "$1,450 (31%)",
    status: "Running",
    machineCluster: "Antminer S19 Pro+ Pod 2",
  },
  {
    id: "WL-812",
    name: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    powerKw: 450,
    scheduledWindow: "02:00 – 04:30 UTC",
    deadline: "08:00 AM UTC",
    savings: "$520 (19%)",
    status: "Scheduled",
    machineCluster: "Slurm HPC Cluster (96 Nodes)",
  },
  {
    id: "WL-022",
    name: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    powerKw: 320,
    scheduledWindow: "13:30 – 16:00 UTC",
    deadline: "Peak Window (17:00)",
    savings: "$310 (16%)",
    status: "Running",
    machineCluster: "Trane Centrifugal Chiller Bank",
  },
]

export function Dashboard() {
  const [workloads, setWorkloads] = React.useState<Workload[]>(initialWorkloads)
  const [planApproved, setPlanApproved] = React.useState(false)
  const [isRejecting, setIsRejecting] = React.useState(false)
  const [activeMetricView, setActiveMetricView] = React.useState<"price" | "power" | "wind">("price")

  const handleApprovePlan = () => {
    setPlanApproved(true)
  }

  const handleToggleWorkload = (id: string) => {
    setWorkloads((prev) =>
      prev.map((w) => {
        if (w.id === id) {
          return {
            ...w,
            status: w.status === "Running" ? "Throttled" : w.status === "Throttled" ? "Running" : "Running",
          }
        }
        return w
      })
    )
  }

  return (
    <div className="space-y-6">
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
              setPlanApproved(false)
              alert("Optimization engine re-evaluated models with latest EIA & ERCOT telemetry!")
            }}
          >
            <RotateCcw className="h-3.5 w-3.5" />
            Re-run Solver
          </Button>
        </div>
      </div>

      {/* 4 Core KPI Cards */}
      <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Card 1: Real-Time LMP */}
        <Card className="hover:border-emerald-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Real-Time LMP
            </CardTitle>
            <div className="h-8 w-8 rounded-lg bg-emerald-500/10 flex items-center justify-center text-emerald-600 dark:text-emerald-400">
              <Zap className="h-4 w-4" />
            </div>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">$28.40 <span className="text-xs font-normal text-muted-foreground">/ MWh</span></div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-emerald-600 dark:text-emerald-400">
              <TrendingDown className="h-3.5 w-3.5" />
              <span className="font-semibold">-18.2%</span>
              <span className="text-muted-foreground">vs 24h rolling avg</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Peak Window: 17:00-20:00</span>
            <span className="text-amber-500 font-semibold">$142.50</span>
          </CardFooter>
        </Card>

        {/* Card 2: Renewable Grid Mix */}
        <Card className="hover:border-teal-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Clean Energy Mix
            </CardTitle>
            <div className="h-8 w-8 rounded-lg bg-teal-500/10 flex items-center justify-center text-teal-600 dark:text-teal-400">
              <Wind className="h-4 w-4" />
            </div>
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
            <div className="h-8 w-8 rounded-lg bg-blue-500/10 flex items-center justify-center text-blue-600 dark:text-blue-400">
              <Battery className="h-4 w-4" />
            </div>
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
            <div className="h-8 w-8 rounded-lg bg-emerald-500/10 flex items-center justify-center text-emerald-600 dark:text-emerald-400">
              <Sparkles className="h-4 w-4" />
            </div>
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

      {/* Human-in-the-Loop Pending Schedule Recommendation Banner (FR-09, FR-10) */}
      <div id="approvals" className="scroll-mt-20">
        <Card className={`border-2 transition-all ${planApproved ? "border-emerald-500/50 bg-emerald-500/5" : "border-amber-500/50 bg-amber-500/5"}`}>
          <CardHeader className="pb-3">
            <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
              <div className="flex items-center gap-2.5">
                <div className={`p-2 rounded-lg ${planApproved ? "bg-emerald-500 text-white" : "bg-amber-500 text-white"}`}>
                  {planApproved ? <CheckCircle2 className="h-5 w-5" /> : <ShieldAlert className="h-5 w-5" />}
                </div>
                <div>
                  <CardTitle className="text-base font-bold">
                    {planApproved
                      ? "Optimization Plan #2026-0920-04 Approved & Dispatched"
                      : "Human-in-the-Loop Schedule Recommendation #2026-0920-04"}
                  </CardTitle>
                  <CardDescription className="text-xs">
                    {planApproved
                      ? "Workload dispatch instructions sent to Slurm and Kubernetes adapters."
                      : "Action required: Review proposed schedule adjustments to avoid ERCOT price spike."}
                  </CardDescription>
                </div>
              </div>
              <div className="flex items-center gap-2">
                <span className={`text-xs font-semibold px-2.5 py-1 rounded-full ${planApproved ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30" : "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"}`}>
                  {planApproved ? "STATUS: EXECUTING" : "PENDING OPERATOR SIGN-OFF"}
                </span>
              </div>
            </div>
          </CardHeader>
          <CardContent className="space-y-3 text-sm">
            <div className="p-4 rounded-xl bg-background/80 border border-border leading-relaxed text-xs sm:text-sm text-foreground space-y-2">
              <div className="flex items-center gap-2 font-semibold text-emerald-600 dark:text-emerald-400 text-xs">
                <Info className="h-4 w-4" /> Plain-English Decision Rationale
              </div>
              <p>
                Shifting <strong>Llama-3 Fine-Tuning Run #4</strong> and <strong>Monte Carlo Risk Batch #812</strong> forward into the <strong>01:30 – 05:30 UTC</strong> window takes advantage of sustained West Texas wind generation (curtailed prices at <strong>$14.80/MWh</strong>).
                During the predicted <strong>17:00 – 20:00 UTC</strong> ERCOT thermal spike (forecasted at <strong>$142.50/MWh</strong> due to solar ramp-down), flexible mining load will be throttled and facility cooling will run on thermal pre-chill reserves.
              </p>
              <div className="flex flex-wrap items-center gap-4 pt-1 text-xs text-muted-foreground font-mono">
                <span>⚡ Projected Net Savings: <strong className="text-foreground font-bold">$4,120 (22.4%)</strong></span>
                <span>⏱️ Slack Buffer: <strong className="text-foreground font-bold">2.5 hrs</strong></span>
                <span>🎯 Forecast Confidence: <strong className="text-emerald-500 font-bold">94.8%</strong></span>
              </div>
            </div>
          </CardContent>
          <CardFooter className="flex flex-wrap items-center justify-between gap-3 pt-0">
            <span className="text-xs text-muted-foreground">
              Model: XGBoost-LSTM Hybrid (v2.4) • Ingestion Latency: 1.2m
            </span>
            <div className="flex items-center gap-2">
              {!planApproved ? (
                <>
                  <Button
                    variant="outline"
                    size="sm"
                    className="text-xs text-destructive hover:bg-destructive/10"
                    onClick={() => {
                      if (confirm("Reject proposed optimization and keep standard fixed schedule?")) {
                        alert("Optimization dismissed. Standard fixed schedule maintained.")
                      }
                    }}
                  >
                    Reject Plan
                  </Button>
                  <Button
                    size="sm"
                    className="text-xs bg-emerald-600 hover:bg-emerald-700 text-white font-semibold gap-1.5 shadow-sm"
                    onClick={handleApprovePlan}
                  >
                    <CheckCircle2 className="h-4 w-4" />
                    Approve & Dispatch Workloads
                  </Button>
                </>
              ) : (
                <Button
                  variant="outline"
                  size="sm"
                  className="text-xs"
                  onClick={() => setPlanApproved(false)}
                >
                  Modify Schedule
                </Button>
              )}
            </div>
          </CardFooter>
        </Card>
      </div>

      {/* Main Chart Section: Price Forecast vs Workload Scheduling */}
      <Card id="forecasts" className="scroll-mt-20">
        <CardHeader>
          <div className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3">
            <div>
              <CardTitle className="text-lg font-bold flex items-center gap-2">
                24-Hour ERCOT Electricity Price Curve & Compute Dispatch Plan
              </CardTitle>
              <CardDescription className="text-xs mt-1">
                Comparing forecasted wholesale LMP ($/MWh) against optimized flexible compute power (MW).
              </CardDescription>
            </div>
            <div className="flex items-center gap-2 text-xs">
              <Button
                variant={activeMetricView === "price" ? "default" : "outline"}
                size="sm"
                className="h-8 text-xs"
                onClick={() => setActiveMetricView("price")}
              >
                Price vs Load
              </Button>
              <Button
                variant={activeMetricView === "wind" ? "default" : "outline"}
                size="sm"
                className="h-8 text-xs"
                onClick={() => setActiveMetricView("wind")}
              >
                Wind Generation %
              </Button>
            </div>
          </div>
        </CardHeader>
        <CardContent>
          <ChartContainer config={chartConfig} className="h-72 sm:h-96 w-full">
            <ResponsiveContainer width="100%" height="100%">
              <ComposedChart data={forecastData} margin={{ top: 20, right: 20, left: -10, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" opacity={0.2} vertical={false} />
                <XAxis
                  dataKey="time"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  style={{ fontSize: "11px" }}
                />
                <YAxis
                  yAxisId="left"
                  orientation="left"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  tickFormatter={(val) => `$${val}`}
                  style={{ fontSize: "11px" }}
                />
                <YAxis
                  yAxisId="right"
                  orientation="right"
                  tickLine={false}
                  axisLine={false}
                  tickMargin={8}
                  tickFormatter={(val) => `${val} MW`}
                  style={{ fontSize: "11px" }}
                />
                <ChartTooltip content={<ChartTooltipContent indicator="dot" />} />
                
                {/* Visual Area for Cheap Nighttime Energy Valley */}
                <Area
                  yAxisId="left"
                  type="monotone"
                  dataKey="forecastPrice"
                  fill="#f59e0b"
                  fillOpacity={0.15}
                  stroke="#f59e0b"
                  strokeWidth={2}
                  name="Forecast Price ($/MWh)"
                />

                {/* Actual Real-Time price points recorded so far */}
                <Line
                  yAxisId="left"
                  type="monotone"
                  dataKey="actualPrice"
                  stroke="#10b981"
                  strokeWidth={3}
                  dot={{ r: 4, fill: "#10b981" }}
                  name="Actual RT Price ($/MWh)"
                />

                {/* Optimized Scheduled Load in MW */}
                <Bar
                  yAxisId="right"
                  dataKey="scheduledMW"
                  fill="#3b82f6"
                  radius={[4, 4, 0, 0]}
                  fillOpacity={0.7}
                  name="Optimized Load (MW)"
                />

                {/* Fixed Baseline Comparison */}
                <Line
                  yAxisId="right"
                  type="stepAfter"
                  dataKey="baselineMW"
                  stroke="#94a3b8"
                  strokeWidth={1.5}
                  strokeDasharray="4 4"
                  dot={false}
                  name="Fixed Baseline (MW)"
                />
              </ComposedChart>
            </ResponsiveContainer>
          </ChartContainer>

          <div className="flex flex-wrap items-center justify-center gap-6 mt-4 pt-4 border-t border-border text-xs text-muted-foreground">
            <div className="flex items-center gap-2">
              <span className="h-3 w-3 rounded-full bg-amber-500" />
              <span>Forecasted Price ($/MWh)</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="h-3 w-3 rounded-full bg-emerald-500" />
              <span>Recorded Actual Price ($/MWh)</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="h-3 w-3 rounded-md bg-blue-500" />
              <span>Optimized Workload Power (MW)</span>
            </div>
            <div className="flex items-center gap-2">
              <span className="h-1 w-4 bg-slate-400 border-dashed" />
              <span>Unresponsive Baseline (MW)</span>
            </div>
          </div>
        </CardContent>
      </Card>

      {/* Workload Queue & Dispatch Management Table */}
      <Card id="workloads" className="scroll-mt-20">
        <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div>
            <CardTitle className="text-base font-bold flex items-center gap-2">
              <Server className="h-4 w-4 text-emerald-500" />
              Flexible Workload Queue & Execution Schedule
            </CardTitle>
            <CardDescription className="text-xs mt-0.5">
              Active workloads registered with hardware capacity limits, precedence rules, and deadlines.
            </CardDescription>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="outline" size="sm" className="text-xs">
              Filter by Cluster
            </Button>
            <Button size="sm" className="text-xs bg-primary text-primary-foreground">
              + Register Workload
            </Button>
          </div>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  <th className="pb-3 pl-2">Job Name & Cluster</th>
                  <th className="pb-3">Type</th>
                  <th className="pb-3">Power</th>
                  <th className="pb-3">Scheduled Window</th>
                  <th className="pb-3">Hard Deadline</th>
                  <th className="pb-3">Est. Savings</th>
                  <th className="pb-3">Status</th>
                  <th className="pb-3 pr-2 text-right">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {workloads.map((item) => (
                  <tr key={item.id} className="hover:bg-muted/40 transition-colors">
                    <td className="py-3 pl-2">
                      <div className="font-semibold text-foreground">{item.name}</div>
                      <div className="text-[11px] text-muted-foreground font-mono">{item.machineCluster}</div>
                    </td>
                    <td className="py-3">
                      <span className="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
                        {item.type}
                      </span>
                    </td>
                    <td className="py-3 font-mono font-medium">
                      {item.powerKw} kW
                    </td>
                    <td className="py-3 font-mono text-xs">
                      {item.scheduledWindow}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground">
                      {item.deadline}
                    </td>
                    <td className="py-3 font-mono font-bold text-emerald-600 dark:text-emerald-400 text-xs">
                      {item.savings}
                    </td>
                    <td className="py-3">
                      <span
                        className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold ${
                          item.status === "Running"
                            ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                            : item.status === "Throttled"
                            ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                            : "bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30"
                        }`}
                      >
                        <span className={`h-1.5 w-1.5 rounded-full ${item.status === "Running" ? "bg-emerald-500 animate-pulse" : item.status === "Throttled" ? "bg-amber-500" : "bg-blue-500"}`} />
                        {item.status}
                      </span>
                    </td>
                    <td className="py-3 pr-2 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-8 text-xs text-muted-foreground hover:text-foreground"
                        onClick={() => handleToggleWorkload(item.id)}
                      >
                        {item.status === "Running" ? (
                          <Pause className="h-3.5 w-3.5 text-amber-500" />
                        ) : (
                          <Play className="h-3.5 w-3.5 text-emerald-500" />
                        )}
                        <span className="sr-only">Toggle</span>
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
