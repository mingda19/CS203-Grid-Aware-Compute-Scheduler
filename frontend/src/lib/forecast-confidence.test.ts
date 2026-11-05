import { describe, it, expect } from "vitest"
import {
  aggregateConfidence,
  confidenceBand,
  confidenceTone,
  formatConfidence,
  HIGH_CONFIDENCE_FROM,
  MODERATE_CONFIDENCE_FROM,
} from "./forecast-confidence"

describe("forecast confidence banding", () => {
  it("distinguishes high, low and unavailable, as FR-10 requires", () => {
    expect(confidenceBand(0.91)).toBe("High")
    expect(confidenceBand(0.64)).toBe("Moderate")
    expect(confidenceBand(0.2)).toBe("Low")
    expect(confidenceBand(null)).toBe("Not available")
    expect(confidenceBand(undefined)).toBe("Not available")
  })

  it("treats the thresholds as inclusive lower bounds", () => {
    expect(confidenceBand(HIGH_CONFIDENCE_FROM)).toBe("High")
    expect(confidenceBand(HIGH_CONFIDENCE_FROM - 0.01)).toBe("Moderate")
    expect(confidenceBand(MODERATE_CONFIDENCE_FROM)).toBe("Moderate")
    expect(confidenceBand(MODERATE_CONFIDENCE_FROM - 0.01)).toBe("Low")
  })

  it("never reads a missing score as zero confidence", () => {
    // FR-04: a missing value must not silently become a zero. "Not available" and "Low" send an
    // operator to completely different conclusions about whether to approve.
    expect(confidenceBand(null)).not.toBe("Low")
    expect(confidenceBand(Number.NaN)).toBe("Not available")
    expect(formatConfidence(null)).toBe("Not available")
    expect(confidenceTone(null)).toBe("text-muted-foreground")
  })

  it("formats a score as a percentage and its band", () => {
    expect(formatConfidence(0.824)).toBe("82% · High")
    expect(formatConfidence(0.5)).toBe("50% · Moderate")
  })

  it("tones each band differently so the colour carries the same meaning as the words", () => {
    const tones = [0.9, 0.6, 0.1, null].map(confidenceTone)
    expect(new Set(tones).size).toBe(4)
  })
})

describe("run-level aggregate", () => {
  it("averages only the scored intervals and reports how many had none", () => {
    expect(aggregateConfidence([0.9, 0.7, null, undefined])).toEqual({
      mean: 0.8, scored: 2, missing: 2,
    })
  })

  it("is unavailable rather than zero when nothing is scored", () => {
    // Folding unscored intervals in as zeros would make a horizon the model has no error history
    // for look confidently bad, instead of unknown.
    expect(aggregateConfidence([null, null])).toEqual({ mean: null, scored: 0, missing: 2 })
    expect(aggregateConfidence([])).toEqual({ mean: null, scored: 0, missing: 0 })
  })

  it("does not let unscored intervals drag a confident average down", () => {
    expect(aggregateConfidence([0.9, 0.9, null, null, null]).mean).toBe(0.9)
  })
})
