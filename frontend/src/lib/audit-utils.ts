import type { AuditActionType, AuditEvent, AuditEventDetail, AuditEventQuery } from "./api"
import { addMarketDays, formatMarketDateTime, marketDateToUtcIso } from "./date-utils"

export const AUDIT_ACTION_TYPES: AuditActionType[] = ["APPROVE", "REJECT", "REVISE", "REVOKE", "OVERRIDE", "DISPATCH", "REMOVE_JOB"]

export const AUDIT_ACTION_LABELS: Record<AuditActionType, string> = {
  APPROVE: "Approved",
  REJECT: "Rejected",
  REVISE: "Revised",
  REVOKE: "Revoked",
  OVERRIDE: "Overridden",
  DISPATCH: "Dispatched",
  REMOVE_JOB: "Job removed",
}

export interface AuditFilters {
  /** Inclusive Central (CT) calendar day, `YYYY-MM-DD` as produced by `<input type="date">`. */
  fromDate: string
  /** Inclusive Central (CT) calendar day, `YYYY-MM-DD`. */
  toDate: string
  operator: string
  actionType: AuditActionType | "ALL"
  runId: string
  /** Matches decisions on runs whose stored plan includes this job. */
  jobId: string
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  fromDate: "",
  toDate: "",
  operator: "",
  actionType: "ALL",
  runId: "",
  jobId: "",
}

const DATE_INPUT = /^\d{4}-\d{2}-\d{2}$/

/** Start of the given CT day as a UTC ISO instant, or undefined for an empty or malformed date. */
export function marketDayStart(date: string): string | undefined {
  if (!DATE_INPUT.test(date) || Number.isNaN(new Date(`${date}T00:00:00Z`).getTime())) return undefined
  return marketDateToUtcIso(date)
}

/** Start of the CT day after the given one, so an inclusive end day becomes an exclusive bound. */
export function marketDayEndExclusive(date: string): string | undefined {
  return marketDayStart(date) && marketDateToUtcIso(addMarketDays(date, 1))
}

/** True when both days are set and the end day is before the start day. */
export function isInvertedRange(filters: Pick<AuditFilters, "fromDate" | "toDate">): boolean {
  const from = marketDayStart(filters.fromDate)
  const to = marketDayStart(filters.toDate)
  return !!from && !!to && to < from
}

/** A positive whole number, or undefined for anything else (blank, decimals, negatives, text). */
export function parseJobId(value: string): number | undefined {
  const trimmed = value.trim()
  if (!/^\d+$/.test(trimmed)) return undefined
  const id = Number(trimmed)
  return id > 0 && Number.isSafeInteger(id) ? id : undefined
}

/** Translates UI filter state into the query parameters `GET /api/audit-events` accepts. */
export function buildAuditQuery(filters: AuditFilters, page: number, size: number): AuditEventQuery {
  const operator = filters.operator.trim()
  const runId = filters.runId.trim()
  return {
    from: marketDayStart(filters.fromDate),
    to: marketDayEndExclusive(filters.toDate),
    actor: operator || undefined,
    actionType: filters.actionType === "ALL" ? undefined : filters.actionType,
    runId: runId || undefined,
    jobId: parseJobId(filters.jobId),
    page,
    size,
  }
}

export function hasActiveFilters(filters: AuditFilters): boolean {
  return (
    filters.fromDate !== "" ||
    filters.toDate !== "" ||
    filters.operator.trim() !== "" ||
    filters.actionType !== "ALL" ||
    filters.runId.trim() !== "" ||
    filters.jobId.trim() !== ""
  )
}

/** `2026-10-28T09:30:00Z` → `28 Oct 2026, 04:30 CDT`, like the rest of the site. */
export function formatAuditTimestamp(iso: string): string {
  return formatMarketDateTime(iso)
}

export type OutcomeTone = "positive" | "negative" | "neutral"

