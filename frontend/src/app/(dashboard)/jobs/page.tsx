"use client"

import * as React from "react"
import { useRouter, useSearchParams } from "next/navigation"
import { BriefcaseBusiness, Clock, Ellipsis, Lock, LockOpen, Pencil, Plus, RefreshCw, Trash2, X } from "lucide-react"
import {
  jobsApi,
  priceApi,
  facilityApi,
  authApi,
  type User,
  type ComputeJob,
  type ComputeJobPayload,
  type JobPriority,
  type WorkloadCategory,
  WORKLOAD_CATEGORY_LABELS,
  PRIORITY_LABELS,
  type FacilityConfig,
} from "@/lib/api"
import { currentMarketHourInput, formatMarketDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { ConstraintParseReview } from "@/components/jobs/ConstraintParseReview"
import { BLOCKED_NUMBER_KEYS, JOB_TEMPLATES, effectiveFlexHours, forecastEndMs, maxJobHours, usesEstimatedPrices, type ScheduleMode, toInputDate, toApiUtcDate, windowHours } from "@/lib/job-form-utils"
import { canRecompute, isPlanning, jobStatus } from "@/lib/job-status"
import { JobDateTimePicker } from "@/components/jobs/JobDateTimePicker"

type FormState = {
  name: string
  description: string
  cpuHours: string
  gpuHours: string
  originalStartTime: string
  deadline: string
  flexibilityWindowHours: string // "" = whole window (see effectiveFlexHours)
  scheduleMode: ScheduleMode // "locked" is saved as isExempt
  priority: JobPriority
  workloadCategory: WorkloadCategory
  rawConstraints?: string | null
  parsedAt?: string | null
  confirmationOutcome?: ComputeJobPayload["confirmationOutcome"]
}

const blankForm = (): FormState => ({
  name: "",
  description: "",
  cpuHours: "0",
  gpuHours: "0",
  originalStartTime: "",
  deadline: "",
  flexibilityWindowHours: "",
  scheduleMode: "flexible",
  priority: "NORMAL",
  workloadCategory: "ML_TRAINING",
})

// The table headers already name the zone, so rows leave it off to save width.
const displayDate = (value?: string, zone = true) => (value ? formatMarketDateTime(value, zone) : "—")

const getWindowDurationText = (start: string, end: string) => {
  const diffHours = windowHours(start, end)
  if (!start || !end || !Number.isFinite(diffHours)) return ""
  if (diffHours < 0) return "⚠️ Deadline is before start time"
  if (diffHours === 0) return "0 hours (starts at deadline)"
  return `${diffHours % 1 === 0 ? diffHours : diffHours.toFixed(1)} hours`
}

// The shell's "Create Job" button links here with ?new=1. useSearchParams needs its own Suspense boundary.
function OpenCreateOnParam({ onOpen }: { onOpen: () => void }) {
  const wanted = useSearchParams().get("new") === "1"
  const router = useRouter()
  React.useEffect(() => {
    if (!wanted) return
    onOpen()
    router.replace("/jobs") // clear the param so a refresh does not reopen the form
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [wanted])
  return null
}

// The Schedule page's "Open job" link arrives as ?job={id}. Same Suspense and clear-the-param rules as ?new=1.
function OpenJobOnParam({ onOpen }: { onOpen: (id: number) => void }) {
  const raw = useSearchParams().get("job")
  const id = raw !== null && /^\d+$/.test(raw) ? Number(raw) : null
  const router = useRouter()
  React.useEffect(() => {
    if (id === null) return
    onOpen(id)
    router.replace("/jobs")
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id])
  return null
}

export default function JobsPage() {
  const [jobs, setJobs] = React.useState<ComputeJob[]>([])
  // An admin can list every user's jobs. Everyone else sees their own.
  const [scope, setScope] = React.useState<"mine" | "all">("mine")
  const [me, setMe] = React.useState<User | null>(null)
  React.useEffect(() => {
    let active = true
    authApi.getMe().then((r) => { if (active && r.data) setMe(r.data) }).catch(() => {}) // no admin switch if unknown
    return () => { active = false }
  }, [])
  const isAdmin = me?.role === "ROLE_ADMIN"
  const [loading, setLoading] = React.useState(true)
  const [saving, setSaving] = React.useState(false)
  const [updatingExemptionId, setUpdatingExemptionId] = React.useState<number | null>(null)
  const [error, setError] = React.useState("")
  const [formOpen, setFormOpen] = React.useState(false)
  const [viewOpen, setViewOpen] = React.useState(false)
  const [editing, setEditing] = React.useState<ComputeJob | null>(null)
  const [viewing, setViewing] = React.useState<ComputeJob | null>(null)
  const [parseReviewPending, setParseReviewPending] = React.useState(false)
  // End of the stored forecast (ms); null until known. Drives the "Estimated prices" flag and warning.
  const [forecastEnd, setForecastEnd] = React.useState<number | null>(null)
  const [template, setTemplate] = React.useState("") // label of the chosen template; "" = none
  const [estimatedToConfirm, setEstimatedToConfirm] = React.useState<ComputeJobPayload | null>(null)
  const [form, setForm] = React.useState<FormState>(blankForm)

  const [recomputing, setRecomputing] = React.useState(false)
  // The jobs the open delete confirmation is about: one from the detail dialog, or the checked rows.
  const [jobsToDelete, setJobsToDelete] = React.useState<ComputeJob[]>([])
  const [selectedIds, setSelectedIds] = React.useState<Set<number>>(new Set())
  const [deletingJob, setDeletingJob] = React.useState(false)
  const [menuJobId, setMenuJobId] = React.useState<number | null>(null) // row whose "⋯" menu is open

  // The shared facility, read only here (it sets the form limits). An admin changes it on the Admin page.
  const [facility, setFacility] = React.useState<FacilityConfig>({
    name: "Primary Compute Cluster",
    maxCpuHours: 256.0,
    maxGpuHours: 64.0,
    location: "LZ_NORTH",
  })
  const [facilityError, setFacilityError] = React.useState<string | null>(null)

  React.useEffect(() => {
    let active = true
    priceApi.getForecast({ location: facility.location, hours: 168 })
      .then((r) => { if (active && r.data) setForecastEnd(forecastEndMs(r.data.points)) })
      .catch(() => { if (active) setForecastEnd(null) }) // unknown forecast: show no warning rather than a wrong one
    return () => { active = false }
  }, [facility.location])

  const loadJobs = React.useCallback(async () => {
    setLoading(true)
    setError("")
    try {
      const response = await (scope === "all" ? jobsApi.listAll() : jobsApi.list())
      setJobs(response.data ?? [])
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load jobs")
    } finally {
      setLoading(false)
    }
  }, [scope])

  React.useEffect(() => {
    let active = true
    ;(scope === "all" ? jobsApi.listAll() : jobsApi.list())
      .then((response) => {
        if (active) setJobs(response.data ?? [])
      })
      .catch((e) => {
        if (active) setError(e instanceof Error ? e.message : "Could not load jobs")
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [scope])

  React.useEffect(() => {
    let active = true
    facilityApi.get().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load facility settings")
      setFacility(response.data)
    }).catch(error => {
      if (active) setFacilityError(error instanceof Error ? error.message : "Could not load facility settings")
    })

    return () => {
      active = false
    }
  }, [])

  const maxCpu = maxJobHours(Number(facility.maxCpuHours), form.originalStartTime, form.deadline)
  const maxGpu = maxJobHours(Number(facility.maxGpuHours), form.originalStartTime, form.deadline)
  const blockNumberKeys = (e: React.KeyboardEvent) => { if (BLOCKED_NUMBER_KEYS.includes(e.key)) e.preventDefault() }

  const openCreate = () => {
    setEditing(null)
    setParseReviewPending(false)
    setTemplate("")
    setForm({ ...blankForm(), originalStartTime: currentMarketHourInput() })
    setError("")
    setFormOpen(true)
  }

  const openEdit = (job: ComputeJob) => {
    setParseReviewPending(false)
    setEditing(job)
    setError("")
    setForm({
      name: job.name,
      description: job.description ?? "",
      cpuHours: String(job.cpuHours),
      gpuHours: String(job.gpuHours),
      originalStartTime: toInputDate(job.originalStartTime),
      deadline: toInputDate(job.deadline),
      flexibilityWindowHours: String(job.flexibilityWindowHours),
      scheduleMode: job.isExempt ? "locked" : "flexible",
      priority: job.priority ?? "NORMAL",
      workloadCategory: job.workloadCategory ?? "ML_TRAINING",
      rawConstraints: job.rawConstraints,
      parsedAt: job.parsedAt,
      confirmationOutcome: job.confirmationOutcome,
    })
    setFormOpen(true)
  }

  const openViewById = async (id: number, fallback?: ComputeJob) => {
    setError("")
    try {
      const response = await jobsApi.get(id)
      const job = response.data ?? fallback
      if (!job) throw new Error("Could not load job details")
      setViewing(job)
      setViewOpen(true)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not load job details")
    }
  }
  const openView = (job: ComputeJob) => openViewById(job.id, job)

  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError("")
    if (parseReviewPending) {
      setError("Confirm the proposed constraints or restore the original text before saving.")
      return
    }
    const cpuHours = Number(form.cpuHours)
    const gpuHours = Number(form.gpuHours)
    if (!Number.isFinite(cpuHours) || !Number.isFinite(gpuHours) || cpuHours < 0 || gpuHours < 0) {
      setError("CPU and GPU hours must be plain non-negative numbers.")
      return
    }
    if (cpuHours === 0 && gpuHours === 0) {
      setError("Enter CPU hours, GPU hours, or both.")
      return
    }
    // Pasted values such as "1e9" pass the key block, so the cap is enforced here too.
    if (cpuHours > maxCpu || gpuHours > maxGpu) {
      setError(`Hours exceed what the facility can run in this window (max ${maxCpu} CPU-h, ${maxGpu} GPU-h).`)
      return
    }
    if (form.deadline < form.originalStartTime) {
      setError("Deadline must be at or after the start time.")
      return
    }
    // Checked here as well as server-side so the operator sees market time rather than the API's UTC
    // phrasing. A job starting in an elapsed hour can never be run, so it is rejected on save rather
    // than left for the optimiser to report as unschedulable.
    const earliest = currentMarketHourInput()
    const flexibilityWindowHours = effectiveFlexHours(form.scheduleMode, form.flexibilityWindowHours, form.originalStartTime, form.deadline)
    // On an edit only a time the operator changed is checked, matching the backend: a started job stays
    // editable (Lock switch, hours) as long as the edit does not move a time into the past.
    const startChanged = !editing || form.originalStartTime !== toInputDate(editing.originalStartTime)
    const deadlineChanged = !editing || form.deadline !== toInputDate(editing.deadline)
    if (startChanged && form.originalStartTime < earliest) {
      setError(`Start time has passed. Choose ${earliest.replace("T", " ")} ${MARKET_ZONE_LABEL} or later.`)
      return
    }
    if (deadlineChanged && form.deadline <= earliest) {
      setError(`Deadline has passed. Choose a deadline after ${earliest.replace("T", " ")} ${MARKET_ZONE_LABEL}.`)
      return
    }
    // originalStartTime is formatted to exact hour (00 minutes) per optimizer specification
    const payload: ComputeJobPayload = {
      name: form.name.trim(),
      description: form.description.trim(),
      cpuHours,
      gpuHours,
      originalStartTime: toApiUtcDate(form.originalStartTime, true),
      deadline: toApiUtcDate(form.deadline, false),
      flexibilityWindowHours,
      priority: form.priority,
      workloadCategory: form.workloadCategory,
      isExempt: form.scheduleMode === "locked",
      rawConstraints: form.rawConstraints,
      parsedAt: form.parsedAt,
      confirmationOutcome: form.confirmationOutcome,
    }
    // Ask once when the save newly pushes the window past the forecast (an edit that already was past it stays quiet).
    if (usesEstimatedPrices(payload.deadline, forecastEnd) && !(editing && usesEstimatedPrices(editing.deadline, forecastEnd))) {
      setEstimatedToConfirm(payload)
      return
    }
    await persist(payload)
  }

  const persist = async (payload: ComputeJobPayload) => {
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
      workloadCategory: job.workloadCategory ?? "ML_TRAINING",
      isExempt,
    }
    try {
      const response = await jobsApi.update(job.id, payload)
      const updated = response.data ?? { ...job, isExempt }
      setJobs((current) => current.map((item) => (item.id === job.id ? updated : item)))
      setViewing((current) => (current?.id === job.id ? updated : current))
    } catch (e) {
      setError(e instanceof Error ? e.message : `Could not update exemption for ${job.name}`)
    } finally {
      setUpdatingExemptionId(null)
    }
  }

  const handleConfirmDeleteJobs = async () => {
    if (!jobsToDelete.length) return
    setDeletingJob(true)
    setError("")
    const results = await Promise.allSettled(jobsToDelete.map((job) => jobsApi.remove(job.id)))
    const deleted = new Set(jobsToDelete.filter((_, i) => results[i].status === "fulfilled").map((job) => job.id))
    const failed = jobsToDelete.filter((job) => !deleted.has(job.id))
    setJobs((current) => current.filter((item) => !deleted.has(item.id)))
    setSelectedIds((current) => new Set([...current].filter((id) => !deleted.has(id))))
    // Failed jobs stay selected, so a retry is one click.
    setJobsToDelete([])
    if (failed.length) setError(`Could not delete: ${failed.map((job) => job.name).join(", ")}.`)
    setDeletingJob(false)
  }

  /** Only the owner edits a job, and not once it is in an approved plan. Delete stays open. */
  const canEdit = (job: ComputeJob) => !job.approvedRunId && (scope === "mine" || job.ownerEmail?.toLowerCase() === me?.email?.toLowerCase())

  const selectedJobs = jobs.filter((job) => selectedIds.has(job.id))
  // Rows have no divider lines. A run of ticked rows is one rounded block: only its outer corners are round.
  // Cells carry the tint and the corners because border-radius does not work on rows or collapsed borders.
  const isTicked = (i: number) => i >= 0 && i < jobs.length && selectedIds.has(jobs[i].id)
  const rowClass = (i: number) => {
    const ticked = isTicked(i)
    const parts = [ticked ? "[&>td]:bg-primary/10 [&:hover>td]:bg-primary/15" : "hover:bg-muted/30"]
    if (ticked && !isTicked(i - 1)) parts.push("[&>td:first-child]:rounded-tl-lg [&>td:last-child]:rounded-tr-lg")
    if (ticked && !isTicked(i + 1)) parts.push("[&>td:first-child]:rounded-bl-lg [&>td:last-child]:rounded-br-lg")
    return parts.join(" ")
  }
  const toggleJob = (id: number, checked: boolean) =>
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })

  const ownJobs = scope === "all" ? jobs.filter(job => job.ownerEmail?.toLowerCase() === me?.email?.toLowerCase()) : jobs

  /** Reloads the list without the loading state, so the table does not flash. */
  const refreshJobs = React.useCallback(async () => {
    try {
      const response = await (scope === "all" ? jobsApi.listAll() : jobsApi.list())
      setJobs(response.data ?? [])
    } catch {
      // Keep what is shown. The next tick tries again.
    }
  }, [scope])

  const [refreshing, setRefreshing] = React.useState(false)
  const refreshClick = async () => {
    setRefreshing(true)
    await refreshJobs()
    setRefreshing(false)
  }

  // The system plans new jobs within a minute, so keep looking while one is still Scheduling.
  const planning = isPlanning(jobs)
  React.useEffect(() => {
    if (!planning) return
    const timer = window.setInterval(() => void refreshJobs(), 30_000)
    return () => window.clearInterval(timer)
  }, [planning, refreshJobs])

  /** Manual fallback for the automatic planner. */
  const recompute = async () => {
    setError("")
    setRecomputing(true)
    try {
      const response = await jobsApi.recompute()
      const failed = response.data?.failed ?? 0
      if (failed > 0) setError(`${failed} ${failed === 1 ? "job" : "jobs"} could not be planned. Open ${failed === 1 ? "it" : "them"} to see why.`)
      await refreshJobs()
    } catch (e) {
      setError(e instanceof Error ? e.message : "Could not plan your jobs")
    } finally {
      setRecomputing(false)
    }
  }

  return (
    <div className="space-y-6" id="jobs-root">
      <React.Suspense fallback={null}><OpenCreateOnParam onOpen={openCreate} /></React.Suspense>
      <React.Suspense fallback={null}><OpenJobOnParam onOpen={(id) => void openViewById(id)} /></React.Suspense>
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <h1 className="text-2xl font-bold tracking-tight text-foreground">{scope === "all" ? "All jobs" : "My jobs"}</h1>
          {isAdmin && (
            <div role="group" aria-label="Jobs to show" className="flex rounded-md border">
              {(["mine", "all"] as const).map((value) => (
                <Button
                  key={value}
                  size="sm"
                  variant={scope === value ? "secondary" : "ghost"}
                  aria-pressed={scope === value}
                  onClick={() => { if (scope !== value) { setScope(value); setSelectedIds(new Set()); setLoading(true) } }}
                >
                  {value === "mine" ? "Mine" : "All"}
                </Button>
              ))}
            </div>
          )}
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {/* The system plans new jobs by itself. This is the manual fallback. */}
          {canRecompute(ownJobs) && (
            <Button variant="outline" onClick={() => void recompute()} disabled={recomputing || loading}>
              <RefreshCw className={`mr-2 h-4 w-4 ${recomputing ? "animate-spin" : ""}`} />
              {recomputing ? "Planning…" : "Recompute"}
            </Button>
          )}
          <Button variant="ghost" size="icon" aria-label="Refresh jobs" title="Refresh jobs" onClick={() => void refreshClick()} disabled={refreshing || loading}>
            <RefreshCw className={`h-4 w-4 ${refreshing ? "animate-spin" : ""}`} />
          </Button>
          <Button onClick={openCreate}>
            <Plus className="mr-2 h-4 w-4" />
            New job
          </Button>
        </div>
      </div>

      {facilityError && <p role="alert" className="text-sm text-destructive">{facilityError}. The form is using default limits. Reload the page to load the real ones.</p>}

      {error && (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      <Card>
        <CardContent className="pb-3">
          {loading ? (
            <p className="py-8 text-center text-sm text-muted-foreground">Loading jobs…</p>
          ) : jobs.length === 0 ? (
            <div className="py-10 text-center">
              <BriefcaseBusiness className="h-10 w-10 mx-auto text-muted-foreground/40 mb-3" />
              <p className="font-medium text-foreground">No compute jobs yet</p>
              <Button onClick={openCreate} className="mt-4">
                <Plus className="mr-2 h-4 w-4" />
                Create your first job
              </Button>
            </div>
          ) : (
            <table className="w-full border-separate border-spacing-0 text-left text-xs">
              <thead>
                <tr className="text-xs text-muted-foreground [&>th]:border-b">
                  <th className="w-10 px-2 pb-3 pt-4 sm:px-3"><span className="sr-only">Select</span></th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 2xl:table-cell">ID</th>
                  <th className="w-full px-2 pb-3 pt-4 sm:px-3">Name</th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 2xl:table-cell">CPU hours</th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 2xl:table-cell">GPU hours</th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 md:table-cell">Start ({MARKET_ZONE_LABEL})</th>
                  <th className="whitespace-nowrap px-2 pb-3 pt-4 sm:px-3">Deadline ({MARKET_ZONE_LABEL})</th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 xl:table-cell">Priority</th>
                  <th className="hidden whitespace-nowrap px-2 pb-3 pt-4 sm:px-3 xl:table-cell">Job type</th>
                  <th className="whitespace-nowrap px-2 pb-3 pt-4 sm:px-3">Status</th>
                  <th className="w-10 px-2 pb-3 pt-4 sm:px-3"><span className="sr-only">Actions</span></th>
                </tr>
                {/* Gap between the header line and the first row, outside the row so hover does not cover it. */}
                <tr aria-hidden="true"><th colSpan={11} className="h-3 p-0" /></tr>
              </thead>
              <tbody>
                {jobs.map((job, i) => (
                  <tr
                    key={job.id}
                    className={`group/row cursor-pointer ${rowClass(i)}`}
                    onClick={() => void openView(job)}
                  >
                    <td className="px-2 py-2 sm:px-3" onClick={(e) => e.stopPropagation()}>
                      <Checkbox
                        checked={selectedIds.has(job.id)}
                        className={revealClass(selectedJobs.length > 0)}
                        onCheckedChange={(checked) => toggleJob(job.id, checked)}
                        aria-label={`Select ${job.name}`}
                      />
                    </td>
                    <td className="hidden whitespace-nowrap px-2 py-2 font-mono text-muted-foreground sm:px-3 2xl:table-cell">#{job.id}</td>
                    <td className="max-w-0 px-2 py-2 font-medium text-foreground sm:px-3">
                      <div className="flex items-center gap-2">
                        <button
                          type="button"
                          title={job.name}
                          className="min-w-0 truncate text-left hover:underline focus-visible:underline focus-visible:outline-none"
                          onClick={(e) => { e.stopPropagation(); void openView(job) }}
                        >
                          {job.name}
                        </button>
                        {usesEstimatedPrices(job.deadline, forecastEnd) && <EstimatedPriceBadge compact />}
                      </div>
                    </td>
                    <td className="hidden whitespace-nowrap px-2 py-2 sm:px-3 2xl:table-cell">{job.cpuHours}</td>
                    <td className="hidden whitespace-nowrap px-2 py-2 sm:px-3 2xl:table-cell">{job.gpuHours}</td>
                    <td className="hidden whitespace-nowrap px-2 py-2 sm:px-3 md:table-cell">{displayDate(job.originalStartTime, false)}</td>
                    <td className="whitespace-nowrap px-2 py-2 sm:px-3">{displayDate(job.deadline, false)}</td>
                    <td className="hidden whitespace-nowrap px-2 py-2 sm:px-3 xl:table-cell">{PRIORITY_LABELS[job.priority ?? "NORMAL"]}</td>
                    <td className="hidden whitespace-nowrap px-2 py-2 sm:px-3 xl:table-cell">{WORKLOAD_CATEGORY_LABELS[job.workloadCategory ?? "ML_TRAINING"]}</td>
                    <td className="whitespace-nowrap px-2 py-2 sm:px-3">
                      <StatusBadge status={job.status} />
                    </td>
                    {/* Popover content is portaled, but React events still bubble to this cell, so the row click stays out. */}
                    <td className="px-2 py-2 sm:px-3" onClick={(e) => e.stopPropagation()}>
                      <Popover open={menuJobId === job.id} onOpenChange={(open) => setMenuJobId(open ? job.id : null)}>
                        <PopoverTrigger asChild>
                          <Button
                            variant="ghost"
                            size="icon"
                            className={`h-8 w-8 data-[state=open]:opacity-100 ${revealClass(false)}`}
                            aria-label={`Actions for ${job.name}`}
                          >
                            <Ellipsis className="h-4 w-4" />
                          </Button>
                        </PopoverTrigger>
                        <PopoverContent align="end" className="w-48 p-1">
                          <MenuItem onClick={() => { setMenuJobId(null); void openView(job) }}>View details</MenuItem>
                          {canEdit(job) && (
                            <MenuItem onClick={() => { setMenuJobId(null); openEdit(job) }}>
                              <Pencil className="h-4 w-4" /> Edit
                            </MenuItem>
                          )}
                          {canEdit(job) && (
                            <MenuItem disabled={updatingExemptionId === job.id} onClick={() => { setMenuJobId(null); void setJobExempt(job, !job.isExempt) }}>
                              {job.isExempt ? <LockOpen className="h-4 w-4" /> : <Lock className="h-4 w-4" />}
                              {job.isExempt ? "Unlock schedule" : "Lock schedule"}
                            </MenuItem>
                          )}
                          <MenuItem className="text-destructive" onClick={() => { setMenuJobId(null); setJobsToDelete([job]) }}>
                            <Trash2 className="h-4 w-4" /> Delete
                          </MenuItem>
                        </PopoverContent>
                      </Popover>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      {/* Bulk actions, floating over the list while rows are ticked */}
      {selectedJobs.length > 0 && (
        <div className="fixed bottom-6 left-1/2 z-40 flex -translate-x-1/2 items-center gap-3 rounded-lg border bg-popover px-4 py-2 text-popover-foreground shadow-lg">
          <span role="status" className="text-sm">{selectedJobs.length} selected</span>
          <Button variant="destructive" size="sm" onClick={() => setJobsToDelete(selectedJobs)}>
            <Trash2 className="mr-2 h-4 w-4" />
            Delete
          </Button>
          <Button variant="ghost" size="icon" className="h-8 w-8" aria-label="Clear selection" onClick={() => setSelectedIds(new Set())}>
            <X className="h-4 w-4" />
          </Button>
        </div>
      )}

      {/* Create / Edit Form Dialog */}
      <Dialog open={formOpen} onOpenChange={setFormOpen}>
        <DialogContent className="sm:max-w-xl max-h-[90vh] overflow-y-auto overflow-x-hidden">
          <form onSubmit={submit}>
            <DialogHeader>
              <DialogTitle>{editing ? "Edit compute job" : "Create compute job"}</DialogTitle>
              <DialogDescription>
                Enter resource requirements and allowed scheduling window. Times are shown in ERCOT market local time (Central).
              </DialogDescription>
            </DialogHeader>
            <div className="grid gap-4 py-6 sm:grid-cols-2">
              <Field label="Job title">
                <Input
                  value={form.name}
                  maxLength={255}
                  required
                  onChange={(e) => setForm({ ...form, name: e.target.value })}
                  placeholder="e.g. Model fine-tuning run"
                />
              </Field>
              {!editing && (
                <Field label="Template (optional)">
                  <select
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    value={template}
                    onChange={(e) => {
                      setTemplate(e.target.value)
                      const t = JOB_TEMPLATES.find((item) => item.label === e.target.value)
                      if (t) setForm((f) => ({ ...f, cpuHours: t.cpuHours, gpuHours: t.gpuHours, workloadCategory: t.workloadCategory, priority: t.priority, name: f.name || t.label }))
                    }}
                  >
                    <option value="">None</option>
                    {JOB_TEMPLATES.map((t) => <option key={t.label} value={t.label}>{t.label}</option>)}
                  </select>
                </Field>
              )}
              <div className="space-y-2 text-sm sm:col-span-2">
                <label htmlFor="job-description" className="block font-medium">Description (optional)</label>
                <textarea
                  id="job-description"
                  rows={3}
                  maxLength={1000}
                  className="w-full rounded-md border bg-background px-4 py-2 text-sm"
                  placeholder="What this job does"
                  value={form.description}
                  onChange={(e) => setForm({ ...form, description: e.target.value })}
                />
              </div>
              <div className="grid grid-cols-2 gap-4 sm:col-span-2">
                <Field label={`CPU hours (limit ${facility.maxCpuHours} per hour)`}>
                  <Input
                    type="number"
                    min="0"
                    step="any"
                    required
                    value={form.cpuHours}
                    onKeyDown={blockNumberKeys}
                    onChange={(e) => setForm({ ...form, cpuHours: e.target.value })}
                  />
                </Field>
                <Field label={`GPU hours (limit ${facility.maxGpuHours} per hour)`}>
                  <Input
                    type="number"
                    min="0"
                    step="any"
                    required
                    value={form.gpuHours}
                    onKeyDown={blockNumberKeys}
                    onChange={(e) => setForm({ ...form, gpuHours: e.target.value })}
                  />
                </Field>
              </div>
              <div className="grid gap-4 sm:col-span-2 sm:grid-cols-2">
                <Field label="Priority">
                  <select
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    value={form.priority}
                    onChange={(e) => setForm({ ...form, priority: e.target.value as JobPriority })}
                  >
                    {(Object.keys(PRIORITY_LABELS) as JobPriority[]).map((p) => (
                      <option key={p} value={p}>{PRIORITY_LABELS[p]}</option>
                    ))}
                  </select>
                </Field>
                <Field label="Job type">
                  <select
                    className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
                    value={form.workloadCategory}
                    onChange={(e) => setForm({ ...form, workloadCategory: e.target.value as WorkloadCategory })}
                  >
                    {Object.entries(WORKLOAD_CATEGORY_LABELS).map(([value, label]) => (
                      <option key={value} value={value}>{label}</option>
                    ))}
                  </select>
                </Field>
              </div>
              <div className="grid content-start gap-4 rounded-md border p-4 sm:col-span-2">
                <Field label={`Start time (${MARKET_ZONE_LABEL})`}>
                  <JobDateTimePicker
                    id="job-original-start"
                    value={form.originalStartTime}
                    onChange={(val) => setForm({ ...form, originalStartTime: val })}
                    placeholder={`Select start date & hour (${MARKET_ZONE_LABEL})...`}
                    mode="start"
                    required
                  />
                </Field>
                <Field label={`Deadline (${MARKET_ZONE_LABEL})`}>
                  <JobDateTimePicker
                    id="job-deadline"
                    value={form.deadline}
                    onChange={(val) => setForm({ ...form, deadline: val })}
                    placeholder={`Select deadline (${MARKET_ZONE_LABEL})...`}
                    mode="deadline"
                    minDateTime={form.originalStartTime}
                    required
                  />
                </Field>
                <div className="mt-2 rounded-md bg-muted/50 p-2 text-xs flex min-h-10 items-center justify-between border border-border/60">
                  <span className="text-muted-foreground flex items-center gap-2">
                    <Clock className="h-3.5 w-3.5 text-emerald-500" />
                    <span>Scheduling Window:</span>
                  </span>
                  <span className={`font-mono font-medium ${form.deadline && form.deadline < form.originalStartTime ? "text-destructive font-semibold" : "text-foreground"}`}>
                    {getWindowDurationText(form.originalStartTime, form.deadline) || "—"}
                  </span>
                </div>
              </div>
              <fieldset className="grid gap-2 sm:col-span-2">
                <legend className="mb-2 text-sm font-medium">Scheduling</legend>
                {([["flexible", "Flexible schedule", "The scheduler may move this job to cheaper hours before the deadline."],
                  ["locked", "Lock schedule", "The scheduler will not move this job. It runs at the start time you choose."]] as const).map(([mode, label, help]) => (
                  <label key={mode} className="flex cursor-pointer items-start gap-2 rounded-md border p-4 text-sm">
                    <input
                      type="radio"
                      name="schedule-mode"
                      className="mt-1"
                      checked={form.scheduleMode === mode}
                      onChange={() => setForm({ ...form, scheduleMode: mode, flexibilityWindowHours: "" })}
                    />
                    <span>
                      <span className="block font-medium">{label}</span>
                      <span className="text-xs text-muted-foreground">
                        {mode === "flexible" && Number(form.flexibilityWindowHours) > 0 ? `The scheduler may delay the start by up to ${form.flexibilityWindowHours} h.` : help}
                      </span>
                    </span>
                  </label>
                ))}
              </fieldset>
            </div>
            {formOpen && <ConstraintParseReview
              draft={{
                originalStartTime: form.originalStartTime ? toApiUtcDate(form.originalStartTime, true) : null,
                deadline: form.deadline ? toApiUtcDate(form.deadline) : null,
                flexibilityWindowHours: form.scheduleMode === "locked" ? 0 : form.flexibilityWindowHours === "" ? null : Number(form.flexibilityWindowHours),
                priority: form.priority,
                isExempt: form.scheduleMode === "locked",
              }}
              confirmedSource={form.rawConstraints ?? ""}
              onPendingChange={setParseReviewPending}
              onConfirm={(result, rawConstraints) => {
                setForm((current) => ({
                  ...current,
                  originalStartTime: result.fields.originalStartTime ? toInputDate(result.fields.originalStartTime) : "",
                  deadline: result.fields.deadline ? toInputDate(result.fields.deadline) : "",
                  flexibilityWindowHours: result.fields.flexibilityWindowHours == null ? "" : String(result.fields.flexibilityWindowHours),
                  scheduleMode: (result.fields.isExempt ?? current.scheduleMode === "locked") ? "locked" : "flexible",
                  priority: result.fields.priority ?? current.priority,
                  rawConstraints,
                  parsedAt: result.parsedAt,
                  confirmationOutcome: "CONFIRMED",
                }))
                setError("")
              }}
            />}
            {error && <p role="alert" className="my-4 text-sm text-destructive">{error}</p>}
            <DialogFooter className="mt-6">
              <Button type="button" variant="outline" onClick={() => setFormOpen(false)}>
                Cancel
              </Button>
              <Button type="submit" disabled={saving || parseReviewPending}>
                {saving ? "Saving…" : editing ? "Save changes" : "Create job"}
              </Button>
            </DialogFooter>
          </form>
        </DialogContent>
      </Dialog>

      {/* Estimated-price confirmation (masterplan D10) */}
      <Dialog open={!!estimatedToConfirm} onOpenChange={(open) => { if (!open && !saving) setEstimatedToConfirm(null) }}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>Prices are estimated for this job</DialogTitle>
            <DialogDescription>
              Prices for this period are estimated from history, not forecast. The plan can change when forecast data arrives.
            </DialogDescription>
          </DialogHeader>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={saving} onClick={() => setEstimatedToConfirm(null)}>Cancel</Button>
            <Button
              type="button"
              disabled={saving}
              onClick={async () => { const p = estimatedToConfirm; setEstimatedToConfirm(null); if (p) await persist(p) }}
            >
              {editing ? "Save changes" : "Create job"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Detail dialog: Edit, Delete and the lock live here, not in the table. */}
      <Dialog open={viewOpen} onOpenChange={setViewOpen}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>{viewing?.name ?? "Job details"}</DialogTitle>
            <DialogDescription className="sr-only">Job details</DialogDescription>
          </DialogHeader>
          {viewing && (
            <>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-3 py-3 text-sm">
                <Detail label="Job ID" value={String(viewing.id)} />
                {viewing.ownerName && <Detail label="Owner" value={viewing.ownerName} />}
                {viewing.description && <Detail label="Description" value={viewing.description} />}
                {usesEstimatedPrices(viewing.deadline, forecastEnd) && <EstimatedPriceBadge />}
                <Detail label="Status" value={jobStatus(viewing.status).label} />
                {viewing.statusReason && <Detail label="Reason" value={viewing.statusReason} />}
                <Detail label="CPU hours" value={String(viewing.cpuHours)} />
                <Detail label="GPU hours" value={String(viewing.gpuHours)} />
                <Detail label="Start" value={displayDate(viewing.originalStartTime)} />
                <Detail label="Deadline" value={displayDate(viewing.deadline)} />
                <Detail label="Priority" value={PRIORITY_LABELS[viewing.priority ?? "NORMAL"]} />
                <Detail label="Job type" value={WORKLOAD_CATEGORY_LABELS[viewing.workloadCategory ?? "ML_TRAINING"]} />
                <Detail label="Scheduling" value={viewing.isExempt ? "Locked" : "Flexible"} />
                {viewing.approvedRunId && <Detail label="Plan" value="Approved" />}
              </dl>
              {canEdit(viewing) && (
                <label className="flex items-center gap-2 text-sm font-medium">
                  <Checkbox
                    checked={viewing.isExempt}
                    disabled={updatingExemptionId === viewing.id}
                    onCheckedChange={(checked) => void setJobExempt(viewing, checked)}
                  />
                  Lock schedule
                </label>
              )}
            </>
          )}
          <DialogFooter className="sm:justify-between sm:space-x-0">
            <Button
              variant="outline"
              className="text-destructive"
              onClick={() => { if (viewing) { setJobsToDelete([viewing]); setViewOpen(false) } }}
            >
              <Trash2 className="mr-2 h-4 w-4" />
              Delete
            </Button>
            <div className="flex flex-col-reverse gap-2 sm:flex-row">
              <Button variant="outline" onClick={() => setViewOpen(false)}>Close</Button>
              {viewing && canEdit(viewing) && (
                <Button onClick={() => { setViewOpen(false); openEdit(viewing) }}>
                  <Pencil className="mr-2 h-4 w-4" />
                  Edit
                </Button>
              )}
            </div>
          </DialogFooter>
        </DialogContent>
      </Dialog>

      {/* Delete confirmation, for one job or the checked rows */}
      <Dialog open={jobsToDelete.length > 0} onOpenChange={(open) => { if (!open && !deletingJob) setJobsToDelete([]) }}>
        <DialogContent className="sm:max-w-md">
          <DialogHeader>
            <DialogTitle>{jobsToDelete.length === 1 ? "Delete job" : `Delete ${jobsToDelete.length} jobs`}</DialogTitle>
            <DialogDescription>This cannot be undone.</DialogDescription>
          </DialogHeader>
          <ul className="max-h-56 space-y-1 overflow-y-auto rounded-md border p-3 text-sm">
            {jobsToDelete.map((job) => (
              <li key={job.id}>
                <div className="flex justify-between gap-2">
                  <span className="font-medium">{job.name}</span>
                  <span className="font-mono text-xs text-muted-foreground">#{job.id}</span>
                </div>
                {job.approvedRunId && (
                  <p className="text-xs text-muted-foreground">
                    In approved plan {job.approvedRunId.slice(0, 8)}. It leaves the plan and the calendar. The other jobs stay approved.
                  </p>
                )}
              </li>
            ))}
          </ul>
          <DialogFooter className="gap-2 sm:gap-0">
            <Button variant="outline" onClick={() => setJobsToDelete([])} disabled={deletingJob}>
              Cancel
            </Button>
            <Button variant="destructive" disabled={deletingJob} onClick={() => void handleConfirmDeleteJobs()}>
              {deletingJob ? "Deleting…" : "Delete"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>
    </div>
  )
}

/**
 * Hidden until the row is hovered or a control inside is focused. Always shown on touch screens,
 * which cannot hover, and while any row is ticked.
 */
function revealClass(anySelected: boolean) {
  return `${anySelected ? "" : "opacity-0"} group-hover/row:opacity-100 group-focus-within/row:opacity-100 peer-focus-visible:opacity-100 [@media(hover:none)]:opacity-100`
}

function MenuItem({ className = "", ...props }: React.ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      type="button"
      className={`flex w-full items-center gap-2 rounded-sm px-2 py-1.5 text-left text-sm hover:bg-muted focus-visible:bg-muted focus-visible:outline-none disabled:opacity-50 ${className}`}
      {...props}
    />
  )
}

function StatusBadge({ status }: { status: string }) {
  const { label, icon: Icon, tone } = jobStatus(status)
  return (
    <span className={`inline-flex items-center gap-1 whitespace-nowrap rounded-full px-2 py-1 text-xs ${tone}`}>
      <Icon className="h-3 w-3" aria-hidden="true" />
      {label}
    </span>
  )
}

function Field({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <label className="space-y-2 text-sm">
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
