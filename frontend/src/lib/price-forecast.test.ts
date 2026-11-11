import { describe, expect, it } from "vitest"
import { accuracyStats, buildForecastRows, cheapestHours, deviationRows, hourSpans, HOUR_MS, type ForecastRow } from "./price-forecast"
import type { PriceForecastPoint } from "./api"

const at = (h: number) => `2026-11-11T${String(h).padStart(2, "0")}:00:00`
const ms = (h: number) => Date.parse(`${at(h)}Z`)
const fc = (h: number, price: number, error?: number): PriceForecastPoint => ({
  intervalStartUtc: at(h), predictedPrice: price, modelVersion: "lear_y0_2026-11-09",
  generatedAt: "2026-11-09T00:10:00", confidenceScore: 0.8, typicalErrorUsdMwh: error,
})

describe("buildForecastRows", () => {
  it("keeps known prices over forecasts and starts the forecast at the last known hour", () => {
    const history = [{ intervalStartUtc: at(1), sppUsdMwh: 20 }, { intervalStartUtc: at(2), sppUsdMwh: 22 }]
    const rows = buildForecastRows(history, [fc(2, 99, 5), fc(3, 30, 4), fc(4, 31)], ms(0), ms(23))

    expect(rows.map((r) => r.t)).toEqual([ms(1), ms(2), ms(3), ms(4)])
    // Hour 2 is known, so its forecast of 99 is dropped; it anchors the dashed line at the known price.
    expect(rows[1]).toMatchObject({ price: 22, forecast: 22, band: [22, 22] })
    expect(rows[2]).toMatchObject({ price: null, forecast: 30, band: [26, 34], typicalError: 4 })
    // No typical error: no band for that hour.
    expect(rows[3]).toMatchObject({ forecast: 31, band: null })
  })

  // 2026-11-12T06:00Z is midnight CT, so 06:00Z to 05:00Z next day is one CT day (Thu 12 Nov).
  const start = Date.parse("2026-11-12T06:00:00Z")
  const hourRows = (prices: Array<number | null>, knownCount = 0): ForecastRow[] => prices.map((p, i) => ({
    t: start + i * HOUR_MS, price: i < knownCount ? p : null, forecast: i < knownCount ? null : p,
    band: null, typicalError: null, confidence: null,
  }))

  it("cheapestHours marks the 4 cheapest hours of each CT day, known or forecast, from fromHour on", () => {
    // Hour 0 is before fromHour; the 4 cheapest after it are 10 (known), 11, 12 and 13. Hour 8 has no price.
    const rows = hourRows([1, 10, 40, 12, 30, 11, 60, 13, null], 2)
    const low = cheapestHours(rows, start + HOUR_MS)
    expect([...low].map((t) => (t - start) / HOUR_MS).sort()).toEqual([1, 3, 5, 7])
  })

  it("cheapestHours marks nothing on a day with 4 or fewer priced hours", () => {
    expect(cheapestHours(hourRows([5, 6, 7, 8]), start).size).toBe(0)
  })

  it("hourSpans joins consecutive hours", () => {
    const h = (n: number) => start + n * HOUR_MS
    expect(hourSpans(new Set([h(3), h(1), h(2), h(6)]))).toEqual([[h(1), h(4)], [h(6), h(7)]])
  })

  it("accuracyStats gives the average miss, typical error (RMSE) and biggest miss", () => {
    const p = (h: number, predicted: number, actual: number) => ({
      intervalStartUtc: at(h), predictedPrice: predicted, actualPrice: actual, modelVersion: "lear_y0_x", generatedAt: at(0),
    })
    const stats = accuracyStats([p(1, 10, 13), p(2, 20, 16), p(3, 30, 30)]) // misses 3, 4, 0
    expect(stats.hours).toBe(3)
    expect(stats.averageMiss).toBeCloseTo(7 / 3)
    expect(stats.typicalError).toBeCloseTo(Math.sqrt(25 / 3))
    expect(stats.biggestMiss).toEqual({ miss: 4, t: ms(2) })
    expect(accuracyStats([])).toEqual({ hours: 0, averageMiss: null, typicalError: null, biggestMiss: null })
  })

  it("deviationRows fills every hour and signs forecast minus price", () => {
    const p = (h: number, predicted: number, actual: number) => ({
      intervalStartUtc: at(h), predictedPrice: predicted, actualPrice: actual, modelVersion: "lear_y0_x", generatedAt: at(0),
    })
    const rows = deviationRows([p(1, 21, 20), p(3, 18, 20)], ms(1), ms(3))
    expect(rows.map((r) => r.deviation)).toEqual([1, null, -2])
  })

  it("leaves out hours outside the window", () => {
    const rows = buildForecastRows([{ intervalStartUtc: at(1), sppUsdMwh: 20 }], [fc(9, 30)], ms(2), ms(8))
    expect(rows).toEqual([])
  })
})
