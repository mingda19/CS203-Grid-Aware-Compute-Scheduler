import { describe, expect, it } from "vitest"
import { applyDisagreementAnswers, constraintReviewBlocker, type DisagreementAnswers } from "./constraint-review"
import type { ConstraintDisagreement, ConstraintDraft, ConstraintParseResponse } from "./api"

const draft: ConstraintDraft = {
  originalStartTime: "2026-11-01T14:00:00", deadline: "2026-11-02T10:00:00",
  flexibilityWindowHours: 0, priority: "NORMAL", isExempt: false,
}

const keepOrDrop: ConstraintDisagreement = {
  field: "deadline", parsedValue: "2026-11-02T10:00", probability: null,
  question: "The text was read as setting a deadline to 2026-11-02T10:00, but it does not look like it states one. Keep it?",
  options: [{ id: "KEEP", label: "Keep a deadline as 2026-11-02T10:00" }, { id: "DROP", label: "Remove it" }],
}

const enterOrIgnore: ConstraintDisagreement = {
  field: "flexibilityWindowHours", parsedValue: null, probability: 0.91,
  question: "The text looks like it sets a delay allowance, but no value could be read from it.",
  options: [{ id: "ENTER_VALUE", label: "I will set a delay allowance in the form" }, { id: "IGNORE", label: "The text does not set a delay allowance" }],
}

function response(disagreements: ConstraintDisagreement[]): ConstraintParseResponse {
  return {
    status: "NEEDS_CLARIFICATION", fields: { ...draft },
    evidence: [{ field: "deadline", value: draft.deadline!, phrase: "by 10am", start: 0, end: 7 }],
    ambiguities: [], conflicts: [], unsupported: [], validationError: null,
    parsedAt: "2026-11-01T10:00:00", timeZone: "America/Chicago",
    suggestions: [], disagreements,
  }
}

describe("disagreement answers block a confirm until given", () => {
  it("blocks while a question is unanswered", () => {
    expect(constraintReviewBlocker(response([keepOrDrop]), draft, draft, {})).toContain("Answer the question")
  })

  it("counts the questions so the operator knows how many are left", () => {
    expect(constraintReviewBlocker(response([keepOrDrop, enterOrIgnore]), draft, draft, {}))
      .toContain("Answer the 2 questions")
  })

  it("clears once every question is answered", () => {
    const answers: DisagreementAnswers = { deadline: "KEEP", flexibilityWindowHours: "IGNORE" }
    expect(constraintReviewBlocker(response([keepOrDrop, enterOrIgnore]), draft, draft, answers)).toBeNull()
  })

  it("is not satisfied by answering only one of two", () => {
    expect(constraintReviewBlocker(response([keepOrDrop, enterOrIgnore]), draft, draft, { deadline: "KEEP" }))
      .toContain("Answer the question")
  })

  it("takes precedence over the generic no-constraints message, which would mislead", () => {
    // Something WAS found - by the classifier and not the parser. "No supported constraints were
    // found" would send the operator off to reword text that was understood.
    const noEvidence = { ...response([enterOrIgnore]), evidence: [] }
    expect(constraintReviewBlocker(noEvidence, draft, draft, {})).toContain("Answer the question")
  })

  it("still lets an invalid result win, since that has to be fixed first", () => {
    const invalid = { ...response([keepOrDrop]), status: "INVALID" as const, validationError: "Deadline must be after start" }
    expect(constraintReviewBlocker(invalid, draft, draft, {})).toBe("Deadline must be after start")
  })

  it("does not block when the backend sends no disagreements field at all", () => {
    // An older backend, or one with no classifier configured.
    const legacy = { ...response([]) } as ConstraintParseResponse
    delete (legacy as Partial<ConstraintParseResponse>).disagreements
    expect(constraintReviewBlocker(legacy, draft, draft, {})).toBeNull()
  })
})

describe("answers change what gets applied", () => {
  it("keeps the parsed value on KEEP", () => {
    expect(applyDisagreementAnswers(draft, [keepOrDrop], { deadline: "KEEP" }).deadline)
      .toBe("2026-11-02T10:00:00")
  })

  it("removes it on DROP", () => {
    // The whole point: without this the selection would be cosmetic and the value would be saved
    // regardless of what the operator chose.
    expect(applyDisagreementAnswers(draft, [keepOrDrop], { deadline: "DROP" }).deadline).toBeNull()
  })

  it("clears the field on ENTER_VALUE, so a guess cannot stand in for what they meant to type", () => {
    const withFlex = { ...draft, flexibilityWindowHours: 4 }
    expect(applyDisagreementAnswers(withFlex, [enterOrIgnore], { flexibilityWindowHours: "ENTER_VALUE" })
      .flexibilityWindowHours).toBeNull()
  })

  it("clears the field on IGNORE", () => {
    const withFlex = { ...draft, flexibilityWindowHours: 4 }
    expect(applyDisagreementAnswers(withFlex, [enterOrIgnore], { flexibilityWindowHours: "IGNORE" })
      .flexibilityWindowHours).toBeNull()
  })

  it("leaves every other field alone", () => {
    const applied = applyDisagreementAnswers(draft, [keepOrDrop], { deadline: "DROP" })
    expect(applied.originalStartTime).toBe(draft.originalStartTime)
    expect(applied.priority).toBe("NORMAL")
    expect(applied.isExempt).toBe(false)
  })

  it("does not mutate the fields it was given", () => {
    const original = { ...draft }
    applyDisagreementAnswers(draft, [keepOrDrop], { deadline: "DROP" })
    expect(draft).toEqual(original)
  })

  it("applies nothing for a question left unanswered", () => {
    expect(applyDisagreementAnswers(draft, [keepOrDrop], {}).deadline).toBe(draft.deadline)
  })
})
