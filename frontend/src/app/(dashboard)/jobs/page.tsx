"use client"

import * as React from "react"
import Link from "next/link"
import {
  BriefcaseBusiness,
  Eye,
  Pencil,
  Plus,
  RefreshCw,
  Trash2,
  Sparkles,
  TrendingUp,
  CheckCircle2,
  AlertTriangle,
  Clock,
  Calendar,
  Server,
  Check,
  ArrowRight,
  SlidersHorizontal,
} from "lucide-react"
import {
  jobsApi,
  optimizerApi,
  facilityApi,
  workloadScheduleApi,
  type ComputeJob,
  type ComputeJobPayload,
  type JobPriority,
  type OptimizeJobInput,
  type OptimizeResponse,
  type ScheduleItem,
  type FacilityConfig,
} from "@/lib/api"
import { formatSgtDateTime, SGT_TIMEZONE } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardHeader, CardTitle, CardDescription } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { JobDateTimePicker } from "@/components/jobs/JobDateTimePicker"

type FormState = {
  name: string
  cpuHours: string
  gpuHours: string
  originalStartTime: string
  deadline: string
  flexibilityWindowHours: string
  priority: JobPriority
  isExempt: boolean
}

const blankForm = (): FormState => ({
  name: "",
  cpuHours: "0",
  gpuHours: "0",
  originalStartTime: "",
  deadline: "",
  flexibilityWindowHours: "0",
  priority: "NORMAL",
  isExempt: false,
})

// The API uses timezone-free LocalDateTime strings that represent UTC. Convert
// them explicitly so datetime-local inputs always show Singapore wall time.
const toInputDate = (value?: string) => {
  if (!value) return ""
  const iso = value.endsWith("Z") || value.includes("+") ? value : `${value}Z`
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SGT_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(iso))
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? ""
  return `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}`
}

const toApiUtcDate = (value: string, onTheHour = false) => {
  const [date, time] = value.split("T")
  const [year, month, day] = date.split("-").map(Number)
  const [hour, minute] = time.split(":").map(Number)
  const finalMinute = onTheHour ? 0 : minute
  const utc = new Date(Date.UTC(year, month - 1, day, hour - 8, finalMinute, 0))
  return utc.toISOString().slice(0, 19)
}

const displayDate = (value?: string) => (value ? formatSgtDateTime(value) : "—")

const getWindowDurationText = (start: string, end: string) => {
  if (!start || !end) return ""
  try {
    const [d1, t1] = start.split("T")
    const [y1, m1, day1] = d1.split("-").map(Number)
    const [h1, min1] = t1.split(":").map(Number)
    const [d2, t2] = end.split("T")
    const [y2, m2, day2] = d2.split("-").map(Number)
    const [h2, min2] = t2.split(":").map(Number)
    const date1 = new Date(Date.UTC(y1, m1 - 1, day1, h1, min1))
    const date2 = new Date(Date.UTC(y2, m2 - 1, day2, h2, min2))
    const diffHours = (date2.getTime() - date1.getTime()) / (1000 * 60 * 60)
    if (diffHours < 0) return "⚠️ Deadline is before start time"
    if (diffHours === 0) return "0 hours (starts at deadline)"
    return `${diffHours % 1 === 0 ? diffHours : diffHours.toFixed(1)} hours span`
  } catch {
    return ""
  }
}

