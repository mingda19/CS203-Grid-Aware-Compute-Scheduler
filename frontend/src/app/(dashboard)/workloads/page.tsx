"use client"

import * as React from "react"
import Link from "next/link"
import {
  Calendar,
  Table,
  RefreshCw,
  Sparkles,
  BriefcaseBusiness,
  CheckCircle2,
  AlertCircle,
  ArrowRight,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { GoogleCalendarView } from "@/components/schedule-calendar/GoogleCalendarView"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  jobsApi,
  optimizerApi,
  facilityApi,
  workloadScheduleApi,
  type ComputeJob,
  type OptimizeJobInput,
} from "@/lib/api"
import { formatSgtDateTime } from "@/lib/date-utils"
import type { Workload } from "@/lib/workload-data"
import { WorkloadQueue } from "@/components/workloads/WorkloadQueue"

function computeJobToWorkload(job: ComputeJob): Workload {
  const power = Math.round(job.cpuHours * 5 + job.gpuHours * 25) || 250
  return {
    id: `JOB-${job.id}`,
    name: job.name,
    type: job.gpuHours > 0 ? "ML Training" : "HPC Batch",
    powerKw: power,
    actualPowerKw: power,
    scheduledWindow: `${formatSgtDateTime(job.originalStartTime)} (Baseline)`,
    deadline: formatSgtDateTime(job.deadline),
    savings: job.isExempt ? "Pinned (Exempt)" : "Pending Solver Optimization",
    status:
      job.status === "COMPLETED"
        ? "Completed"
        : job.status === "RUNNING"
        ? "Running"
        : "Scheduled",
    machineCluster: `${job.cpuHours} CPU-hrs · ${job.gpuHours} GPU-hrs`,
    priority: job.priority === "CRITICAL"
      ? "Critical"
      : job.priority === "HIGH"
      ? "High"
      : job.priority === "LOW"
      ? "Interruptible"
      : job.priority === "NORMAL"
      ? "Normal"
      : (job.isExempt ? "Critical" : "Normal"),
    hardware: `${job.cpuHours} vCPUs · ${job.gpuHours} GPUs`,
    precedence: job.isExempt
      ? "Pinned start time (Exempt from rescheduling)"
      : `Flexibility window: ${job.flexibilityWindowHours}h`,
    slaBuffer: `${job.flexibilityWindowHours}h delay tolerance`,
    slaRisk: job.isExempt ? "Zero" : "Low",
    scheduleRationale: job.rawConstraints || "Submitted by operator for grid-aware scheduling",
    carbonOffsetKg: Math.round(power * 0.3),
  }
}

