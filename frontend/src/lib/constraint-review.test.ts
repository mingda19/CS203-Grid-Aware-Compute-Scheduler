import { describe, expect, it } from "vitest"
import { constraintReviewBlocker, confirmedPairConstraints } from "./constraint-review"
import { toApiUtcDate, toInputDate } from "./job-form-utils"
import type { ConstraintDraft, ConstraintParseResponse } from "./api"

const draft: ConstraintDraft = { originalStartTime: "2026-11-01T14:00:00", deadline: "2026-11-02T10:00:00", flexibilityWindowHours: 0, priority: "NORMAL", isExempt: false }
const result: ConstraintParseResponse = {
  status: "PARSED", fields: { ...draft, flexibilityWindowHours: 4 },
  evidence: [{ field: "flexibilityWindowHours", value: "4", phrase: "can wait 4 hours", start: 0, end: 16 }],
  ambiguities: [], conflicts: [], unsupported: [], validationError: null,
  parsedAt: "2026-11-01T10:00:00", timeZone: "America/Chicago",
}

describe("parse review confirmation", () => {
  it("allows precedence-only text and keeps the referenced job id for saving", () => {
    const suggestions = [{ type: "PRECEDENCE" as const, probability: .94, phrase: "finish before the batch starts", referencedJobId: 9, needsConfirmation: false }]
    expect(constraintReviewBlocker({ ...result, evidence: [], suggestions }, draft, draft)).toBeNull()
    expect(confirmedPairConstraints(suggestions)).toEqual([{ type: "PRECEDENCE", referencedJobId: 9 }])
  })
  it("requires a decision on an uncertain relationship and preserves unsupported text", () => {
    const response = { ...result, evidence: [], suggestions: [{ type: "DEPENDENCY" as const, probability: .5, phrase: "after the batch", referencedJobId: 9, needsConfirmation: true }] }
    expect(constraintReviewBlocker(response, draft, draft)).toContain("uncertain")
    expect(constraintReviewBlocker(response, draft, draft, {}, { 0: "KEEP" })).toBeNull()
    expect(constraintReviewBlocker({ ...response, unsupported: [{ phrase: "16 GPUs", start: 0, end: 7 }] }, draft, draft, {}, { 0: "KEEP" })).toContain("unsupported")
  })
  it("allows a clear result and explicit acceptance of a difference from the form", () => {
    expect(constraintReviewBlocker(result, draft, draft)).toBeNull()
    expect(constraintReviewBlocker({ ...result, status: "NEEDS_CLARIFICATION", conflicts: [{ field: "flexibilityWindowHours", phrases: ["can wait 4 hours", "form: 0"], message: "Text replaces form" }] }, draft, draft)).toBeNull()
  })
  it("blocks a response after the form changes", () => {
    expect(constraintReviewBlocker(result, draft, { ...draft, deadline: "2026-11-01T15:00:00" })).toContain("changed")
  })
  it("blocks ambiguous, invalid, unsupported and empty results", () => {
    expect(constraintReviewBlocker({ ...result, ambiguities: [{ field: "deadline", reason: "AM_OR_PM", phrase: "by 6", message: "6am or 6pm?", start: 0, end: 4 }] }, draft, draft)).toContain("Clarify")
    expect(constraintReviewBlocker({ ...result, status: "INVALID", validationError: "Deadline before start" }, draft, draft)).toBe("Deadline before start")
    expect(constraintReviewBlocker({ ...result, unsupported: [{ phrase: "16 GPUs", start: 0, end: 7 }] }, draft, draft)).toContain("unsupported")
    expect(constraintReviewBlocker({ ...result, evidence: [] }, draft, draft)).toContain("No supported")
  })
  it("blocks contradictory text even if the form has a fallback value", () => {
    expect(constraintReviewBlocker({ ...result, conflicts: [{ field: "deadline", phrases: ["by 6am", "by 8am"], message: "Two deadlines" }] }, draft, draft)).toContain("conflicting")
  })
  it("prevents silently rounding a parsed start time when the job is saved", () => {
    expect(constraintReviewBlocker({ ...result, fields: { ...draft, originalStartTime: "2026-11-01T14:30:00" } }, draft, draft)).toContain("on the hour")
  })
  it("converts UTC parser values to market form values and back across midnight", () => {
    // Central Time is behind UTC, so an early-morning UTC value is the previous
    // market day: 04:00Z on 02 Nov is 22:00 CST on 01 Nov.
    const value = "2026-11-02T04:00:00"
    expect(toInputDate(value)).toBe("2026-11-01T22:00")
    expect(toInputDate(value + "Z")).toBe("2026-11-01T22:00")
    expect(toApiUtcDate(toInputDate(value))).toBe(value)
  })
})
