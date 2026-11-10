import { Ban, CalendarCheck, CircleCheck, CircleX, Clock, Hourglass, Play, TriangleAlert, type LucideIcon } from "lucide-react"
import type { ComputeJob, JobStatus } from "@/lib/api"

/** Every status gets an icon and text, so colour is never the only signal. */
export const JOB_STATUS: Record<JobStatus, { label: string; icon: LucideIcon; tone: string }> = {
  SCHEDULING: { label: "Scheduling", icon: Hourglass, tone: "bg-muted text-muted-foreground" },
  AWAITING_APPROVAL: { label: "Awaiting approval", icon: Clock, tone: "bg-amber-500/15 text-amber-700 dark:text-amber-300" },
  SCHEDULED: { label: "Scheduled", icon: CalendarCheck, tone: "bg-teal-500/15 text-teal-700 dark:text-teal-300" },
  RUNNING: { label: "Running", icon: Play, tone: "bg-blue-500/15 text-blue-700 dark:text-blue-300" },
  COMPLETED: { label: "Completed", icon: CircleCheck, tone: "bg-emerald-500/15 text-emerald-700 dark:text-emerald-300" },
  REJECTED: { label: "Rejected", icon: CircleX, tone: "bg-rose-500/15 text-rose-700 dark:text-rose-300" },
  NEEDS_ATTENTION: { label: "Needs attention", icon: TriangleAlert, tone: "bg-rose-500/15 text-rose-700 dark:text-rose-300" },
  CANCELLED: { label: "Cancelled", icon: Ban, tone: "bg-muted text-muted-foreground" },
}

export const jobStatus = (status: string) => JOB_STATUS[status as JobStatus] ?? { label: status, icon: Clock, tone: "bg-muted text-muted-foreground" }

/** Recompute is the manual fallback, so it shows only when a job is waiting on the system or was turned away. */
export const canRecompute = (jobs: Pick<ComputeJob, "status">[]) =>
  jobs.some((job) => job.status === "SCHEDULING" || job.status === "NEEDS_ATTENTION" || job.status === "REJECTED")

/** The page reloads on a timer while the system may still be planning one of these. */
export const isPlanning = (jobs: Pick<ComputeJob, "status">[]) => jobs.some((job) => job.status === "SCHEDULING")
