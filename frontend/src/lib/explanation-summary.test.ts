import { describe, it, expect } from "vitest"
import { parseExplanationSummary } from "./explanation-summary"

// A summary in the shape ScheduleOptimizationService.explain() actually writes.
const REAL = [
  "- Scheduled 4/11 jobs",
  "- 3 jobs placed on the cheapest available slots (2 moved)",
  "- 1 exempt job runs at the original time",
  "- 5 jobs could not be scheduled: their scheduling window has passed",
  "- 2 jobs could not be scheduled: other jobs hold the capacity they need",
  "- Projected energy cost is $12.3456 against $20.0000 at the original times, saving $7.6544 (38.27%)",
  "The above optimisation plan is based on forecasted prices; projected savings are estimates and non-final.",
].join("\n")

describe("parseExplanationSummary", () => {
  it("separates the facts from the closing caveat", () => {
    const { bullets, notes } = parseExplanationSummary(REAL)

    expect(bullets).toHaveLength(6)
    expect(bullets[0]).toBe("Scheduled 4/11 jobs")
    expect(notes).toEqual([
      "The above optimisation plan is based on forecasted prices; projected savings are estimates and non-final.",
    ])
  })

  it("strips the bullet marker so the list can supply its own", () => {
    expect(parseExplanationSummary("- Scheduled 1/1 jobs").bullets).toEqual(["Scheduled 1/1 jobs"])
  })

  it("keeps a hyphen that is part of the sentence rather than a marker", () => {
    // "-5" is a value, not a bullet: only "- " (with the space) marks a line.
    const { bullets, notes } = parseExplanationSummary("-5 degrees of slack remained")
    expect(bullets).toEqual([])
    expect(notes).toEqual(["-5 degrees of slack remained"])
  })

  it("ignores blank lines and surrounding whitespace", () => {
    const { bullets, notes } = parseExplanationSummary("\n  - Scheduled 1/1 jobs  \n\n  A caveat.  \n")
    expect(bullets).toEqual(["Scheduled 1/1 jobs"])
    expect(notes).toEqual(["A caveat."])
  })

  it("renders an older prose summary as notes rather than losing it", () => {
    // Runs stored before the point-form change have no bullets at all; they must still show.
    const prose = "Scheduled 1 of 1 job(s). Projected energy cost is $1.0000."
    expect(parseExplanationSummary(prose)).toEqual({ bullets: [], notes: [prose] })
  })

  it("is empty for empty input rather than throwing", () => {
    for (const input of ["", "   ", null, undefined]) {
      expect(parseExplanationSummary(input)).toEqual({ bullets: [], notes: [] })
    }
  })
})
