import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, it, expect } from "vitest"
import { ConstraintConfirmation } from "./ConstraintConfirmation"
import type { Decision, ProposedConstraint } from "@/lib/constraint-confirmation"

const unsure: ProposedConstraint = {
  key: "k1", type: "FLEXIBILITY_WINDOW", probability: 0.55,
  phrase: "can wait a bit", needsConfirmation: true,
}
const ambiguous: ProposedConstraint = {
  key: "k2", type: "PRECEDENCE", probability: 0.93, phrase: "before gpt-agent-5",
  needsConfirmation: false,
  candidates: [
    { id: 7, name: "gpt-agent-5", startUtc: "2026-11-08T14:00:00Z" },
    { id: 9, name: "gpt-agent-5", startUtc: "2026-11-09T14:00:00Z" },
  ],
}

const render = (proposals: ProposedConstraint[], decisions: Record<string, Decision> = {}) =>
  renderToStaticMarkup(
    <ConstraintConfirmation proposals={proposals} decisions={decisions} onDecide={() => {}} />)

describe("ConstraintConfirmation", () => {
  it("renders nothing when every proposal was certain", () => {
    const certain: ProposedConstraint = {
      key: "a", type: "DEADLINE", probability: 0.99, phrase: "by 6pm", needsConfirmation: false,
    }
    expect(render([certain])).toBe("")
  })

  it("quotes the operator's own words and says what we read them as", () => {
    const html = render([unsure])
    expect(html).toContain("can wait a bit")
    // Never the enum name: an operator has not heard of FLEXIBILITY_WINDOW.
    expect(html).toContain("how long this job may be delayed")
    expect(html).not.toContain("FLEXIBILITY_WINDOW")
  })

  it("shows how sure we were, so the operator can weigh it", () => {
    expect(render([unsure])).toContain("55% sure")
  })

  it("offers a job picker only once an ambiguous constraint is accepted", () => {
    expect(render([ambiguous])).not.toContain("Which job does it refer to?")

    const accepted = render([ambiguous], { k2: { accepted: true } })
    expect(accepted).toContain("Which job does it refer to?")
    expect((accepted.match(/<option/g) ?? []).length).toBe(3) // placeholder + two jobs
  })

  it("distinguishes two jobs sharing a name by their start time", () => {
    const html = render([ambiguous], { k2: { accepted: true } })
    // Both options read "gpt-agent-5"; only the start time tells them apart.
    expect(html).toContain("08 Nov 2026")
    expect(html).toContain("09 Nov 2026")
  })

  it("states what is still blocking the save", () => {
    expect(render([unsure])).toContain("Confirm the remaining suggested constraint before saving.")
    expect(render([ambiguous], { k2: { accepted: true } })).toContain("Choose which job")
  })

  it("stops warning once everything is settled", () => {
    const html = render([unsure, ambiguous],
      { k1: { accepted: false }, k2: { accepted: true, referencedJobId: 9 } })

    // The items stay listed so the operator can change their mind; only the prompting stops.
    expect(html).not.toContain('role="alert"')
    expect(html).toContain("All settled. You can save this job.")
    expect(html).toContain("before gpt-agent-5")
  })

  it("counts only what is still unanswered, not everything it ever asked", () => {
    const half = render([unsure, ambiguous], { k1: { accepted: false } })
    expect(half).toContain("One part of your description")
  })

  it("escapes the operator's text rather than trusting it as markup", () => {
    const nasty: ProposedConstraint = { ...unsure, phrase: "<script>bad()</script>" }
    const html = render([nasty])
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
  })
})
