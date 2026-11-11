import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it, vi } from "vitest"
import { ConstraintComparison, ConstraintParseReview, Suggestions } from "./ConstraintParseReview"
import type { ConstraintParseResponse } from "../../lib/api"

const result: ConstraintParseResponse = {
  status: "PARSED", fields: { originalStartTime: "2026-11-01T18:00:00", deadline: null, flexibilityWindowHours: 4, priority: "HIGH", isExempt: false },
  evidence: [{ field: "flexibilityWindowHours", value: "4", phrase: "can wait 4 hours", start: 0, end: 16 }],
  ambiguities: [], conflicts: [], unsupported: [], validationError: null, parsedAt: "2026-11-01T10:00:00", timeZone: "America/Chicago",
}

describe("constraint review UI", () => {
  it("shows a choice for job relationships and explains they will be saved", () => {
    const html = renderToStaticMarkup(<Suggestions suggestions={[{ type: "PRECEDENCE", probability: .5,
      phrase: "finish before the batch", referencedJobId: 9, needsConfirmation: true }]} />)
    expect(html).toContain("Keep relationship")
    expect(html).toContain("Ignore relationship")
    expect(html).toContain("saved with the job")
    expect(html).not.toContain("checked=")
  })
  it("shows raw text and proposed fields together with source phrases and market times", () => {
    const html = renderToStaticMarkup(<ConstraintComparison raw="can wait 4 hours <script>bad()</script>" result={result} />)
    expect(html).toContain("md:grid-cols-2")
    for (const text of ["Original text", "Proposed values", "4 hours", "HIGH", "Original start (CT)", "01 Nov 2026, 12:00 CST", "Kept from current form", "can wait 4 hours"]) expect(html).toContain(text)
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
  })
  it("shows clarification questions, differences and unsupported phrases", () => {
    const html = renderToStaticMarkup(<ConstraintComparison raw="by 6" result={{ ...result,
      ambiguities: [{ field: "deadline", reason: "AM_OR_PM", phrase: "by 6", message: "6am or 6pm?", start: 0, end: 4 }],
      conflicts: [{ field: "priority", phrases: ["urgent", "form: NORMAL"], message: "Priority differs from form" }],
      unsupported: [{ phrase: "16 GPUs", start: 0, end: 7 }],
    }} />)
    expect(html).toContain("6am or 6pm?")
    expect(html).toContain("Priority differs from form")
    expect(html).toContain("16 GPUs")
  })
  it("never parses, applies or saves merely by rendering existing source text", () => {
    const onConfirm = vi.fn()
    const onPendingChange = vi.fn()
    const html = renderToStaticMarkup(<ConstraintParseReview draft={result.fields} confirmedSource="high priority" onConfirm={onConfirm} onPendingChange={onPendingChange} />)
    expect(onConfirm).not.toHaveBeenCalled()
    expect(onPendingChange).not.toHaveBeenCalled()
    expect(html).toContain("Parse and review")
    expect(html).not.toContain("Confirm and apply proposed values")
    expect(html).toContain('type="button"')
  })
})
