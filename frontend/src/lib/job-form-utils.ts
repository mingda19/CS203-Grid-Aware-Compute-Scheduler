import { MARKET_TIMEZONE, marketWallTimeToUtc, parseIsoDate } from "./date-utils"
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
// them explicitly so datetime-local inputs always show market wall time.
export const toInputDate = (value?: string) => {
  if (!value) return ""
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: MARKET_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(parseIsoDate(value))
  const part = (type: string) => parts.find((item) => item.type === type)?.value ?? ""
  return `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}`
}

// Inverse of toInputDate: the operator typed market wall time, the API stores UTC.
// Central Time observes DST, so the offset is resolved for the instant being
// converted rather than subtracted as a constant.
export const toApiUtcDate = (value: string, onTheHour = false) => {
  const [date, time] = value.split("T")
  const [year, month, day] = date.split("-").map(Number)
  const [hour, minute] = time.split(":").map(Number)
  const finalMinute = onTheHour ? 0 : minute
  return marketWallTimeToUtc(year, month, day, hour, finalMinute, 0).toISOString().slice(0, 19)
}

// The instant (ms) of a market wall-clock string ("YYYY-MM-DDTHH:mm"), so the clock change is counted.
const marketMs = (value: string) => {
  const [date, time] = value.split("T")
  const [year, month, day] = date.split("-").map(Number)
  const [hour, minute] = time.split(":").map(Number)
  return marketWallTimeToUtc(year, month, day, hour, minute, 0).getTime()
}

// Real hours between two datetime-local strings in market time ("YYYY-MM-DDTHH:mm"). A window across
// the spring clock change is one hour shorter than the wall clock shows, and one longer in the fall.
export const windowHours = (start: string, end: string) =>
  start && end ? (marketMs(end) - marketMs(start)) / 36e5 : 0

// Facility caps are per slot, so a job spread over N hourly slots may need N times the cap.
// No usable window means no maximum yet (Infinity).
export const maxJobHours = (perSlotCap: number, start: string, end: string) => {
  const hours = Math.floor(windowHours(start, end))
  return hours >= 1 ? perSlotCap * hours : Infinity
}

// type="number" still accepts these characters (exponent and sign), so block them at the key.
export const BLOCKED_NUMBER_KEYS = ["e", "E", "+", "-"]

export type ScheduleMode = "flexible" | "locked"

// "Lock schedule" (sent as isExempt) allows no delay. "Flexible schedule" lets the optimiser start the job
// anywhere before the deadline (the backend caps the start by the deadline), unless an explicit hours value
// ("" means none) came from a saved job or a confirmed natural-language parse.
export const effectiveFlexHours = (mode: ScheduleMode, flexInput: string, start: string, deadline: string) => {
  if (mode === "locked") return 0
  return flexInput !== "" ? Number(flexInput) : Math.max(0, Math.floor(windowHours(start, deadline)))
}

// Starting points for operators who do not know their hours. Frontend only; the values are plain form fields.
export const JOB_TEMPLATES = [
  { label: "Data Processing Batch", cpuHours: "32", gpuHours: "0", workloadCategory: "HPC_BATCH", priority: "NORMAL" },
  { label: "Light Script", cpuHours: "2", gpuHours: "0", workloadCategory: "HPC_BATCH", priority: "LOW" },
  { label: "ML Training", cpuHours: "0", gpuHours: "8", workloadCategory: "ML_TRAINING", priority: "NORMAL" },
] as const

const utcMs = (v: string) => parseIsoDate(v).getTime()

// End (ms) of the last stored forecast hour. 0 when no forecast is stored.
export const forecastEndMs = (points: { intervalStartUtc: string }[]) =>
  points.reduce((end, p) => Math.max(end, utcMs(p.intervalStartUtc) + 36e5), 0)

// True when any hour a job may use lies beyond the forecast, so the backend prices it from history
// (masterplan D10). `forecastEnd` null means the forecast is unknown (loading or failed): no claim either way.
export const usesEstimatedPrices = (deadlineUtc: string, forecastEnd: number | null) =>
  forecastEnd !== null && utcMs(deadlineUtc) > forecastEnd