export function outcomeTone(action: AuditActionType): OutcomeTone {
  if (action === "APPROVE" || action === "DISPATCH") return "positive"
  if (action === "REJECT" || action === "REVOKE") return "negative"
  return "neutral"
}

/** Parses the stored solver configuration; falls back to the raw text when it is not valid JSON. */
export function parseSolverConfig(raw: string): unknown {
  try {
    return JSON.parse(raw)
  } catch {
    return raw
  }
}

/** The operator's decision as shown in the detail view's metadata JSON. */
export function decisionMetadata(event: AuditEvent): Record<string, unknown> {
  return {
    auditEventId: event.id,
    decision: event.actionType,
    operator: event.actor,
    timestamp: event.timestamp,
    targetRunId: event.runId,
    operatorRationale: event.operatorRationale,
  }
}

/** The schedule the decision was made on: its hash, whether the stored schedule still matches it, and the run. */
export function scheduleSnapshot(detail: AuditEventDetail): Record<string, unknown> {
  const { event, run } = detail
  return {
    runId: event.runId,
    scheduleHash: event.scheduleHash,
    storedScheduleMatchesHash: detail.scheduleHashMatches,
    optimizerRun: run
      ? {
          createdAt: run.createdAt,
          solver: `${run.solverName} ${run.solverVersion}`,
          status: run.status,
          provenOptimal: run.provenOptimal,
          solverConfig: parseSolverConfig(run.solverConfig),
        }
      : null,
  }
}

/** Why the stored schedule is not shown, or null when it is. */
export function storedScheduleNote(detail: AuditEventDetail): string | null {
  switch (detail.scheduleAvailability) {
    case "VISIBLE":
      return detail.schedule ? null : "The stored schedule could not be read."
    case "OWNER_ONLY":
      return "Only the operator who owns this run can see its stored schedule."
    default:
      return "No schedule is stored for this run ID; runs optimised before schedules were saved only have the hash."
  }
}

function csvCell(value: string | number): string {
  return `"${String(value).replace(/"/g, '""')}"`
}

export function auditEventsToCsv(events: AuditEvent[]): string {
  const headers = ["ID", "Timestamp (CT)", "Operator", "Decision", "Target Run ID", "Schedule Hash", "Rationale"]
  const rows = events.map((e) => [
    e.id,
    formatAuditTimestamp(e.timestamp),
    e.actor,
    e.actionType,
    e.runId,
    e.scheduleHash,
    e.operatorRationale,
  ])
  return [headers, ...rows].map((row) => row.map(csvCell).join(",")).join("\n")
}

/** Operator decisions on a schedule run, as recorded by the decision endpoints (contract section 6.5). */
export const SCHEDULE_DECISION_TYPES: AuditActionType[] = ["APPROVE", "REJECT", "REVISE"]

/** The most recent decision per run among the given events; audit IDs increase with insertion order. */
export function latestDecisionByRun(events: AuditEvent[]): Map<string, AuditEvent> {
  const latest = new Map<string, AuditEvent>()
  for (const event of events) {
    if (!SCHEDULE_DECISION_TYPES.includes(event.actionType)) continue
    const current = latest.get(event.runId)
    if (!current || event.id > current.id) latest.set(event.runId, event)
  }
  return latest
}

/**
 * Why a decision row offers no schedule download, or undefined when it does. Only the latest decision on a run
 * can be exported, and only if it is an approval; the server still checks that the approval stands.
 */
export function exportUnavailableReason(event: AuditEvent, latest: Map<string, AuditEvent>): string | undefined {
  if (event.actionType !== "APPROVE") return "Only approved schedules can be exported"
  const newest = latest.get(event.runId)
  if (!newest || newest.id === event.id) return undefined
  return newest.actionType === "APPROVE"
    ? "This run was approved again later; download from the latest approval"
    : `Replaced by a later ${newest.actionType} decision on this run`
}
