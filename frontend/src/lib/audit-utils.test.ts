import { describe, it, expect } from "vitest"
import type { AuditEvent, AuditEventDetail, OptimizeResponse } from "./api"
import {
  EMPTY_AUDIT_FILTERS,
  exportUnavailableReason,
  latestDecisionByRun,
  auditEventsToCsv,
  buildAuditQuery,
  decisionMetadata,
  formatAuditTimestamp,
  hasActiveFilters,
  isInvertedRange,
  outcomeTone,
  parseJobId,
  parseSolverConfig,
  scheduleSnapshot,
  storedScheduleNote,
  marketDayEndExclusive,
  marketDayStart,
} from "./audit-utils"

const event: AuditEvent = {
  id: 12,
  actor: "operator@gacs.internal",
  actionType: "REJECT",
  timestamp: "2026-10-28T09:30:00Z",
  runId: "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
  scheduleHash: "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
  operatorRationale: 'Price spike, "too risky"',
}

describe("utc day bounds", () => {
  it("maps a date input to the start of that CT day", () => {
    expect(marketDayStart("2026-10-28")).toBe("2026-10-28T05:00:00.000Z")
    expect(marketDayStart("2026-11-04")).toBe("2026-11-04T06:00:00.000Z")
  })

  it("maps an inclusive end day to the start of the next CT day", () => {
    expect(marketDayEndExclusive("2026-10-31")).toBe("2026-11-01T05:00:00.000Z")
    expect(marketDayEndExclusive("2026-11-01")).toBe("2026-11-02T06:00:00.000Z")
    expect(marketDayEndExclusive("2026-12-31")).toBe("2027-01-01T06:00:00.000Z")
  })

  it("ignores empty or malformed dates", () => {
    expect(marketDayStart("")).toBeUndefined()
    expect(marketDayStart("28/10/2026")).toBeUndefined()
    expect(marketDayEndExclusive("2026-13-45")).toBeUndefined()
  })

  it("detects an end day before the start day", () => {
    expect(isInvertedRange({ fromDate: "2026-10-29", toDate: "2026-10-28" })).toBe(true)
    expect(isInvertedRange({ fromDate: "2026-10-28", toDate: "2026-10-28" })).toBe(false)
    expect(isInvertedRange({ fromDate: "2026-10-28", toDate: "" })).toBe(false)
  })
})

describe("buildAuditQuery", () => {
  it("omits unset filters", () => {
    expect(buildAuditQuery(EMPTY_AUDIT_FILTERS, 0, 20)).toEqual({
      from: undefined,
      to: undefined,
      actor: undefined,
      actionType: undefined,
      runId: undefined,
      jobId: undefined,
      page: 0,
      size: 20,
    })
  })

  it("maps every filter to its query parameter", () => {
    expect(
      buildAuditQuery(
        {
          fromDate: "2026-10-28",
          toDate: "2026-10-28",
          operator: "  alice@ ",
          actionType: "APPROVE",
          runId: " 7f3c ",
          jobId: " 101 ",
        },
        2,
        50,
      ),
    ).toEqual({
      from: "2026-10-28T05:00:00.000Z",
      to: "2026-10-29T05:00:00.000Z",
      actor: "alice@",
      actionType: "APPROVE",
      runId: "7f3c",
      jobId: 101,
      page: 2,
      size: 50,
    })
  })
})

describe("hasActiveFilters", () => {
  it("is false for the empty filter set and whitespace-only text", () => {
    expect(hasActiveFilters(EMPTY_AUDIT_FILTERS)).toBe(false)
    expect(hasActiveFilters({ ...EMPTY_AUDIT_FILTERS, operator: "   " })).toBe(false)
  })

  it("is true once any filter is set", () => {
    expect(hasActiveFilters({ ...EMPTY_AUDIT_FILTERS, actionType: "REVISE" })).toBe(true)
    expect(hasActiveFilters({ ...EMPTY_AUDIT_FILTERS, jobId: "7" })).toBe(true)
    expect(hasActiveFilters({ ...EMPTY_AUDIT_FILTERS, fromDate: "2026-10-01" })).toBe(true)
  })
})

describe("formatting", () => {
  it("formats timestamps in CT", () => {
    expect(formatAuditTimestamp("2026-10-28T09:30:00Z")).toBe("28 Oct 2026, 04:30 CDT")
    expect(formatAuditTimestamp("2026-11-04T15:30:00.123456Z")).toBe("04 Nov 2026, 09:30 CST")
    expect(formatAuditTimestamp("garbage")).toBe("—")
  })

  it("assigns a tone per decision", () => {
    expect(outcomeTone("APPROVE")).toBe("positive")
    expect(outcomeTone("DISPATCH")).toBe("positive")
    expect(outcomeTone("REJECT")).toBe("negative")
    expect(outcomeTone("REVISE")).toBe("neutral")
    expect(outcomeTone("OVERRIDE")).toBe("neutral")
  })
})

