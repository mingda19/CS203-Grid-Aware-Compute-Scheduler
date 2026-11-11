import { describe, expect, it } from "vitest"
import { buildForecastRows, cheapestHours, hourSpans, HOUR_MS, type ForecastRow } from "./price-forecast"
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
    const rows = buildForecastRows(history, [fc(2, 99, 5), fc(3, 30, 4), fc(4, 31)], [fc(3, 40)], ms(0), ms(23))

    expect(rows.map((r) => r.t)).toEqual([ms(1), ms(2), ms(3), ms(4)])
    // Hour 2 is known, so its forecast of 99 is dropped; it anchors the dashed lines at the known price.
    expect(rows[1]).toMatchObject({ price: 22, forecast: 22, band: [22, 22], compare: 22 })
    expect(rows[2]).toMatchObject({ price: null, forecast: 30, band: [26, 34], typicalError: 4, compare: 40 })
    // No typical error: no band for that hour.
    expect(rows[3]).toMatchObject({ forecast: 31, band: null })
  })

  // 2026-11-12T06:00Z is midnight CT, so 06:00Z to 05:00Z next day is one CT day (Thu 12 Nov).
  const start = Date.parse("2026-11-12T06:00:00Z")
  const hourRows = (prices: Array<number | null>, knownCount = 0): ForecastRow[] => prices.map((p, i) => ({
    t: start + i * HOUR_MS, price: i < knownCount ? p : null, forecast: i < knownCount ? null : p,
    band: null, typicalError: null, confidence: null, compare: null,
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

  it("leaves out hours outside the window", () => {
    const rows = buildForecastRows([{ intervalStartUtc: at(1), sppUsdMwh: 20 }], [fc(9, 30)], null, ms(2), ms(8))
    expect(rows).toEqual([])
  })
})
