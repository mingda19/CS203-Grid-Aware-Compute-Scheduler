import { Ban, CalendarCheck, CircleCheck, CircleX, Clock, Gauge, Hourglass, Play, TriangleAlert, Undo2 } from "lucide-react"
import type { ComputeJob, JobStatus, PlanReviewState, WorkloadSchedule } from "@/lib/api"
import { reviewLabels } from "@/lib/schedule-review-utils"
import type { StatusInfo } from "@/components/ui/status-badge"

/**
 * What a status looks like, for the one StatusBadge on every page. Every status has an icon and
 * text, so colour is never the only signal. Tones: neutral = quiet or finished, primary = on track,
 * warning = waiting for a person, destructive = went wrong.
 */
export const JOB_STATUS: Record<JobStatus, StatusInfo> = {
  SCHEDULING: { label: "Scheduling", icon: Hourglass, tone: "neutral" },
  AWAITING_APPROVAL: { label: "Awaiting approval", icon: Clock, tone: "warning" },
  SCHEDULED: { label: "Scheduled", icon: CalendarCheck, tone: "primary" },
  RUNNING: { label: "Running", icon: Play, tone: "primary" },
  COMPLETED: { label: "Completed", icon: CircleCheck, tone: "neutral" },
  REJECTED: { label: "Rejected", icon: CircleX, tone: "destructive" },
  NEEDS_ATTENTION: { label: "Needs attention", icon: TriangleAlert, tone: "destructive" },
  CANCELLED: { label: "Cancelled", icon: Ban, tone: "neutral" },
}

export const jobStatus = (status: string): StatusInfo => JOB_STATUS[status as JobStatus] ?? { label: status, icon: Clock, tone: "neutral" }

/** A plan's review state. Labels come from reviewLabels so the words stay in one place. */
const PLAN_STATUS: Record<PlanReviewState, Pick<StatusInfo, "icon" | "tone">> = {
  PENDING: { icon: Clock, tone: "warning" },
  APPROVED: { icon: CircleCheck, tone: "primary" },
  REJECTED: { icon: CircleX, tone: "destructive" },
  REVISION_REQUESTED: { icon: Undo2, tone: "warning" },
  STALE: { icon: TriangleAlert, tone: "destructive" },
  INVALIDATED: { icon: TriangleAlert, tone: "destructive" },
}

export const planStatus = (state: PlanReviewState): StatusInfo => ({ label: reviewLabels[state], ...PLAN_STATUS[state] })

/** A calendar entry's status. The label is the status itself. */
const ENTRY_STATUS: Record<WorkloadSchedule["status"], Pick<StatusInfo, "icon" | "tone">> = {
  Scheduled: { icon: CalendarCheck, tone: "neutral" },
  Running: { icon: Play, tone: "primary" },
  Throttled: { icon: Gauge, tone: "primary" },
  Completed: { icon: CircleCheck, tone: "neutral" },
}

export const entryStatus = (status: WorkloadSchedule["status"]): StatusInfo => ({ label: status, ...ENTRY_STATUS[status] })

/** Recompute is the manual fallback, so it shows only when a job is waiting on the system or was turned away. */
export const canRecompute = (jobs: Pick<ComputeJob, "status">[]) =>
  jobs.some((job) => job.status === "SCHEDULING" || job.status === "NEEDS_ATTENTION" || job.status === "REJECTED")

/** The page reloads on a timer while the system may still be planning one of these. */
export const isPlanning = (jobs: Pick<ComputeJob, "status">[]) => jobs.some((job) => job.status === "SCHEDULING")
