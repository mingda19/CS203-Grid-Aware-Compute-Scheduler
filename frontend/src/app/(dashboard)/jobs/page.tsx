"use client"

import * as React from "react"
import Link from "next/link"
import { useRouter, useSearchParams } from "next/navigation"
import { BriefcaseBusiness, Pencil, Plus, Trash2, Sparkles, TrendingUp, Clock, Server, ArrowRight } from "lucide-react"
import {
  jobsApi,
  priceApi,
  optimizerApi,
  facilityApi,
  type JobExplanation,
  type ComputeJob,
  type ComputeJobPayload,
  type JobPriority,
  type WorkloadCategory,
  WORKLOAD_CATEGORY_LABELS,
  PRIORITY_LABELS,
  type OptimizeResponse,
  type ScheduleItem,
  type FacilityConfig,
} from "@/lib/api"
import { currentMarketHourInput, formatMarketDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
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
import { BLOCKED_NUMBER_KEYS, JOB_TEMPLATES, effectiveFlexHours, forecastEndMs, maxJobHours, usesEstimatedPrices, type ScheduleMode, toInputDate, toApiUtcDate, toOptimizeJobInput } from "@/lib/job-form-utils"
import { elapsedJobLabel, elapsedJobsConfirmation, elapsedStartJobs } from "@/lib/elapsed-jobs"
import { RecommendationExplanation } from "@/components/jobs/RecommendationExplanation"
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

const displayDate = (value?: string) => (value ? formatMarketDateTime(value) : "—")

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
    return `${diffHours % 1 === 0 ? diffHours : diffHours.toFixed(1)} hours`
  } catch {
    return ""
  }
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
  const [parseReviewPending, setParseReviewPending] = React.useState(false)
  // End of the stored forecast (ms); null until known. Drives the "Estimated prices" flag and warning.
  const [forecastEnd, setForecastEnd] = React.useState<number | null>(null)
  const [template, setTemplate] = React.useState("") // label of the chosen template; "" = none
  const [estimatedToConfirm, setEstimatedToConfirm] = React.useState<ComputeJobPayload | null>(null)
  const [form, setForm] = React.useState<FormState>(blankForm)

  // Optimizer state
  const [optimizing, setOptimizing] = React.useState(false)
  const [optimizationPlan, setOptimizationPlan] = React.useState<OptimizeResponse | null>(null)
  const [optimizeOpen, setOptimizeOpen] = React.useState(false)
  // The jobs the open delete confirmation is about: one from the detail dialog, or the checked rows.
  const [jobsToDelete, setJobsToDelete] = React.useState<ComputeJob[]>([])
  const [selectedIds, setSelectedIds] = React.useState<Set<number>>(new Set())
  // The jobs whose start time has already passed, held while the operator decides whether to let
  // the optimiser reschedule them. Null means no question is pending - the common path, where no
  // job has elapsed and optimising must not cost an extra click.
  const [elapsedToConfirm, setElapsedToConfirm] = React.useState<ComputeJob[] | null>(null)
  const [deletingJob, setDeletingJob] = React.useState(false)

  const [jobExplanations, setJobExplanations] = React.useState<JobExplanation[]>([])
  const [explanationLoading, setExplanationLoading] = React.useState(false)
  const [explanationError, setExplanationError] = React.useState("")
  const explanationRequest = React.useRef(0)

  const loadExplanations = async (runId: string) => {
    const requestId = ++explanationRequest.current
    setExplanationLoading(true)
    setExplanationError("")
    setJobExplanations([])
    try {
      const response = await optimizerApi.explanation(runId)
      if (!response.success || !response.data) throw new Error(response.message || "Could not load explanations")
      if (requestId === explanationRequest.current) setJobExplanations(response.data.items ?? [])
    } catch {
      if (requestId === explanationRequest.current) setExplanationError("Could not load recommendation explanations.")
    } finally {
      if (requestId === explanationRequest.current) setExplanationLoading(false)
    }
  }

  // The shared facility, read only here. An admin changes it on the Admin page.
  const [facility, setFacility] = React.useState<FacilityConfig>({
    name: "Primary Compute Cluster",
    maxCpuHours: 256.0,
    maxGpuHours: 64.0,
    location: "LZ_NORTH",
  })
  const [facilityLoading, setFacilityLoading] = React.useState(true)
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

    facilityApi.get().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load facility settings")
      setFacility(response.data)
    }).catch(error => {
      if (active) setFacilityError(error instanceof Error ? error.message : "Could not load facility settings")
    }).finally(() => { if (active) setFacilityLoading(false) })

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
    if (form.originalStartTime < earliest) {
      setError(`Start time has passed. Choose ${earliest.replace("T", " ")} ${MARKET_ZONE_LABEL} or later.`)
      return
    }
    if (form.deadline <= earliest) {
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

  const selectedJobs = jobs.filter((job) => selectedIds.has(job.id))
  const allSelected = jobs.length > 0 && selectedJobs.length === jobs.length
  const toggleJob = (id: number, checked: boolean) =>
    setSelectedIds((current) => {
      const next = new Set(current)
      if (checked) next.add(id)
      else next.delete(id)
      return next
    })

  /** The jobs this run would submit: everything not already cancelled, completed or running. */
  const schedulableJobs = () => jobs.filter(job => !["CANCELLED", "COMPLETED", "RUNNING"].includes(job.status))

  /**
   * Schedule optimisation, with one question in front of it.
   *
   * A job whose start time has passed has a flexibility window anchored to a time that no longer
   * exists, so the optimiser has nowhere to put it and reports it NO_FEASIBLE_SLOT even when its
   * deadline is hours away. It can instead be rescheduled into the hours between now and that
   * deadline - but that places it outside the window the operator specified, so it is their call,
   * not an inference from the data. Hence the confirmation, which names the jobs.
   *
   * If nothing has elapsed there is no dialog: the common path keeps exactly one click.
   */
  const runOptimization = async () => {
    if (jobs.length === 0) {
      setError("Add at least one compute job before running schedule optimization.")
      return
    }
    const pending = schedulableJobs()
    if (!pending.length) {
      setError("No pending jobs are available for optimization.")
      return
    }
    const elapsed = elapsedStartJobs(pending)
    if (elapsed.length) {
      setError("")
      setElapsedToConfirm(elapsed)
      return
    }
    await optimize(false)
  }

  const optimize = async (rescheduleElapsedJobs: boolean) => {
    setError("")
    setOptimizing(true)

    // Build optimize payload ensuring originalStartTime is truncated to the hour
    const pending = schedulableJobs()
    if (!pending.length) {
      setError("No pending jobs are available for optimization.")
      setOptimizing(false)
      return
    }
    const optimizeJobs = pending.map(toOptimizeJobInput)

    try {
      const response = await optimizerApi.optimize({
        jobs: optimizeJobs,
        forecastLocation: facility.location || "LZ_NORTH",
        horizonHours: 48,
        clusterCapacity: {
          maxConcurrentCpuHours: Number(facility.maxCpuHours),
          maxConcurrentGpuHours: Number(facility.maxGpuHours),
        },
        // Only ever true because the operator just confirmed it for these jobs.
        ...(rescheduleElapsedJobs ? { rescheduleElapsedJobs: true } : {}),
      })
      if (!response.success || !response.data) {
        throw new Error(response.message || "Failed to generate schedule")
      }
      setOptimizationPlan(response.data)
      setOptimizeOpen(true)
      void loadExplanations(response.data.runId)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Optimization failed")
    } finally {
      setOptimizing(false)
    }
  }

  return (
    <div className="space-y-6" id="jobs-root">
      <React.Suspense fallback={null}><OpenCreateOnParam onOpen={openCreate} /></React.Suspense>
      <h1 className="sr-only">Compute Jobs</h1>
      <div className="flex flex-wrap items-center justify-between gap-x-6 gap-y-3">
        {/* The shared facility, read only. An admin changes it on the Admin page. */}
        <div className="flex flex-wrap items-center gap-x-6 gap-y-2 text-xs">
          <div className="flex items-center gap-2">
            <Server className="h-4 w-4 text-teal-500" />
            <div>
              <span className="block text-[11px] text-muted-foreground">Facility</span>
              <span className="font-semibold text-foreground">{facility.name}</span>
            </div>
          </div>
          <div>
            <span className="block text-[11px] text-muted-foreground">Max CPU per hour</span>
            <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400">{facility.maxCpuHours} CPU-h</span>
          </div>
          <div>
            <span className="block text-[11px] text-muted-foreground">Max GPU per hour</span>
            <span className="font-mono font-semibold text-teal-600 dark:text-teal-400">{facility.maxGpuHours} GPU-h</span>
          </div>
          <div>
            <span className="block text-[11px] text-muted-foreground">ERCOT zone</span>
            <span className="font-mono font-semibold text-foreground">{facility.location}</span>
          </div>
        </div>
        <div className="flex flex-wrap items-center gap-2">
          {selectedJobs.length > 0 && (
            <>
              <span role="status" className="text-sm text-muted-foreground">{selectedJobs.length} selected</span>
              <Button variant="destructive" size="sm" onClick={() => setJobsToDelete(selectedJobs)}>
                <Trash2 className="mr-2 h-4 w-4" />
                Delete
              </Button>
            </>
          )}
          {/* Temporary: Phase 4 replaces this with autorun. */}
          <Button
            variant="outline"
            onClick={runOptimization}
            disabled={optimizing || loading || facilityLoading || !!facilityError || jobs.length === 0}
          >
            <Sparkles className="mr-2 h-4 w-4" />
            {optimizing ? "Solving Schedule…" : "Run Optimizer"}
          </Button>
          <Button onClick={openCreate}>
            <Plus className="mr-2 h-4 w-4" />
            New job
          </Button>
        </div>
      </div>

      {facilityError && <p role="alert" className="text-sm text-destructive">{facilityError}. Reload the page before running optimization.</p>}

      {error && (
        <div
          role="alert"
          className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive"
        >
          {error}
        </div>
      )}

      <Card>
        <CardContent>
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
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b text-xs text-muted-foreground">
                  <th className="px-2 py-3 sm:px-3">
                    <Checkbox
                      checked={allSelected}
                      onCheckedChange={(checked) => setSelectedIds(checked ? new Set(jobs.map((job) => job.id)) : new Set())}
                      aria-label="Select all jobs"
                    />
                  </th>
                  <th className="hidden px-2 py-3 sm:px-3 lg:table-cell">ID</th>
                  <th className="px-2 py-3 sm:px-3">Name</th>
                  <th className="hidden px-2 py-3 sm:px-3 lg:table-cell">CPU hours</th>
                  <th className="hidden px-2 py-3 sm:px-3 lg:table-cell">GPU hours</th>
                  <th className="px-2 py-3 sm:px-3">Start ({MARKET_ZONE_LABEL})</th>
                  <th className="px-2 py-3 sm:px-3">Deadline ({MARKET_ZONE_LABEL})</th>
                  <th className="hidden px-2 py-3 sm:px-3 lg:table-cell">Priority</th>
                  <th className="hidden px-2 py-3 sm:px-3 lg:table-cell">Job type</th>
                  <th className="px-2 py-3 sm:px-3">Status</th>
                </tr>
              </thead>
              <tbody>
                {jobs.map((job) => (
                  <tr
                    key={job.id}
                    className="cursor-pointer border-b last:border-0 hover:bg-muted/30"
                    onClick={() => void openView(job)}
                  >
                    <td className="px-2 py-3 sm:px-3" onClick={(e) => e.stopPropagation()}>
                      <Checkbox
                        checked={selectedIds.has(job.id)}
                        onCheckedChange={(checked) => toggleJob(job.id, checked)}
                        aria-label={`Select ${job.name}`}
                      />
                    </td>
                    <td className="hidden px-2 py-3 font-mono text-xs text-muted-foreground sm:px-3 lg:table-cell">#{job.id}</td>
                    <td className="px-2 py-3 font-medium text-foreground sm:px-3">
                      <button
                        type="button"
                        className="text-left [overflow-wrap:anywhere] hover:underline focus-visible:underline focus-visible:outline-none"
                        onClick={(e) => { e.stopPropagation(); void openView(job) }}
                      >
                        {job.name}
                      </button>
                      {usesEstimatedPrices(job.deadline, forecastEnd) && <div className="mt-1"><EstimatedPriceBadge /></div>}
                    </td>
                    <td className="hidden px-2 py-3 sm:px-3 lg:table-cell">{job.cpuHours}</td>
                    <td className="hidden px-2 py-3 sm:px-3 lg:table-cell">{job.gpuHours}</td>
                    <td className="px-2 py-3 sm:px-3">{displayDate(job.originalStartTime)}</td>
                    <td className="px-2 py-3 sm:px-3">{displayDate(job.deadline)}</td>
                    <td className="hidden px-2 py-3 sm:px-3 lg:table-cell">{PRIORITY_LABELS[job.priority ?? "NORMAL"]}</td>
                    <td className="hidden px-2 py-3 sm:px-3 lg:table-cell">{WORKLOAD_CATEGORY_LABELS[job.workloadCategory ?? "ML_TRAINING"]}</td>
                    <td className="px-2 py-3 sm:px-3">
                      <span className="rounded-full bg-muted px-2 py-1 text-xs">{job.status}</span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      {/*
        Elapsed-start confirmation. Shown only when at least one job's start time has already gone
        by; optimising when nothing has elapsed does not pass through here at all. Choosing
        "Optimize anyway" is what sends rescheduleElapsedJobs - the operator's explicit consent to
        placement outside the window they originally set.
      */}
      <Dialog open={!!elapsedToConfirm} onOpenChange={open => { if (!open && !optimizing) setElapsedToConfirm(null) }}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle>
              {elapsedToConfirm?.length === 1
                ? "One job's start time has passed"
                : `${elapsedToConfirm?.length ?? 0} jobs' start times have passed`}
            </DialogTitle>
            <DialogDescription>
              {elapsedToConfirm ? elapsedJobsConfirmation(elapsedToConfirm.length) : ""}
            </DialogDescription>
          </DialogHeader>
          <div className="max-h-56 overflow-y-auto rounded-md border p-3">
            <ul className="space-y-1 text-sm">
              {elapsedToConfirm?.map(job => (
                <li key={job.id} className="flex flex-wrap items-baseline justify-between gap-2">
                  {/* Name and id, not the id alone: an operator cannot tell which job "#118" is. */}
                  <span className="font-medium">{elapsedJobLabel(job)}</span>
                  <span className="text-xs text-muted-foreground">
                    was due {formatMarketDateTime(job.originalStartTime)} {MARKET_ZONE_LABEL}, deadline{" "}
                    {formatMarketDateTime(job.deadline)} {MARKET_ZONE_LABEL}
                  </span>
                </li>
              ))}
            </ul>
          </div>
          <DialogFooter>
            <Button type="button" variant="outline" disabled={optimizing}
              onClick={() => setElapsedToConfirm(null)}>Cancel</Button>
            {/*
              Optimising without the flag stays available: it is today's behaviour, and an operator
              who would rather fix the start times themselves should not have to accept a widened
              window to see the rest of the plan.
            */}
            <Button type="button" variant="outline" disabled={optimizing}
              onClick={() => { setElapsedToConfirm(null); void optimize(false) }}>
              Leave them out
            </Button>
            <Button type="button" disabled={optimizing}
              onClick={() => { setElapsedToConfirm(null); void optimize(true) }}>
              {optimizing ? "Solving Schedule…" : "Reschedule and optimize"}
            </Button>
          </DialogFooter>
        </DialogContent>
      </Dialog>

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
                    Facility: <strong>{facility.name}</strong> ({facility.maxCpuHours} CPU-h, {facility.maxGpuHours} GPU-h / slot)
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
                        <th className="py-2.5 px-3">Scheduled Start ({MARKET_ZONE_LABEL})</th>
                        <th className="py-2.5 px-3">Scheduled End ({MARKET_ZONE_LABEL})</th>
                        <th className="py-2.5 px-3 text-right">Cost</th>
                        <th className="py-2.5 px-3 text-right">Savings</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-border/60">
                      {optimizationPlan.items.map((item: ScheduleItem) => (
                        <React.Fragment key={item.jobId}>
                        <tr className="hover:bg-muted/20">
                          <td className="py-2.5 px-3 font-medium text-foreground">{item.jobName} <span className="font-mono text-[10px] font-normal text-muted-foreground">#{item.jobId}</span></td>
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
                        <tr><td colSpan={6} className="px-3 pb-3 pt-1">
                          <RecommendationExplanation
                            item={item}
                            explanation={jobExplanations.find((entry) => entry.jobId === item.jobId)}
                            loading={explanationLoading}
                            error={explanationError}
                            onRetry={() => void loadExplanations(optimizationPlan.runId)}
                          />
                        </td></tr>
                        </React.Fragment>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            </div>
          )}

          <DialogFooter className="flex flex-col sm:flex-row gap-2 justify-between">
            {optimizationPlan && (
              <Button type="button" asChild className="gap-1.5">
                <Link href={`/approvals?runId=${encodeURIComponent(optimizationPlan.runId)}`}>
                  Review and approve <ArrowRight className="h-3.5 w-3.5" />
                </Link>
              </Button>
            )}
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
                {viewing.description && <Detail label="Description" value={viewing.description} />}
                {usesEstimatedPrices(viewing.deadline, forecastEnd) && <EstimatedPriceBadge />}
                <Detail label="Status" value={viewing.status} />
                <Detail label="CPU hours" value={String(viewing.cpuHours)} />
                <Detail label="GPU hours" value={String(viewing.gpuHours)} />
                <Detail label="Start" value={displayDate(viewing.originalStartTime)} />
                <Detail label="Deadline" value={displayDate(viewing.deadline)} />
                <Detail label="Priority" value={PRIORITY_LABELS[viewing.priority ?? "NORMAL"]} />
                <Detail label="Job type" value={WORKLOAD_CATEGORY_LABELS[viewing.workloadCategory ?? "ML_TRAINING"]} />
                <Detail label="Scheduling" value={viewing.isExempt ? "Locked" : "Flexible"} />
              </dl>
              <label className="flex items-center gap-2 text-sm font-medium">
                <Checkbox
                  checked={viewing.isExempt}
                  disabled={updatingExemptionId === viewing.id}
                  onCheckedChange={(checked) => void setJobExempt(viewing, checked)}
                />
                Lock schedule
              </label>
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
              <Button onClick={() => { if (viewing) { setViewOpen(false); openEdit(viewing) } }}>
                <Pencil className="mr-2 h-4 w-4" />
                Edit
              </Button>
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
              <li key={job.id} className="flex justify-between gap-2">
                <span className="font-medium">{job.name}</span>
                <span className="font-mono text-xs text-muted-foreground">#{job.id}</span>
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