export default function JobsPage() {
  const [jobs, setJobs] = React.useState<ComputeJob[]>([])
  const [loading, setLoading] = React.useState(true)
  const [saving, setSaving] = React.useState(false)
  const [updatingExemptionId, setUpdatingExemptionId] = React.useState<number | null>(null)
  const [error, setError] = React.useState("")
  const [formOpen, setFormOpen] = React.useState(false)
  const [viewOpen, setViewOpen] = React.useState(false)
  const [editing, setEditing] = React.useState<ComputeJob | null>(null)
  const [viewing, setViewing] = React.useState<ComputeJob | null>(null)
  const [form, setForm] = React.useState<FormState>(blankForm)

  // Optimizer state
  const [optimizing, setOptimizing] = React.useState(false)
  const [optimizationPlan, setOptimizationPlan] = React.useState<OptimizeResponse | null>(null)
  const [optimizeOpen, setOptimizeOpen] = React.useState(false)
  const [applyingSchedule, setApplyingSchedule] = React.useState(false)
  const [applySuccessMessage, setApplySuccessMessage] = React.useState<string | null>(null)

  // Facility & Cluster Envelope state (stored in DB per user)
  const [facility, setFacility] = React.useState<FacilityConfig>({
    name: "Primary Compute Cluster",
    maxCpuHours: 256.0,
    maxGpuHours: 64.0,
    location: "LZ_NORTH",
  })
  const [facilityLoading, setFacilityLoading] = React.useState(true)
  const [facilitySaving, setFacilitySaving] = React.useState(false)
  const [facilitySavedMsg, setFacilitySavedMsg] = React.useState<string | null>(null)
  const [facilityExpanded, setFacilityExpanded] = React.useState(false)

  const loadFacility = React.useCallback(async () => {
    setFacilityLoading(true)
    try {
      const response = await facilityApi.get()
      if (response.data) {
        setFacility(response.data)
      }
    } catch {
      // Keep sensible defaults if network or first time
    } finally {
      setFacilityLoading(false)
    }
  }, [])

  const handleSaveFacility = async (e?: React.FormEvent) => {
    if (e) e.preventDefault()
    setFacilitySaving(true)
    setFacilitySavedMsg(null)
    try {
      const response = await facilityApi.update({
        ...facility,
        maxCpuHours: Number(facility.maxCpuHours) || 0,
        maxGpuHours: Number(facility.maxGpuHours) || 0,
      })
      if (response.data) {
        setFacility(response.data)
      }
      setFacilitySavedMsg("Facility envelope updated & saved to database!")
      setTimeout(() => setFacilitySavedMsg(null), 3500)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to update facility envelope")
    } finally {
      setFacilitySaving(false)
    }
  }

  const applyPreset = (presetName: string, cpu: number, gpu: number, loc = "LZ_NORTH") => {
    setFacility((prev) => ({
      ...prev,
      name: presetName,
      maxCpuHours: cpu,
      maxGpuHours: gpu,
      location: loc,
    }))
  }

  const loadJobs = React.useCallback(async () => {
    setLoading(true)
    setError("")
    try {
      const response = await jobsApi.list()
      setJobs(response.data ?? [])
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load jobs")
    } finally {
      setLoading(false)
    }
  }, [])

  React.useEffect(() => {
    let active = true
    jobsApi
      .list()
      .then((response) => {
        if (active) setJobs(response.data ?? [])
      })
      .catch((e) => {
        if (active) setError(e instanceof Error ? e.message : "Could not load jobs")
      })
      .finally(() => {
        if (active) setLoading(false)
      })

    void loadFacility()

    return () => {
      active = false
    }
  }, [loadFacility])

  const openCreate = () => {
    setEditing(null)
    setForm(blankForm())
    setError("")
    setFormOpen(true)
  }

  const openEdit = (job: ComputeJob) => {
    setEditing(job)
    setError("")
    setForm({
      name: job.name,
      cpuHours: String(job.cpuHours),
      gpuHours: String(job.gpuHours),
      originalStartTime: toInputDate(job.originalStartTime),
      deadline: toInputDate(job.deadline),
      flexibilityWindowHours: String(job.flexibilityWindowHours),
      priority: job.priority ?? "NORMAL",
      isExempt: job.isExempt,
    })
    setFormOpen(true)
  }

  const openView = async (job: ComputeJob) => {
    setError("")
    try {
      const response = await jobsApi.get(job.id)
      setViewing(response.data ?? job)
      setViewOpen(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load job details")
    }
  }

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError("")
    const cpuHours = Number(form.cpuHours)
    const gpuHours = Number(form.gpuHours)
    const flexibilityWindowHours = Number(form.flexibilityWindowHours)
    if (cpuHours === 0 && gpuHours === 0) {
      setError("Enter CPU hours, GPU hours, or both.")
      return
    }
    if (form.deadline < form.originalStartTime) {
      setError("Deadline must be at or after the original start time.")
      return
    }
    // originalStartTime is formatted to exact hour (00 minutes) per optimizer specification
    const payload: ComputeJobPayload = {
      name: form.name.trim(),
      cpuHours,
      gpuHours,
      originalStartTime: toApiUtcDate(form.originalStartTime, true),
      deadline: toApiUtcDate(form.deadline, false),
      flexibilityWindowHours,
      priority: form.priority,
      isExempt: form.isExempt,
    }
    setSaving(true)
    try {
      if (editing) await jobsApi.update(editing.id, payload)
      else await jobsApi.create(payload)
      setFormOpen(false)
      await loadJobs()
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not save job")
    } finally {
      setSaving(false)
    }
  }

  const setJobExempt = async (job: ComputeJob, isExempt: boolean) => {
    setError("")
    setUpdatingExemptionId(job.id)
    const payload: ComputeJobPayload = {
      name: job.name,
      cpuHours: job.cpuHours,
      gpuHours: job.gpuHours,
      originalStartTime: job.originalStartTime,
      deadline: job.deadline,
      flexibilityWindowHours: job.flexibilityWindowHours,
      priority: job.priority ?? "NORMAL",
      isExempt,
    }
    try {
      const response = await jobsApi.update(job.id, payload)
      const updated = response.data
      setJobs((current) =>
        current.map((item) => (item.id === job.id ? (updated ?? { ...item, isExempt }) : item))
      )
    } catch (e) {
      setError(e instanceof Error ? e.message : `Could not update exemption for ${job.name}`)
    } finally {
      setUpdatingExemptionId(null)
    }
  }

  const remove = async (job: ComputeJob) => {
    if (!window.confirm(`Delete “${job.name}”? This cannot be undone.`)) return
    setError("")
    try {
      await jobsApi.remove(job.id)
      setJobs((current) => current.filter((item) => item.id !== job.id))
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not delete job")
    }
  }

  // Schedule Optimization execution
  const runOptimization = async () => {
    if (jobs.length === 0) {
      setError("Add at least one compute job before running schedule optimization.")
      return
    }
    setError("")
    setOptimizing(true)
    setApplySuccessMessage(null)

    // Build optimize payload ensuring originalStartTime is truncated to the hour
    const optimizeJobs: OptimizeJobInput[] = jobs.map((j) => {
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

    try {
      const response = await optimizerApi.optimize({
        jobs: optimizeJobs,
        forecastLocation: facility.location || "LZ_NORTH",
        horizonHours: 48,
        clusterCapacity: {
          maxConcurrentCpuHours: Number(facility.maxCpuHours) || 256.0,
          maxConcurrentGpuHours: Number(facility.maxGpuHours) || 64.0,
        },
      })
      if (!response.success || !response.data) {
        throw new Error(response.message || "Failed to generate schedule")
      }
      setOptimizationPlan(response.data)
      setOptimizeOpen(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Optimization failed")
    } finally {
      setOptimizing(false)
    }
  }

  // Push scheduled items to workload-schedules for Calendar and Queue view
  const applyScheduleToCalendar = async () => {
    if (!optimizationPlan || !optimizationPlan.items) return
    setApplyingSchedule(true)
    setApplySuccessMessage(null)
    try {
      let createdCount = 0
      for (const item of optimizationPlan.items) {
        if (item.scheduledStart && item.scheduledEnd) {
          const powerEst = Math.round(item.allocatedCpuHours * 5 + item.allocatedGpuHours * 25) || 500
          await workloadScheduleApi.create({
            title: item.jobName,
            type: item.allocatedGpuHours > 0 ? "ML Training" : "HPC Batch",
            cluster: "Cluster Primary",
            powerKw: powerEst,
            savings: item.savingsUsd != null ? `$${item.savingsUsd.toFixed(2)}` : null,
            startUtc: item.scheduledStart.replace("Z", ""),
            endUtc: item.scheduledEnd.replace("Z", ""),
            notes: item.perJobStatus + (item.reason ? `: ${item.reason}` : ""),
          })
          createdCount++
        }
      }
      setApplySuccessMessage(`Successfully applied ${createdCount} optimized jobs to Workload Calendar!`)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not apply schedule")
    } finally {
      setApplyingSchedule(false)
    }
  }

  return (
    <div className="space-y-6" id="jobs-root">
      <div className="flex flex-col gap-4 rounded-2xl border border-border bg-gradient-to-r from-card to-card/60 p-6 shadow-xs sm:flex-row sm:items-center sm:justify-between">
        <div>
          <div className="flex items-center gap-2">
            <BriefcaseBusiness className="h-5 w-5 text-emerald-500" />
            <h1 className="text-2xl font-bold tracking-tight">Compute Jobs</h1>
          </div>
          <p className="mt-1 text-sm text-muted-foreground">
            Manage compute workloads and run grid-aware schedule optimization against ERCOT LMP pricing signals.
          </p>
        </div>
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            asChild
            className="border-border text-foreground hover:bg-muted"
          >
            <Link href="/workloads">
              <Calendar className="mr-2 h-4 w-4 text-emerald-500" />
              Workload Calendar
            </Link>
          </Button>
          <Button
            variant="outline"
            onClick={() => void loadJobs()}
            disabled={loading}
            aria-label="Refresh jobs"
          >
            <RefreshCw className="mr-2 h-4 w-4" />
            Refresh
          </Button>
          <Button onClick={openCreate} className="bg-emerald-600 text-white hover:bg-emerald-700">
            <Plus className="mr-2 h-4 w-4" />
            New job
          </Button>
          <Button
            onClick={runOptimization}
            disabled={optimizing || jobs.length === 0}
            className="bg-teal-600 text-white hover:bg-teal-700 font-semibold shadow-xs"
          >
            <Sparkles className="mr-2 h-4 w-4" />
            {optimizing ? "Solving Schedule…" : "Run Optimizer"}
          </Button>
        </div>
      </div>

      {/* Facility & Cluster Hardware Envelope Card */}
      <Card className="border border-border bg-card shadow-xs overflow-hidden">
        <div className="p-5 flex flex-col md:flex-row md:items-center justify-between gap-4 border-b border-border/60 bg-muted/20">
          <div className="flex items-start gap-3">
            <div className="h-10 w-10 rounded-xl bg-teal-500/15 border border-teal-500/30 text-teal-600 dark:text-teal-400 flex items-center justify-center shrink-0">
              <Server className="h-5 w-5" />
            </div>
            <div>
              <div className="flex items-center gap-2 flex-wrap">
                <h3 className="font-semibold text-base text-foreground tracking-tight">
                  Facility Hardware & Capacity Envelope
                </h3>
                <span className="text-[10px] font-mono uppercase px-2 py-0.5 rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30 font-semibold">
                  Saved in DB
                </span>
              </div>
              <p className="text-xs text-muted-foreground mt-0.5">
                Configured limits constrain solver slot allocation per hour (AC3). Adjust your data center throughput or test constrained conditions.
              </p>
            </div>
          </div>

          <div className="flex items-center gap-2">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setFacilityExpanded(!facilityExpanded)}
              className="gap-1.5 text-xs font-medium cursor-pointer"
            >
              <SlidersHorizontal className="h-3.5 w-3.5 text-teal-500" />
              {facilityExpanded ? "Hide Settings" : "Configure Facility"}
            </Button>
          </div>
        </div>

        {/* Overview Bar */}
        <div className="px-5 py-3.5 bg-card flex flex-wrap items-center justify-between gap-4 text-xs">
          <div className="flex flex-wrap items-center gap-4 sm:gap-6">
            <div>
              <span className="text-[11px] text-muted-foreground block">Facility Node</span>
              <span className="font-semibold text-foreground">{facility.name || "Primary Cluster"}</span>
            </div>
            <div className="h-8 w-px bg-border/60 hidden sm:block" />
            <div>
              <span className="text-[11px] text-muted-foreground block">Max CPU / Slot</span>
              <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400">
                {facility.maxCpuHours} CPU-h
              </span>
            </div>
            <div className="h-8 w-px bg-border/60 hidden sm:block" />
            <div>
              <span className="text-[11px] text-muted-foreground block">Max GPU / Slot</span>
              <span className="font-mono font-semibold text-teal-600 dark:text-teal-400">
                {facility.maxGpuHours} GPU-h
              </span>
            </div>
            <div className="h-8 w-px bg-border/60 hidden sm:block" />
            <div>
              <span className="text-[11px] text-muted-foreground block">ERCOT Pricing Zone</span>
              <span className="font-mono font-semibold text-foreground">
                {facility.location || "LZ_NORTH"}
              </span>
            </div>
          </div>

          {facilitySavedMsg && (
            <div className="inline-flex items-center gap-1.5 px-3 py-1 rounded-md bg-emerald-500/10 border border-emerald-500/30 text-[11px] font-medium text-emerald-600 dark:text-emerald-400 animate-in fade-in">
              <Check className="h-3.5 w-3.5" />
              {facilitySavedMsg}
            </div>
          )}
        </div>

        {/* Expanded Configuration Panel */}
        {facilityExpanded && (
          <div className="p-5 border-t border-border/60 bg-muted/15 space-y-4 animate-in slide-in-from-top-2 duration-150">
            <div className="flex flex-wrap items-center justify-between gap-2">
              <span className="text-xs font-semibold text-foreground uppercase tracking-wider">
                Quick Facility Presets (Evaluation & Stress-Testing)
              </span>
              <div className="flex flex-wrap gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-7 text-[11px] gap-1"
                  onClick={() => applyPreset("Standard Datacenter", 256.0, 64.0, "LZ_NORTH")}
                >
                  Standard (256 CPU · 64 GPU)
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-7 text-[11px] gap-1 text-amber-600 dark:text-amber-400 border-amber-500/30"
                  onClick={() => applyPreset("Constrained Test Lab", 32.0, 8.0, "LZ_NORTH")}
                >
                  Constrained Lab (32 CPU · 8 GPU)
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant="outline"
                  className="h-7 text-[11px] gap-1 text-teal-600 dark:text-teal-400 border-teal-500/30"
                  onClick={() => applyPreset("AI Supercomputing Pod", 512.0, 128.0, "LZ_HOUSTON")}
                >
                  AI Pod (512 CPU · 128 GPU)
                </Button>
              </div>
            </div>

            <form onSubmit={handleSaveFacility} className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-4 pt-2">
              <Field label="Facility / Cluster Name">
                <Input
                  value={facility.name}
                  onChange={(e) => setFacility({ ...facility, name: e.target.value })}
                  placeholder="e.g. Austin Primary Cluster"
                  required
                />
              </Field>

              <Field label="Max Concurrent CPU-Hours / Slot">
                <Input
                  type="number"
                  min="0"
                  step="any"
                  value={facility.maxCpuHours}
                  onChange={(e) => setFacility({ ...facility, maxCpuHours: Number(e.target.value) })}
                  required
                />
              </Field>

              <Field label="Max Concurrent GPU-Hours / Slot">
                <Input
                  type="number"
                  min="0"
                  step="any"
                  value={facility.maxGpuHours}
                  onChange={(e) => setFacility({ ...facility, maxGpuHours: Number(e.target.value) })}
                  required
                />
              </Field>

              <Field label="ERCOT Settlement Location">
                <select
                  value={facility.location}
                  onChange={(e) => setFacility({ ...facility, location: e.target.value })}
                  className="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-xs font-semibold text-foreground focus:ring-1 focus:ring-primary"
                >
                  <option value="LZ_NORTH">LZ_NORTH (North Hub)</option>
                  <option value="LZ_HOUSTON">LZ_HOUSTON (Houston)</option>
                  <option value="LZ_SOUTH">LZ_SOUTH (South Hub)</option>
                  <option value="LZ_WEST">LZ_WEST (West Texas Wind)</option>
                </select>
              </Field>

              <div className="sm:col-span-2 lg:col-span-4 flex items-center justify-end gap-2 pt-1">
                <Button
                  type="submit"
                  size="sm"
                  disabled={facilityLoading || facilitySaving}
                  className="bg-emerald-600 hover:bg-emerald-700 text-white gap-1.5 font-medium cursor-pointer"
                >
                  <Check className="h-3.5 w-3.5" />
                  {facilityLoading ? "Loading…" : facilitySaving ? "Saving to DB…" : "Save Facility Envelope"}
                </Button>
              </div>
            </form>
          </div>
        )}
      </Card>

      {error && (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      {applySuccessMessage && (
        <div
          role="status"
          className="rounded-md border border-emerald-500/40 bg-emerald-500/10 px-4 py-3 text-sm text-emerald-700 dark:text-emerald-300 flex items-center justify-between"
        >
          <div className="flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4" />
            {applySuccessMessage}
          </div>
          <Button asChild size="sm" variant="ghost" className="text-emerald-700 dark:text-emerald-300 hover:bg-emerald-500/20 text-xs">
            <Link href="/workloads">
              Open Workload Calendar →
            </Link>
          </Button>
        </div>
      )}

      <Card>
        <CardHeader>
          <div className="flex items-center justify-between">
            <div>
              <CardTitle className="text-lg">Your Compute Workloads</CardTitle>
              <CardDescription>
                {jobs.length} registered {jobs.length === 1 ? "job" : "jobs"} available for scheduling
              </CardDescription>
            </div>
            {jobs.length > 0 && (
              <Button
                size="sm"
                variant="outline"
                onClick={runOptimization}
                disabled={optimizing}
                className="gap-1.5 text-xs text-teal-600 dark:text-teal-400 border-teal-500/30"
              >
                <Sparkles className="h-3.5 w-3.5" />
                Optimize ({jobs.length})
              </Button>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {loading ? (
            <p className="py-8 text-center text-sm text-muted-foreground">Loading jobs…</p>
          ) : jobs.length === 0 ? (
            <div className="py-10 text-center">
              <BriefcaseBusiness className="h-10 w-10 mx-auto text-muted-foreground/40 mb-3" />
              <p className="font-medium text-foreground">No compute jobs yet</p>
              <p className="mt-1 text-sm text-muted-foreground">
                Create a compute job with CPU/GPU requirements and deadlines to start optimizing.
              </p>
              <Button onClick={openCreate} className="mt-4 bg-emerald-600 text-white hover:bg-emerald-700">
                <Plus className="mr-2 h-4 w-4" />
                Create your first job
              </Button>
            </div>
          ) : (
            <div className="overflow-x-auto">
              <table className="w-full min-w-[920px] text-left text-sm">
                <thead>
                  <tr className="border-b text-xs text-muted-foreground">
                    <th className="px-3 py-3">Name</th>
                    <th className="px-3 py-3">CPU / GPU hours</th>
                    <th className="px-3 py-3">Original start (SGT)</th>
                    <th className="px-3 py-3">Deadline (SGT)</th>
                    <th className="px-3 py-3">Flexibility</th>
                    <th className="px-3 py-3">Priority</th>
                    <th className="px-3 py-3">Exemption</th>
                    <th className="px-3 py-3">Status</th>
                    <th className="px-3 py-3">Actions</th>
                  </tr>
                </thead>
                <tbody>
                  {jobs.map((job) => (
                    <tr key={job.id} className="border-b last:border-0 hover:bg-muted/30">
                      <td className="px-3 py-3 font-medium text-foreground">{job.name}</td>
                      <td className="px-3 py-3">
                        {job.cpuHours} CPU · {job.gpuHours} GPU
                      </td>
                      <td className="px-3 py-3">{displayDate(job.originalStartTime)}</td>
                      <td className="px-3 py-3">{displayDate(job.deadline)}</td>
                      <td className="px-3 py-3">{`${job.flexibilityWindowHours}h`}</td>
                      <td className="px-3 py-3">{job.priority ?? "NORMAL"}</td>
                      <td className="px-3 py-3">
                        <div className="flex items-center gap-2">
                          <Checkbox
                            id={`job-exempt-${job.id}`}
                            checked={job.isExempt}
                            disabled={updatingExemptionId === job.id}
                            onCheckedChange={(checked) => void setJobExempt(job, Boolean(checked))}
                            aria-label={`${job.isExempt ? "Remove exemption from" : "Mark"} ${job.name} ${job.isExempt ? "as reschedulable" : "as exempt"
                              }`}
                          />
                          {job.isExempt ? (
                            <span className="rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-700 dark:text-amber-300">
                              Exempt
                            </span>
                          ) : (
                            <span className="text-xs text-muted-foreground">Flexible</span>
                          )}
                        </div>
                      </td>
                      <td className="px-3 py-3">
                        <span className="rounded-full bg-muted px-2 py-1 text-xs">{job.status}</span>
                      </td>
                      <td className="px-3 py-3">
                        <div className="flex gap-1">
                          <Button
                            size="icon"
                            variant="ghost"
                            aria-label={`View ${job.name}`}
                            onClick={() => void openView(job)}
                          >
                            <Eye className="h-4 w-4" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            aria-label={`Edit ${job.name}`}
                            onClick={() => openEdit(job)}
                          >
                            <Pencil className="h-4 w-4" />
                          </Button>
                          <Button
                            size="icon"
                            variant="ghost"
                            aria-label={`Delete ${job.name}`}
                            onClick={() => void remove(job)}
                          >
                            <Trash2 className="h-4 w-4 text-destructive" />
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      {/* Optimizer Results Dialog */}
      <Dialog open={optimizeOpen} onOpenChange={setOptimizeOpen}>
        <DialogContent className="sm:max-w-3xl max-h-[85vh] overflow-y-auto">
          <DialogHeader>
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2">
                <Sparkles className="h-5 w-5 text-teal-500" />
                <DialogTitle className="text-xl">Optimized Execution Schedule</DialogTitle>
              </div>
              {optimizationPlan && (
                <span
                  className={`px-2.5 py-1 rounded-full text-xs font-semibold uppercase tracking-wider ${optimizationPlan.status === "SCHEDULED"
                      ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                      : "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                    }`}
                >
                  {optimizationPlan.status}
                </span>
              )}
            </div>
            <DialogDescription>
              Mathematical solver results generated against wholesale ERCOT LMP price signals.
            </DialogDescription>
          </DialogHeader>

          {optimizationPlan && (
            <div className="space-y-5 py-3">
              {/* Facility Envelope Audit Info */}
              <div className="flex flex-wrap items-center justify-between gap-2 p-3 rounded-xl bg-muted/40 border border-border text-xs">
                <div className="flex items-center gap-2">
                  <Server className="h-4 w-4 text-teal-500" />
                  <span className="text-foreground">
                    Facility: <strong>{facility.name}</strong> ({facility.maxCpuHours} CPU-h · {facility.maxGpuHours} GPU-h / slot)
                  </span>
                </div>
                <span className="font-mono text-[11px] px-2 py-0.5 rounded bg-card border border-border text-muted-foreground">
                  Settlement Node: {facility.location || "LZ_NORTH"}
                </span>
              </div>

              {/* Cost Summary Cards */}
              <div className="grid grid-cols-1 sm:grid-cols-3 gap-3">
                <div className="rounded-xl border border-border bg-card p-4">
                  <span className="text-xs text-muted-foreground uppercase tracking-wider block font-medium">
                    Baseline Cost
                  </span>
                  <div className="text-xl font-bold font-mono mt-1 text-foreground">
                    ${optimizationPlan.baselineCostUsd.toFixed(2)}
                  </div>
                  <span className="text-[11px] text-muted-foreground">Original unshifted schedule</span>
                </div>

                <div className="rounded-xl border border-border bg-card p-4">
                  <span className="text-xs text-muted-foreground uppercase tracking-wider block font-medium">
                    Optimized Cost
                  </span>
                  <div className="text-xl font-bold font-mono mt-1 text-teal-600 dark:text-teal-400">
                    ${optimizationPlan.optimizedCostUsd.toFixed(2)}
                  </div>
                  <span className="text-[11px] text-muted-foreground">Shifted to low LMP valleys</span>
                </div>

                <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/10 p-4">
                  <span className="text-xs text-emerald-700 dark:text-emerald-300 uppercase tracking-wider block font-medium">
                    Projected Savings
                  </span>
                  <div className="text-xl font-bold font-mono mt-1 text-emerald-700 dark:text-emerald-300 flex items-center gap-1.5">
                    <TrendingUp className="h-4 w-4" />
                    ${optimizationPlan.projectedSavingsUsd.toFixed(2)}
                    <span className="text-xs font-normal">
                      ({optimizationPlan.projectedSavingsPercent.toFixed(1)}%)
                    </span>
                  </div>
                  <span className="text-[11px] text-emerald-700/80 dark:text-emerald-300/80">
                    {optimizationPlan.provenOptimal
                      ? "Proven optimal solver solution"
                      : "Simulated annealing heuristic"}
                  </span>
                </div>
              </div>

              {/* Plain English Solver Rationale */}
              <div className="rounded-xl border border-border/80 bg-muted/40 p-4">
                <span className="text-xs font-semibold text-foreground flex items-center gap-1.5 mb-1.5">
                  <Clock className="h-3.5 w-3.5 text-teal-500" />
                  Solver Rationale & Summary
                </span>
                <p className="text-xs text-muted-foreground leading-relaxed">
                  {optimizationPlan.plainEnglishExplanation}
                </p>
                {optimizationPlan.usedSeededFallbackPrices && (
                  <p className="text-[11px] text-amber-600 dark:text-amber-400 mt-2">
                    Note: Stored forecast prices were unavailable for parts of the horizon; historical fallback curve was utilized.
                  </p>
                )}
              </div>

              {/* Schedule Items Table */}
              <div>
                <h4 className="text-xs font-semibold text-muted-foreground uppercase tracking-wider mb-2">
                  Job Placement Details
                </h4>
                <div className="rounded-xl border border-border overflow-hidden">
                  <table className="w-full text-xs text-left">
                    <thead className="bg-muted/50 border-b border-border text-muted-foreground">
                      <tr>
                        <th className="py-2.5 px-3">Job</th>
                        <th className="py-2.5 px-3">Outcome</th>
                        <th className="py-2.5 px-3">Scheduled Start (SGT)</th>
                        <th className="py-2.5 px-3">Scheduled End (SGT)</th>
                        <th className="py-2.5 px-3 text-right">Cost</th>
                        <th className="py-2.5 px-3 text-right">Savings</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border/60">
                      {optimizationPlan.items.map((item: ScheduleItem) => (
                        <tr key={item.jobId} className="hover:bg-muted/20">
                          <td className="py-2.5 px-3 font-medium text-foreground">{item.jobName}</td>
                          <td className="py-2.5 px-3">
                            <span
                              className={`px-2 py-0.5 rounded-full text-[10px] font-semibold ${item.perJobStatus === "OPTIMISED"
                                  ? "bg-teal-500/15 text-teal-700 dark:text-teal-300 border border-teal-500/30"
                                  : item.perJobStatus === "UNCHANGED_EXEMPT"
                                    ? "bg-blue-500/15 text-blue-700 dark:text-blue-300 border border-blue-500/30"
                                    : "bg-rose-500/15 text-rose-700 dark:text-rose-300 border border-rose-500/30"
                                }`}
                            >
                              {item.perJobStatus}
                            </span>
                            {item.reason && (
                              <p className="text-[10px] text-muted-foreground mt-0.5">{item.reason}</p>
                            )}
                          </td>
                          <td className="py-2.5 px-3 text-muted-foreground">
                            {item.scheduledStart ? displayDate(item.scheduledStart) : "—"}
                          </td>
                          <td className="py-2.5 px-3 text-muted-foreground">
                            {item.scheduledEnd ? displayDate(item.scheduledEnd) : "—"}
                          </td>
                          <td className="py-2.5 px-3 text-right font-mono">
                            {item.optimizedCostUsd != null ? `$${item.optimizedCostUsd.toFixed(2)}` : "—"}
                          </td>
                          <td className="py-2.5 px-3 text-right font-mono text-emerald-600 dark:text-emerald-400 font-medium">
                            {item.savingsUsd != null ? `$${item.savingsUsd.toFixed(2)}` : "—"}
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          <DialogFooter className="flex flex-col sm:flex-row gap-2 justify-between">
            <div className="flex flex-wrap items-center gap-2">
              <Button
                type="button"
                variant="outline"
                onClick={applyScheduleToCalendar}
                disabled={applyingSchedule || !optimizationPlan}
                className="gap-1.5 text-xs text-emerald-600 dark:text-emerald-400 border-emerald-500/30"
              >
                <Calendar className="h-3.5 w-3.5" />
                {applyingSchedule ? "Applying to Calendar…" : "Apply to Workload Calendar"}
              </Button>
              {applySuccessMessage && (
                <Button
                  type="button"
                  asChild
                  className="bg-emerald-600 hover:bg-emerald-700 text-white gap-1.5 text-xs"
                >
                  <Link href="/workloads">
                    Open Calendar <ArrowRight className="h-3.5 w-3.5" />
                  </Link>
                </Button>
              )}
            </div>
            <Button type="button" onClick={() => setOptimizeOpen(false)}>
              Close
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Create / Edit Form Dialog */}
      <Dialog open={formOpen} onOpenChange={setFormOpen}>
        <DialogContent className="sm:max-w-xl max-h-[90vh] overflow-y-auto overflow-x-hidden">
          <form onSubmit={submit}>
            <DialogHeader>
              <DialogTitle>{editing ? "Edit compute job" : "Create compute job"}</DialogTitle>
              <DialogDescription>
                Enter resource requirements and allowed scheduling window. Times are shown in Singapore time (SGT).
              </DialogDescription>
            </DialogHeader>
            <div className="grid gap-4 py-5 sm:grid-cols-2">
              <Field label="Job name">
                <Input
                  value={form.name}
                  maxLength={255}
                  required
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="e.g. Model fine-tuning run"
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="CPU hours">
                  <Input
                    type="number"
                    min="0"
                    step="any"
                    required
                    value={form.cpuHours}
                    onChange={(e) => setForm({ ...form, cpuHours: e.target.value })}
                  />
                </Field>
                <Field label="GPU hours">
                  <Input
                    type="number"
                    min="0"
                    step="any"
                    required
                    value={form.gpuHours}
                    onChange={(e) => setForm({ ...form, gpuHours: e.target.value })}
                  />
                </Field>
              </div>
              <Field label="Original start time (SGT)">
                <JobDateTimePicker
                  id="job-original-start"
                  value={form.originalStartTime}
                  onChange={(val) => setForm({ ...form, originalStartTime: val })}
                  placeholder="Select start date & hour (SGT)..."
                  mode="start"
                  required
                />
              </Field>
              <Field label="Deadline (SGT)">
                <JobDateTimePicker
                  id="job-deadline"
                  value={form.deadline}
                  onChange={(val) => setForm({ ...form, deadline: val })}
                  placeholder="Select deadline (SGT)..."
                  mode="deadline"
                  minDateTime={form.originalStartTime}
                  required
                />
              </Field>
              {form.originalStartTime && form.deadline && (
                <div className="rounded-md bg-muted/50 p-2.5 text-xs flex items-center justify-between border border-border/60">
                  <span className="text-muted-foreground flex items-center gap-1.5">
                    <Clock className="h-3.5 w-3.5 text-emerald-500" />
                    <span>Scheduling Window:</span>
                  </span>
                  <span className={`font-mono font-medium ${form.deadline < form.originalStartTime ? "text-destructive font-semibold" : "text-foreground"}`}>
                    {getWindowDurationText(form.originalStartTime, form.deadline)}
                  </span>
                </div>
              )}
              <Field label="Flexibility window (hours)">
                <Input
                  type="number"
                  min="0"
                  step="1"
                  required
                  value={form.flexibilityWindowHours}
                  onChange={(e) => setForm({ ...form, flexibilityWindowHours: e.target.value })}
                />
              </Field>
              <Field label="Priority">
                <select
                  className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                  value={form.priority}
                  onChange={(e) => setForm({ ...form, priority: e.target.value as JobPriority })}
                >
                  <option value="CRITICAL">CRITICAL</option>
                  <option value="HIGH">HIGH</option>
                  <option value="NORMAL">NORMAL</option>
                  <option value="LOW">LOW</option>
                </select>
              </Field>
              <div className="flex items-center gap-3 self-end rounded-md border p-3 text-sm">
                <Checkbox
                  id="job-is-exempt"
                  checked={form.isExempt}
                  onCheckedChange={(isExempt) => setForm({ ...form, isExempt: Boolean(isExempt) })}
                />
                <label htmlFor="job-is-exempt" className="cursor-pointer">
                  <span className="block font-medium">Exempt from rescheduling</span>
                  <span className="text-xs text-muted-foreground">Keep this job at its original start time.</span>
                </label>
              </div>
            </div>
            {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
            <DialogFooter>
              <Button type="button" variant="outline" onClick={() => setFormOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={saving} className="bg-emerald-600 text-white hover:bg-emerald-700">
                {saving ? "Saving…" : editing ? "Save changes" : "Create job"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* View Dialog */}
      <Dialog open={viewOpen} onOpenChange={setViewOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{viewing?.name ?? "Job details"}</DialogTitle>
            <DialogDescription>Compute job details returned by the API.</DialogDescription>
          </DialogHeader>
          {viewing && (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-3 py-3 text-sm">
              <Detail label="Job ID" value={String(viewing.id)} />
              <Detail label="Status" value={viewing.status} />
              <Detail label="CPU hours" value={String(viewing.cpuHours)} />
              <Detail label="GPU hours" value={String(viewing.gpuHours)} />
              <Detail label="Original start" value={displayDate(viewing.originalStartTime)} />
              <Detail label="Deadline" value={displayDate(viewing.deadline)} />
              <Detail label="Flexibility" value={`${viewing.flexibilityWindowHours} hours`} />
              <Detail label="Priority" value={viewing.priority ?? "NORMAL"} />
              <Detail label="Exempt" value={viewing.isExempt ? "Yes" : "No"} />
            </dl>
          )}
          <DialogFooter>
            <Button onClick={() => setViewOpen(false)}>Close</Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="space-y-1.5 text-sm">
      <span className="block font-medium">{label}</span>
      {children}
    </label>
  )
}

function Detail({ label, value }: { label: string; value: string }) {
  return (
    <div>
      <dt className="text-xs text-muted-foreground">{label}</dt>
      <dd className="mt-1 font-medium">{value}</dd>
    </div>
  )
}
