import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, it, expect } from "vitest"
import { ExplanationSummary } from "./explanation-summary"

const REAL = [
  "- Scheduled 4/11 jobs",
  "- 3 jobs placed on the cheapest available slots (2 moved)",
  "- 5 jobs could not be scheduled: their scheduling window has passed",
  "- Projected energy cost is $12.3456 against $20.0000 at the original times, saving $7.6544 (38.27%)",
  "The above optimisation plan is based on forecasted prices; projected savings are estimates and non-final.",
].join("\n")

describe("ExplanationSummary", () => {
  it("renders the facts as a real list, one item per line", () => {
    const html = renderToStaticMarkup(<ExplanationSummary text={REAL} />)

    expect(html).toContain("<ul")
    expect((html.match(/<li/g) ?? []).length).toBe(4)
    expect(html).toContain("Scheduled 4/11 jobs")
    expect(html).toContain("their scheduling window has passed")
  })

  it("does not reprint the leading hyphen, since the list supplies its own marker", () => {
    const html = renderToStaticMarkup(<ExplanationSummary text="- Scheduled 1/1 jobs" />)
    expect(html).not.toContain("- Scheduled")
    expect(html).toContain("Scheduled 1/1 jobs")
  })

  it("sets the closing caveat apart from the facts instead of listing it as one", () => {
    const html = renderToStaticMarkup(<ExplanationSummary text={REAL} />)
    const caveat = "projected savings are estimates and non-final"
    expect(html).toContain(caveat)
    // It is a paragraph, not a list item: the facts are what the run found, this is a qualifier.
    expect(html.slice(html.indexOf("</ul>"))).toContain(caveat)
  })

  it("escapes its text rather than trusting it as markup", () => {
    const html = renderToStaticMarkup(<ExplanationSummary text={"- <script>bad()</script>"} />)
    expect(html).toContain("&lt;script&gt;")
    expect(html).not.toContain("<script>")
  })

  it("still shows an older prose summary that has no bullets at all", () => {
    const prose = "Scheduled 1 of 1 job(s). Projected energy cost is $1.0000."
    const html = renderToStaticMarkup(<ExplanationSummary text={prose} />)
    expect(html).toContain("Projected energy cost is $1.0000.")
    expect(html).not.toContain("<ul")
  })

  it("says so when there is no summary rather than rendering nothing", () => {
    const html = renderToStaticMarkup(<ExplanationSummary text={null} />)
    expect(html).toContain("No summary was recorded for this run.")
  })
})
