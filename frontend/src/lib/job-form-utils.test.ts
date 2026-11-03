import { afterEach, describe, expect, it, vi } from "vitest"
import { optimizerApi, setAccessToken, type ComputeJob } from "./api"
import { toOptimizeJobInput } from "./job-form-utils"

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