export default function WorkloadsPage() {
  const [workloads, setWorkloads] = React.useState<Workload[]>([])
  const [loading, setLoading] = React.useState(true)
  const [displayMode, setDisplayMode] = React.useState<"table" | "calendar">("table")
  const [optimizing, setOptimizing] = React.useState(false)
  const [calendarKey, setCalendarKey] = React.useState(0)
  const [feedback, setFeedback] = React.useState<{ type: "success" | "error"; message: string } | null>(null)

  const loadData = React.useCallback(async () => {
    setLoading(true)
    try {
      const response = await jobsApi.list()
      const jobList = response.data ?? []
      setWorkloads(jobList.map(computeJobToWorkload))
    } catch {
      setWorkloads([])
    } finally {
      setLoading(false)
    }
  }, [])

  React.useEffect(() => {
    void loadData()
  }, [loadData])

  const handleRunOptimizer = async () => {
    setOptimizing(true)
    setFeedback(null)
    try {
      const [jobsRes, facilityRes] = await Promise.all([
        jobsApi.list(),
        facilityApi.get().catch(() => ({ data: null })),
      ])
      const jobList = jobsRes.data ?? []
      if (jobList.length === 0) {
        setFeedback({
          type: "error",
          message: "No compute jobs found. Please create compute jobs in the Job Registry first.",
        })
        return
      }

      const facilityConfig = facilityRes.data ?? {
        name: "Primary Cluster",
        maxCpuHours: 256.0,
        maxGpuHours: 64.0,
        location: "LZ_NORTH",
      }

      const optimizeJobs: OptimizeJobInput[] = jobList.map((j) => {
        let startStr = j.originalStartTime
        if (startStr.includes("T")) {
          const [d, t] = startStr.split("T")
          const hh = t.slice(0, 2)
          startStr = `${d}T${hh}:00:00`
        }
        return {
          id: j.id,
          name: j.name,
          cpuHours: j.cpuHours,
          gpuHours: j.gpuHours,
          originalStartTime: startStr,
          deadline: j.deadline,
          flexibilityWindowHours: j.flexibilityWindowHours,
          isExempt: j.isExempt,
        }
      })

      const optRes = await optimizerApi.optimize({
        jobs: optimizeJobs,
        forecastLocation: facilityConfig.location || "LZ_NORTH",
        horizonHours: 48,
        clusterCapacity: {
          maxConcurrentCpuHours: Number(facilityConfig.maxCpuHours) || 256.0,
          maxConcurrentGpuHours: Number(facilityConfig.maxGpuHours) || 64.0,
        },
      })

      if (!optRes.success || !optRes.data) {
        throw new Error(optRes.message || "Failed to solve schedule")
      }

      const plan = optRes.data
      let appliedCount = 0
      for (const item of plan.items) {
        if (item.scheduledStart && item.scheduledEnd) {
          const powerEst = Math.round(item.allocatedCpuHours * 5 + item.allocatedGpuHours * 25) || 500
          await workloadScheduleApi.create({
            title: item.jobName,
            type: item.allocatedGpuHours > 0 ? "ML Training" : "HPC Batch",
            cluster: facilityConfig.name || "Cluster Primary",
            powerKw: powerEst,
            savings: item.savingsUsd != null ? `$${item.savingsUsd.toFixed(2)}` : null,
            startUtc: item.scheduledStart.replace("Z", ""),
            endUtc: item.scheduledEnd.replace("Z", ""),
            notes: item.perJobStatus + (item.reason ? `: ${item.reason}` : ""),
          })
          appliedCount++
        }
      }

      setFeedback({
        type: "success",
        message: `Solver executed! Placed ${appliedCount} jobs into the calendar with $${plan.projectedSavingsUsd.toFixed(2)} (${plan.projectedSavingsPercent.toFixed(1)}%) projected savings against facility envelope (${facilityConfig.name}).`,
      })
      setCalendarKey((prev) => prev + 1)
      await loadData()
    } catch (err) {
      setFeedback({
        type: "error",
        message: err instanceof Error ? err.message : "Optimization run failed.",
      })
    } finally {
      setOptimizing(false)
    }
  }

  return (
    <div id="workloads-root" className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Workload Scheduler & Dispatch Timeline
            </h1>
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Dispatch, throttle, and inspect flexible compute batches mapped to wholesale ERCOT LMP signals.
          </p>
        </div>

        <div className="flex flex-wrap items-center gap-3">
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            <button
              type="button"
              onClick={() => setDisplayMode("table")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 cursor-pointer",
                displayMode === "table"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Table className={cn("h-3.5 w-3.5", displayMode === "table" && "text-emerald-500")} />
              Queue Table
            </button>
            <button
              type="button"
              onClick={() => setDisplayMode("calendar")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 cursor-pointer",
                displayMode === "calendar"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Calendar className={cn("h-3.5 w-3.5", displayMode === "calendar" && "text-emerald-500")} />
              Calendar View
            </button>
          </div>

          <Button
            size="sm"
            variant="outline"
            onClick={() => {
              void loadData()
              setCalendarKey((k) => k + 1)
            }}
            disabled={loading}
            className="gap-1.5"
          >
            <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} />
            Refresh
          </Button>

          <Button
            size="sm"
            onClick={handleRunOptimizer}
            disabled={optimizing || loading}
            className="gap-2 bg-teal-600 hover:bg-teal-700 text-white cursor-pointer font-medium shadow-xs"
          >
            <Sparkles className="h-4 w-4" />
            {optimizing ? "Solving Schedule…" : "Run Optimizer"}
          </Button>

          <Button
            size="sm"
            variant="outline"
            asChild
            className="gap-2 border-border font-medium cursor-pointer"
          >
            <Link href="/jobs">
              <BriefcaseBusiness className="h-4 w-4 text-emerald-500" />
              <span>Job & Facility Registry</span>
              <ArrowRight className="h-3.5 w-3.5 text-muted-foreground" />
            </Link>
          </Button>
        </div>
      </div>

      {feedback && (
        <div
          role="status"
          className={cn(
            "rounded-xl border p-4 text-xs flex items-center justify-between gap-3 shadow-xs",
            feedback.type === "success"
              ? "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"
              : "border-destructive/30 bg-destructive/10 text-destructive"
          )}
        >
          <div className="flex items-center gap-2.5">
            {feedback.type === "success" ? (
              <CheckCircle2 className="h-4 w-4 shrink-0" />
            ) : (
              <AlertCircle className="h-4 w-4 shrink-0" />
            )}
            <span className="font-medium">{feedback.message}</span>
          </div>
          <Button
            variant="ghost"
            size="sm"
            className="h-6 px-2 text-[11px]"
            onClick={() => setFeedback(null)}
          >
            Dismiss
          </Button>
        </div>
      )}

      {/* Main View Area */}
      {displayMode === "calendar" ? (
        <Card className="border border-border bg-card shadow-xs">
          <CardContent className="p-4 sm:p-6">
            <GoogleCalendarView key={calendarKey} />
          </CardContent>
        </Card>
      ) : loading ? (
        <Card>
          <CardContent className="py-16 text-center text-sm text-muted-foreground">
            Loading workloads from compute jobs…
          </CardContent>
        </Card>
      ) : workloads.length === 0 ? (
        <Card>
          <CardContent className="py-16 text-center space-y-3">
            <BriefcaseBusiness className="h-10 w-10 mx-auto text-muted-foreground/40" />
            <h3 className="font-semibold text-lg text-foreground">No compute workloads registered</h3>
            <p className="text-sm text-muted-foreground max-w-md mx-auto">
              Submit your compute jobs with resource hours, baseline start times, and deadlines in the Job Registry to see them in the queue and run schedule optimization.
            </p>
            <Button asChild className="mt-2 bg-emerald-600 hover:bg-emerald-700 text-white">
              <Link href="/jobs">
                <BriefcaseBusiness className="mr-2 h-4 w-4" /> Open Job & Facility Registry
              </Link>
            </Button>
          </CardContent>
        </Card>
      ) : (
        <WorkloadQueue workloads={workloads} onWorkloadsChange={setWorkloads} />
      )}
    </div>
  )
}
