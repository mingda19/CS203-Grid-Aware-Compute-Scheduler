import { describe, expect, it } from "vitest"
import type { DataFreshnessResponse, DatasetFreshness } from "./api"
import { freshnessSummary } from "./data-freshness"

const response = (...statuses: DatasetFreshness["status"][]): DataFreshnessResponse => ({
  datasets: statuses.map((status, index) => ({ dataset: String(index), status, latestRecordTimestamp: null, ageMinutes: null, message: status })),
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
