import { describe, expect, it } from "vitest"
import { currentSettlementMetrics } from "./settlement-price"

const now = Date.parse("2026-11-02T10:30:00Z")

describe("current settlement price", () => {
  it("uses the current hour rather than the final, future row for the day", () => {
    const result = currentSettlementMetrics([
      { intervalStartUtc: "2026-11-02T09:00:00", sppUsdMwh: 20 },
      { intervalStartUtc: "2026-11-02T10:00:00", sppUsdMwh: 30 },
      { intervalStartUtc: "2026-11-02T23:00:00", sppUsdMwh: 999 },
    ], now)
    expect(result.currentLmp).toBe(30)
    expect(result.rollingAvgFormatted).toBe("$25.00")
    expect(result.peakPriceFormatted).toBe("$30.00")
    expect(result.stale).toBe(false)
  })

  it("switches to the next hourly price as time advances without needing different records", () => {
    const points = [
      { intervalStartUtc: "2026-11-02T10:00:00Z", sppUsdMwh: 30 },
      { intervalStartUtc: "2026-11-02T11:00:00Z", sppUsdMwh: -5 },
    ]
    expect(currentSettlementMetrics(points, now).currentLmp).toBe(30)
    expect(currentSettlementMetrics(points, Date.parse("2026-11-02T11:00:00Z")).currentLmp).toBe(-5)
  })

  it("sorts records and ignores null, invalid, and future prices", () => {
    const result = currentSettlementMetrics([
      { intervalStartUtc: "2026-11-02T10:00:00Z", sppUsdMwh: 0 },
      { intervalStartUtc: "2026-11-02T09:00:00Z", sppUsdMwh: 20 },
      { intervalStartUtc: "2026-11-02T10:15:00Z", sppUsdMwh: null },
      { intervalStartUtc: "invalid", sppUsdMwh: 500 },
      { intervalStartUtc: "2026-11-02T10:20:00Z", sppUsdMwh: NaN },
    ], now)
    expect(result.currentLmp).toBe(0)
    expect(result.latest?.intervalStartUtc).toBe("2026-11-02T10:00:00Z")
  })

  it("reports unavailable if only future records exist, and stale if the current hour is missing", () => {
    expect(currentSettlementMetrics([{ intervalStartUtc: "2026-11-02T11:00:00Z", sppUsdMwh: 999 }], now).currentLmp).toBeNull()
    expect(currentSettlementMetrics([{ intervalStartUtc: "2026-11-02T09:00:00Z", sppUsdMwh: 20 }], now).stale).toBe(true)
  })
})
