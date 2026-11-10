import { describe, expect, it } from "vitest"
import type { DataFreshnessResponse, DatasetFreshness } from "./api"
import { datasetAgeText, datasetFreshnessText, freshnessSummary } from "./data-freshness"

const response = (...statuses: DatasetFreshness["status"][]): DataFreshnessResponse => ({
  datasets: statuses.map((status, index) => ({ dataset: String(index), label: `Dataset ${index}`, status, latestRecordTimestamp: null, ageMinutes: null, message: status })),
  notifications: [], checkedAt: "2026-11-02T00:00:00Z",
})

describe("source freshness", () => {
  it("never calls missing or failed feeds healthy", () => {
    for (const input of [null, response(), response("MISSING"), response("HEALTHY", "DATABASE_UNAVAILABLE")]) {
      expect(freshnessSummary(input)).toEqual({ status: "unavailable", label: "Unavailable" })
    }
  })
  it("reports stale data even if another source is healthy", () => {
    expect(freshnessSummary(response("HEALTHY", "STALE"))).toEqual({ status: "cached", label: "Stale" })
  })
  it("reports healthy only when every dataset is healthy", () => {
    expect(freshnessSummary(response("HEALTHY", "HEALTHY"))).toEqual({ status: "live", label: "Healthy" })
  })
})

describe("datasetFreshnessText", () => {
  const now = Date.parse("2026-11-07T12:00:00Z")
  const dataset = (over: Partial<DatasetFreshness>): DatasetFreshness => ({
    dataset: "electrical_price", label: "Daily ERCOT price", status: "HEALTHY",
    latestRecordTimestamp: "2026-11-07T11:55:00Z", ageMinutes: 5, message: "", ...over,
  })

  it("says how old a record is", () => {
    expect(datasetFreshnessText(dataset({}), now)).toBe("Daily ERCOT price updated 5m ago")
    expect(datasetFreshnessText(dataset({ latestRecordTimestamp: "2026-11-07T11:01:00Z" }), now)).toBe("Daily ERCOT price updated 59m ago")
    expect(datasetFreshnessText(dataset({ status: "STALE", latestRecordTimestamp: "2026-11-07T09:00:00Z" }), now)).toBe("Daily ERCOT price updated 3h ago")
    expect(datasetFreshnessText(dataset({ status: "STALE", latestRecordTimestamp: "2026-11-05T12:00:00Z" }), now)).toBe("Daily ERCOT price updated 2d ago")
  })
  it("names the problem for missing data and unreachable sources", () => {
    expect(datasetFreshnessText(dataset({ status: "MISSING", latestRecordTimestamp: null }), now)).toBe("Daily ERCOT price: no data")
    expect(datasetFreshnessText(dataset({ status: "DATABASE_UNAVAILABLE", latestRecordTimestamp: null }), now)).toBe("Daily ERCOT price: source unreachable")
  })
  it("admits when the update time is unknown", () => {
    expect(datasetFreshnessText(dataset({ latestRecordTimestamp: null }), now)).toBe("Daily ERCOT price: update time unknown")
  })
  it("gives the age alone for a table column", () => {
    expect(datasetAgeText(dataset({}), now)).toBe("5m ago")
    expect(datasetAgeText(dataset({ status: "MISSING", latestRecordTimestamp: null }), now)).toBe("no data")
    expect(datasetAgeText(dataset({ status: "DATABASE_UNAVAILABLE", latestRecordTimestamp: null }), now)).toBe("source unreachable")
    expect(datasetAgeText(dataset({ latestRecordTimestamp: null }), now)).toBe("update time unknown")
  })
})
