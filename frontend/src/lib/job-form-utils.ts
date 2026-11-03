import { SGT_TIMEZONE } from "./date-utils"
import type { ComputeJob, OptimizeJobInput } from "./api"

export function toOptimizeJobInput(job: ComputeJob): OptimizeJobInput {
  const originalStartTime = job.originalStartTime.includes("T")
    ? `${job.originalStartTime.split("T")[0]}T${job.originalStartTime.split("T")[1].slice(0, 2)}:00:00`
    : job.originalStartTime
  return {
    id: job.id, name: job.name, cpuHours: job.cpuHours, gpuHours: job.gpuHours,
    originalStartTime, deadline: job.deadline, flexibilityWindowHours: job.flexibilityWindowHours,
    isExempt: job.isExempt, priority: job.priority ?? "NORMAL",
  }
}

// The API uses timezone-free LocalDateTime strings that represent UTC. Convert
// them explicitly so datetime-local inputs always show Singapore wall time.
export const toInputDate = (value?: string) => {
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

export const toApiUtcDate = (value: string, onTheHour = false) => {
  const [date, time] = value.split("T")
  const [year, month, day] = date.split("-").map(Number)
  const [hour, minute] = time.split(":").map(Number)
  const finalMinute = onTheHour ? 0 : minute
  const utc = new Date(Date.UTC(year, month - 1, day, hour - 8, finalMinute, 0))
  return utc.toISOString().slice(0, 19)
}
