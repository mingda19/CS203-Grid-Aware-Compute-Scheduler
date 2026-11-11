import { describe, expect, it } from "vitest"
import { buildForecastRows } from "./price-forecast"
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

  it("leaves out hours outside the window", () => {
    const rows = buildForecastRows([{ intervalStartUtc: at(1), sppUsdMwh: 20 }], [fc(9, 30)], null, ms(2), ms(8))
    expect(rows).toEqual([])
  })
})