describe("detail view JSON", () => {
  it("builds decision metadata from the event", () => {
    expect(decisionMetadata(event)).toEqual({
      auditEventId: 12,
      decision: "REJECT",
      operator: "operator@gacs.internal",
      timestamp: "2026-10-28T09:30:00Z",
      targetRunId: event.runId,
      operatorRationale: 'Price spike, "too risky"',
    })
  })

  it("includes the run snapshot with parsed solver config", () => {
    const snapshot = scheduleSnapshot({
      event,
      scheduleAvailability: "OWNER_ONLY",
      scheduleHashMatches: true,
      schedule: null,
      run: {
        id: event.runId,
        createdAt: "2026-10-28T09:00:00Z",
        solverName: "OR-Tools CP-SAT",
        solverVersion: "9.10",
        solverConfig: '{"timeLimitSeconds":10}',
        status: "SCHEDULED",
        provenOptimal: true,
      },
    })
    expect(snapshot).toEqual({
      runId: event.runId,
      scheduleHash: event.scheduleHash,
      storedScheduleMatchesHash: true,
      optimizerRun: {
        createdAt: "2026-10-28T09:00:00Z",
        solver: "OR-Tools CP-SAT 9.10",
        status: "SCHEDULED",
        provenOptimal: true,
        solverConfig: { timeLimitSeconds: 10 },
      },
    })
  })

  it("keeps the schedule hash when no run is stored", () => {
    expect(
      scheduleSnapshot({ event, run: null, scheduleAvailability: "NOT_STORED", scheduleHashMatches: null, schedule: null }),
    ).toEqual({
      runId: event.runId,
      scheduleHash: event.scheduleHash,
      storedScheduleMatchesHash: null,
      optimizerRun: null,
    })
  })

  it("falls back to raw text for invalid solver config", () => {
    expect(parseSolverConfig("not json")).toBe("not json")
  })
})

describe("auditEventsToCsv", () => {
  it("quotes every cell and escapes embedded quotes", () => {
    const [header, row] = auditEventsToCsv([event]).split("\n")
    expect(header).toBe('"ID","Timestamp (CT)","Operator","Decision","Target Run ID","Schedule Hash","Rationale"')
    expect(row).toContain('"Price spike, ""too risky"""')
    expect(row.startsWith('"12","28 Oct 2026, 04:30 CDT","operator@gacs.internal","REJECT"')).toBe(true)
  })
})

describe("schedule export availability", () => {
  const decision = (id: number, runId: string, actionType: AuditEvent["actionType"]): AuditEvent => ({
    ...event,
    id,
    runId,
    actionType,
  })
  const events = [
    decision(1, "run-a", "APPROVE"),
    decision(5, "run-a", "REJECT"),
    decision(2, "run-b", "APPROVE"),
    decision(3, "run-c", "APPROVE"),
    decision(4, "run-c", "APPROVE"),
    decision(6, "run-d", "DISPATCH"),
  ]
  const latest = latestDecisionByRun(events)

  it("keeps the highest-ID decision per run and ignores non-decision events", () => {
    expect(latest.get("run-a")?.id).toBe(5)
    expect(latest.get("run-b")?.id).toBe(2)
    expect(latest.get("run-c")?.id).toBe(4)
    expect(latest.has("run-d")).toBe(false)
  })

  it("offers downloads only on the latest decision of a run when it is an approval", () => {
    expect(exportUnavailableReason(events[2], latest)).toBeUndefined()
    expect(exportUnavailableReason(events[4], latest)).toBeUndefined()
    expect(exportUnavailableReason(events[0], latest)).toBe("Replaced by a later REJECT decision on this run")
    expect(exportUnavailableReason(events[3], latest)).toMatch(/approved again later/)
    expect(exportUnavailableReason(events[1], latest)).toBe("Only approved schedules can be exported")
  })
})

describe("job ID filter", () => {
  it("accepts positive whole numbers, trimmed", () => {
    expect(parseJobId("101")).toBe(101)
    expect(parseJobId("  7 ")).toBe(7)
  })

  it("ignores blank, zero, negative, decimal and non-numeric input", () => {
    for (const value of ["", "   ", "0", "-3", "1.5", "1e3", "abc", "12abc", "99999999999999999999"]) {
      expect(parseJobId(value)).toBeUndefined()
    }
  })

  it("leaves an invalid job ID out of the query", () => {
    expect(buildAuditQuery({ ...EMPTY_AUDIT_FILTERS, jobId: "abc" }, 0, 20).jobId).toBeUndefined()
  })
})

describe("storedScheduleNote", () => {
  const base: AuditEventDetail = { event, run: null, scheduleAvailability: "VISIBLE", scheduleHashMatches: true, schedule: null }
  const schedule = { runId: event.runId, items: [] } as unknown as OptimizeResponse

  it("shows nothing when the owner's schedule is present", () => {
    expect(storedScheduleNote({ ...base, schedule })).toBeNull()
  })

  it("explains each reason the schedule is not shown", () => {
    expect(storedScheduleNote(base)).toBe("The stored schedule could not be read.")
    expect(storedScheduleNote({ ...base, scheduleAvailability: "OWNER_ONLY" })).toMatch(/Only the operator who owns this run/)
    expect(storedScheduleNote({ ...base, scheduleAvailability: "NOT_STORED", scheduleHashMatches: null })).toMatch(
      /No schedule is stored/,
    )
  })
})
