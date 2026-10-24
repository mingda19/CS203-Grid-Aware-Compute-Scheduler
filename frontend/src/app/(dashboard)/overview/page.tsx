"use client"

import * as React from "react"
import Link from "next/link"
import {
  TrendingDown,
  TrendingUp,
  Cpu,
  Server,
  ArrowRight,
  BriefcaseBusiness,
  Activity,
  Plus,
  Sparkles,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { priceApi, jobsApi, freshnessApi, type PricePoint, type ComputeJob, type DataFreshnessResponse } from "@/lib/api"
import { EndpointPipelineStatus } from "@/components/ui/efferd-dashboard-2-utils/endpoint-pipeline-status"
import { UnifiedPriceTimeline } from "@/components/forecasts/UnifiedPriceTimeline"
import { formatSgtTime, formatSgtDateTime } from "@/lib/date-utils"

export default function OverviewPage() {
  const [jobs, setJobs] = React.useState<ComputeJob[]>([])
  const [jobsLoading, setJobsLoading] = React.useState(true)
  const [pricePoints, setPricePoints] = React.useState<PricePoint[]>([])
  const [pricesLoading, setPricesLoading] = React.useState(true)
  const [pricesError, setPricesError] = React.useState<string | null>(null)
  const [freshness, setFreshness] = React.useState<DataFreshnessResponse | null>(null)

  React.useEffect(() => {
    let cancelled = false

    // Fetch price history
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

    // Fetch compute jobs
    setJobsLoading(true)
    jobsApi
      .list()
      .then((res) => {
        if (!cancelled) setJobs(res.data ?? [])
      })
      .catch(() => {
        if (!cancelled) setJobs([])
      })
      .finally(() => {
        if (!cancelled) setJobsLoading(false)
      })

    // Fetch freshness
    freshnessApi
      .getStatus()
      .then((res) => {
        if (!cancelled && res.data) setFreshness(res.data)
      })
      .catch(() => {})

    return () => {
      cancelled = true
    }
  }, [])

  const lmpMetrics = React.useMemo(() => {
    if (pricePoints.length === 0) {
      return {
        currentLmp: null,
        formattedCurrent: "—",
        diffPercent: 0,
        isDrop: true,
        rollingAvgFormatted: "—",
        peakPriceFormatted: "—",
        peakWindow: "Peak window: —",
      }
    }
    const latest = pricePoints[pricePoints.length - 1]
    const current = latest.sppUsdMwh ?? 0
    const recent = pricePoints.slice(-288)
    const sum = recent.reduce((acc, p) => acc + (p.sppUsdMwh ?? 0), 0)
    const avg = recent.length > 0 ? sum / recent.length : current
    const diff = avg !== 0 ? ((current - avg) / avg) * 100 : 0

    let maxPrice = -Infinity
    let maxTimeStr = "—"
    for (const p of pricePoints) {
      if (p.sppUsdMwh != null && p.sppUsdMwh > maxPrice) {
        maxPrice = p.sppUsdMwh
        maxTimeStr = formatSgtTime(`${p.intervalStartUtc}Z`, true)
      }
    }

    return {
      currentLmp: current,
      formattedCurrent: `$${current.toFixed(2)}`,
      diffPercent: Math.abs(diff),
      isDrop: diff <= 0,
      rollingAvgFormatted: `$${avg.toFixed(2)}`,
      peakPriceFormatted: `$${maxPrice.toFixed(2)}`,
      peakWindow: `Peak at ${maxTimeStr}`,
    }
  }, [pricePoints])

  const totalCpuHours = React.useMemo(() => jobs.reduce((acc, j) => acc + (j.cpuHours || 0), 0), [jobs])
  const totalGpuHours = React.useMemo(() => jobs.reduce((acc, j) => acc + (j.gpuHours || 0), 0), [jobs])
  const flexibleJobsCount = React.useMemo(() => jobs.filter((j) => !j.isExempt).length, [jobs])

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
            Wholesale electricity price dispatching with automated compute workload constraint preservation.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="text-right hidden sm:block">
            <p className="text-xs text-muted-foreground">Market Hub</p>
            <p className="text-sm font-semibold font-mono text-foreground">ERCOT North (LZ_NORTH)</p>
          </div>
          <Separator orientation="vertical" className="h-9 hidden sm:block" />
          <Button asChild className="text-xs gap-2 bg-teal-600 hover:bg-teal-700 text-white font-medium">
            <Link href="/jobs">
              <Sparkles className="h-3.5 w-3.5" />
              Run Optimizer
            </Link>
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
            <DataStatusBadge
              size="sm"
              status={pricesLoading ? "cached" : pricesError ? "unavailable" : pricePoints.length > 0 ? "live" : "unavailable"}
              source="ERCOT SPP (LZ_NORTH)"
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

        {/* Card 2: Compute Workloads */}
        <Card className="hover:border-blue-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Registered Jobs
            </CardTitle>
            <span className="text-[11px] font-mono text-muted-foreground">
              {flexibleJobsCount} flexible
            </span>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground">
              {jobs.length} <span className="text-xs font-normal text-muted-foreground">jobs total</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-blue-600 dark:text-blue-400">
              <Cpu className="h-3.5 w-3.5" />
              <span className="font-semibold">{totalCpuHours.toFixed(0)} CPU-hrs</span>
              <span className="text-muted-foreground">· {totalGpuHours.toFixed(0)} GPU-hrs</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Rescheduling Status</span>
            <span className="font-semibold text-foreground">
              {jobs.length > 0 ? `${flexibleJobsCount}/${jobs.length} reschedulable` : "No jobs queued"}
            </span>
          </CardFooter>
        </Card>

        {/* Card 3: Data Freshness */}
        <Card className="hover:border-teal-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Data Ingestion
            </CardTitle>
            <DataStatusBadge
              size="sm"
              status={freshness?.datasets?.some((d) => d.status === "HEALTHY") ? "live" : "cached"}
              source="ERCOT Telemetry Pipeline"
            />
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-foreground flex items-center gap-2">
              <Activity className="h-5 w-5 text-emerald-500" />
              <span>Healthy</span>
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-teal-600 dark:text-teal-400">
              <span>5-minute settlement intervals ingested</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Checked At</span>
            <span className="font-mono text-foreground">
              {freshness?.checkedAt ? formatSgtTime(freshness.checkedAt, true) : "Active"}
            </span>
          </CardFooter>
        </Card>

        {/* Card 4: Optimizer Action */}
        <Card className="hover:border-amber-500/50 transition-colors bg-gradient-to-br from-card to-teal-500/5">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Schedule Optimizer
            </CardTitle>
            <span className="rounded-full bg-teal-500/15 px-2 py-0.5 text-[10px] font-semibold text-teal-600 dark:text-teal-400 border border-teal-500/30">
              v1.0 Ready
            </span>
          </CardHeader>
          <CardContent>
            <div className="text-2xl font-bold font-mono text-teal-600 dark:text-teal-400">
              {jobs.length > 0 ? "Ready" : "Waiting for Jobs"}
            </div>
            <div className="flex items-center gap-1.5 mt-2 text-xs text-muted-foreground">
              <span>Places flexible jobs into low-cost LMP price valleys</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Target Market</span>
            <span className="font-semibold text-foreground">LZ_NORTH (48h)</span>
          </CardFooter>
        </Card>
      </div>

      {/* Snapshot Modules */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Continuous Price Timeline Preview */}
        <div className="flex flex-col">
          <UnifiedPriceTimeline
            hubLocation="LZ_NORTH"
            variant="compact"
            height={220}
            className="h-full"
          />
        </div>

        {/* Workload Queue Preview */}
        <Card className="hover:border-border transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-3">
            <div>
              <CardTitle className="text-base font-semibold flex items-center gap-2">
                <Server className="h-4 w-4 text-emerald-500" />
                Submitted Compute Jobs
              </CardTitle>
              <CardDescription className="text-xs mt-0.5">
                Workloads registered by operators for grid-aware dispatching.
              </CardDescription>
            </div>
            <Button asChild variant="ghost" size="sm" className="text-xs gap-1.5 text-emerald-600 dark:text-emerald-400">
              <Link href="/jobs">
                Manage Jobs <ArrowRight className="h-3.5 w-3.5" />
              </Link>
            </Button>
          </CardHeader>
          <CardContent className="space-y-3">
            {jobsLoading ? (
              <p className="py-8 text-center text-xs text-muted-foreground">Loading compute jobs…</p>
            ) : jobs.length === 0 ? (
              <div className="py-8 text-center space-y-2">
                <BriefcaseBusiness className="h-8 w-8 mx-auto text-muted-foreground/30" />
                <p className="text-xs font-medium text-foreground">No compute jobs submitted yet</p>
                <p className="text-[11px] text-muted-foreground">
                  Create a compute job to start optimizing execution against wholesale power prices.
                </p>
                <Button asChild size="sm" className="mt-2 bg-emerald-600 hover:bg-emerald-700 text-white text-xs">
                  <Link href="/jobs">
                    <Plus className="h-3.5 w-3.5 mr-1.5" /> Create Job
                  </Link>
                </Button>
              </div>
            ) : (
              jobs.slice(0, 4).map((j) => (
                <div
                  key={j.id}
                  className="flex items-center justify-between p-3 rounded-xl border border-border/70 bg-card hover:bg-muted/30 transition-colors"
                >
                  <div className="space-y-0.5">
                    <div className="flex items-center gap-2">
                      <span className="text-xs font-semibold text-foreground">{j.name}</span>
                      <span className="text-[10px] px-1.5 py-0.5 rounded-md bg-muted text-muted-foreground font-mono">
                        {j.cpuHours} CPU · {j.gpuHours} GPU
                      </span>
                    </div>
                    <p className="text-[11px] text-muted-foreground">
                      Start: <span className="font-mono text-foreground">{formatSgtDateTime(j.originalStartTime)}</span> · Deadline: {formatSgtDateTime(j.deadline)}
                    </p>
                  </div>
                  <div className="text-right">
                    <span
                      className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold ${
                        j.isExempt
                          ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                          : "bg-teal-500/15 text-teal-600 dark:text-teal-400 border border-teal-500/30"
                      }`}
                    >
                      {j.isExempt ? "Exempt" : `+${j.flexibilityWindowHours}h window`}
                    </span>
                    <p className="text-[11px] text-muted-foreground mt-0.5">{j.status}</p>
                  </div>
                </div>
              ))
            )}
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
