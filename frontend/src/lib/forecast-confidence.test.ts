import { describe, it, expect } from "vitest"
import {
  confidenceBand,
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
  })

  it("formats a score as a percentage and its band", () => {
    expect(formatConfidence(0.824)).toBe("82%, High")
    expect(formatConfidence(0.5)).toBe("50%, Moderate")
  })
})
