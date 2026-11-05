import { afterEach, describe, expect, it, vi } from "vitest"
import { optimizerApi, setAccessToken, type ComputeJob } from "./api"
import { effectiveFlexHours, effectiveStart, maxJobHours, toApiUtcDate, toInputDate, toOptimizeJobInput, windowHours } from "./job-form-utils"

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

/**
 * Task 3. The elapsed-job widening must never be sent unless the operator confirmed it, so the
 * request body carries the flag only when asked for and is otherwise byte-for-byte what it was.
 */
describe("opting in to rescheduling jobs whose start has passed", () => {
  const job: ComputeJob = {
    id: 1, name: "Training", cpuHours: 4, gpuHours: 0, originalStartTime: "2026-11-05T10:00:00",
    deadline: "2026-11-05T15:00:00", flexibilityWindowHours: 2, isExempt: false, status: "PENDING",
  }

  const bodyOf = async (payload: Parameters<typeof optimizerApi.optimize>[0]) => {
    setAccessToken("test-token")
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)
    await optimizerApi.optimize(payload)
    return JSON.parse(fetchMock.mock.calls[0][1].body)
  }

  it("omits the flag entirely on the default path", async () => {
    const body = await bodyOf({ jobs: [toOptimizeJobInput(job)], forecastLocation: "LZ_NORTH" })
    // Absent, not false: the server's default is false either way, and an absent key keeps the
    // payload identical to what every caller sent before this option existed.
    expect("rescheduleElapsedJobs" in body).toBe(false)
  })

  it("sends the flag when the operator confirmed it", async () => {
    const body = await bodyOf({
      jobs: [toOptimizeJobInput(job)], forecastLocation: "LZ_NORTH", rescheduleElapsedJobs: true,
    })
    expect(body.rescheduleElapsedJobs).toBe(true)
    // And nothing else about the request changes.
    expect(body.jobs[0].originalStartTime).toBe(job.originalStartTime)
    expect(body.jobs[0].flexibilityWindowHours).toBe(job.flexibilityWindowHours)
  })
})

describe("hours maximum", () => {
  it("scales the per-slot cap by whole window hours", () => {
    expect(windowHours("2026-11-05T10:00", "2026-11-05T22:30")).toBe(12.5)
    expect(maxJobHours(256, "2026-11-05T10:00", "2026-11-05T22:30")).toBe(256 * 12)
  })
  it("has no maximum without a usable window", () => {
    expect(maxJobHours(256, "", "2026-11-05T22:00")).toBe(Infinity)
    expect(maxJobHours(256, "2026-11-05T10:00", "2026-11-05T10:30")).toBe(Infinity)
  })
})

describe("schedule mode", () => {
  const [start, deadline] = ["2026-11-05T10:00", "2026-11-05T22:00"]
  it("immediate allows no delay", () => expect(effectiveFlexHours("immediate", "5", start, deadline)).toBe(0))
  it("flexible defaults to the whole window", () => expect(effectiveFlexHours("flexible", "", start, deadline)).toBe(12))
  it("flexible keeps an explicit value", () => expect(effectiveFlexHours("flexible", "4", start, deadline)).toBe(4))
  it("immediate moves a stale start to the current hour", () => {
    expect(effectiveStart("immediate", "", "2026-11-05T11:00")).toBe("2026-11-05T11:00")
    expect(effectiveStart("flexible", start, "2026-11-05T11:00")).toBe(start)
  })
})
