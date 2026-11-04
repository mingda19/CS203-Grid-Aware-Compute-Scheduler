import { afterEach, describe, expect, it, vi } from "vitest"
import { optimizerApi, setAccessToken, type ComputeJob } from "./api"
import { toApiUtcDate, toInputDate, toOptimizeJobInput } from "./job-form-utils"

afterEach(() => { vi.unstubAllGlobals(); setAccessToken(null) })

describe("optimizer job submission", () => {
  it.each(["CRITICAL", "HIGH", "NORMAL", "LOW", undefined] as const)("submits saved priority %s", async priority => {
    const job: ComputeJob = {
      id: 1, name: "Training", cpuHours: 4, gpuHours: 0, originalStartTime: "2026-11-05T10:00:00",
      deadline: "2026-11-05T15:00:00", flexibilityWindowHours: 2, isExempt: false,
      status: "PENDING", priority,
    }
    setAccessToken("test-token")
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)
    await optimizerApi.optimize({ jobs: [toOptimizeJobInput(job)], forecastLocation: "LZ_NORTH" })
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe("http://localhost:8080/api/schedules/optimize")
    expect(JSON.parse(init.body).jobs[0]).toEqual({
      id: job.id, name: job.name, cpuHours: job.cpuHours, gpuHours: job.gpuHours,
      originalStartTime: job.originalStartTime, deadline: job.deadline,
      flexibilityWindowHours: job.flexibilityWindowHours, isExempt: job.isExempt,
      priority: priority ?? "NORMAL",
    })
  })
})

// Regression guard. These conversions previously subtracted a fixed 8 hours, which
// only worked because Singapore has no DST. ERCOT settles in Central Time, where
// the offset is -6 in winter and -5 in summer, so a constant silently shifted every
// saved job start and deadline by an hour for most of the year.
describe("market wall time <-> API UTC round trip", () => {
  it.each([
    ["winter (CST, -6)", "2026-01-15T09:00", "2026-01-15T15:00:00"],
    ["summer (CDT, -5)", "2026-07-15T09:00", "2026-07-15T14:00:00"],
  ])("converts form input to UTC in %s", (_season, formValue, expectedUtc) => {
    expect(toApiUtcDate(formValue)).toBe(expectedUtc)
    expect(toInputDate(expectedUtc)).toBe(formValue)
  })

  it("keeps the same wall time across the DST boundary rather than the same UTC hour", () => {
    // An operator typing 09:00 means 09:00 locally in both seasons. The UTC hour
    // behind it must differ by one, which a fixed offset cannot produce.
    const winter = toApiUtcDate("2026-01-15T09:00")
    const summer = toApiUtcDate("2026-07-15T09:00")
    expect(winter.slice(11, 13)).toBe("15")
    expect(summer.slice(11, 13)).toBe("14")
  })

  it("rounds to the top of the hour when asked, without disturbing the offset", () => {
    expect(toApiUtcDate("2026-07-15T09:45", true)).toBe("2026-07-15T14:00:00")
    expect(toApiUtcDate("2026-07-15T09:45", false)).toBe("2026-07-15T14:45:00")
  })

  it("returns an empty string for a missing value", () => {
    expect(toInputDate(undefined)).toBe("")
    expect(toInputDate("")).toBe("")
  })
})
