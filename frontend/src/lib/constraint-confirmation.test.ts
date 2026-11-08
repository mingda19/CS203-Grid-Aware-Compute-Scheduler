import { describe, it, expect } from "vitest"
import {
  applied, needsOperatorInput, outstanding, saveBlocker,
  type Decision, type ProposedConstraint,
} from "./constraint-confirmation"

const sure = (key: string): ProposedConstraint => ({
  key, type: "DEADLINE", probability: 0.95, phrase: "by 6pm", needsConfirmation: false,
})
const unsure = (key: string): ProposedConstraint => ({
  key, type: "FLEXIBILITY_WINDOW", probability: 0.55, phrase: "can wait a bit", needsConfirmation: true,
})
const ambiguous = (key: string): ProposedConstraint => ({
  key, type: "PRECEDENCE", probability: 0.95, phrase: "before gpt-agent-5", needsConfirmation: false,
  candidates: [{ id: 7, name: "gpt-agent-5" }, { id: 9, name: "gpt-agent-5" }],
})

describe("which proposals need the operator", () => {
  it("leaves a confident, unambiguous proposal alone", () => {
    expect(needsOperatorInput(sure("a"))).toBe(false)
    expect(outstanding([sure("a")])).toEqual([])
  })

  it("asks about anything in the review band", () => {
    expect(needsOperatorInput(unsure("a"))).toBe(true)
  })

  it("asks when several jobs share the name, even at high probability", () => {
    // Not a confidence problem: the classifier can be certain the words mean "gpt-agent-5" while
    // two of the operator's jobs carry that name.
    expect(needsOperatorInput(ambiguous("a"))).toBe(true)
  })

  it("does not ask when exactly one job matched", () => {
    expect(needsOperatorInput({ ...ambiguous("a"), candidates: [{ id: 7, name: "nightly-etl" }] })).toBe(false)
  })
})

describe("blocking the save until everything is settled", () => {
  it("does not block when nothing needed asking", () => {
    expect(saveBlocker([sure("a")], {})).toBeNull()
  })

  it("blocks while a question is unanswered, and says how many", () => {
    expect(saveBlocker([unsure("a")], {})).toContain("the remaining suggested constraint")
    expect(saveBlocker([unsure("a"), unsure("b")], {})).toContain("the 2 remaining")
  })

  it("stops blocking once every question is answered, either way", () => {
    const decisions: Record<string, Decision> = { a: { accepted: true }, b: { accepted: false } }
    expect(saveBlocker([unsure("a"), unsure("b")], decisions)).toBeNull()
  })

  it("keeps blocking when an accepted constraint still has no job chosen", () => {
    const accepted: Record<string, Decision> = { a: { accepted: true } }
    expect(saveBlocker([ambiguous("a")], accepted)).toContain("Choose which job")

    const chosen: Record<string, Decision> = { a: { accepted: true, referencedJobId: 9 } }
    expect(saveBlocker([ambiguous("a")], chosen)).toBeNull()
  })

  it("does not demand a job for an ambiguous constraint the operator rejected", () => {
    expect(saveBlocker([ambiguous("a")], { a: { accepted: false } })).toBeNull()
  })
})

describe("what actually gets applied", () => {
  it("applies confident proposals without an answer", () => {
    expect(applied([sure("a")], {}).map((x) => x.proposal.key)).toEqual(["a"])
  })

  it("drops anything the operator rejected, and keeps what they accepted", () => {
    const decisions: Record<string, Decision> = { a: { accepted: true }, b: { accepted: false } }
    expect(applied([unsure("a"), unsure("b")], decisions).map((x) => x.proposal.key)).toEqual(["a"])
  })

  it("never applies an unanswered proposal", () => {
    // The whole point of asking before the job is saved: silence is not consent.
    expect(applied([unsure("a")], {})).toEqual([])
  })

  it("carries the job the operator picked, not the one first matched", () => {
    const decisions: Record<string, Decision> = { a: { accepted: true, referencedJobId: 9 } }
    expect(applied([ambiguous("a")], decisions)[0].referencedJobId).toBe(9)
  })
})
