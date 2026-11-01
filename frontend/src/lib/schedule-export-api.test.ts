import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ScheduleExportError, scheduleExportApi, setAccessToken } from "./api"

const RUN_ID = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44"
const EXPORT_URL = `http://localhost:8080/api/schedules/${RUN_ID}/export.csv`
const REFRESH_URL = "http://localhost:8080/api/auth/refresh"

function envelope(status: number, message: string, reason?: string) {
  return new Response(JSON.stringify({ success: false, message, data: reason ? { reason } : null }), {
    status,
    headers: { "Content-Type": "application/json" },
  })
}

function csvFile() {
  return new Response("runId,jobId\r\n", {
    status: 200,
    headers: {
      "Content-Type": "text/csv;charset=UTF-8",
      "Content-Disposition": `attachment; filename="schedule-run-${RUN_ID}.csv"`,
    },
  })
}

describe("scheduleExportApi.download", () => {
  let fetchMock: ReturnType<typeof vi.fn>

  beforeEach(() => {
    setAccessToken("token-1")
    fetchMock = vi.fn()
    vi.stubGlobal("fetch", fetchMock)
  })

  afterEach(() => {
    vi.unstubAllGlobals()
    setAccessToken(null)
  })

  it("sends the JWT and returns the file under its Content-Disposition name", async () => {
    fetchMock.mockResolvedValueOnce(csvFile())

    const file = await scheduleExportApi.download(RUN_ID, "csv")

    expect(file.filename).toBe(`schedule-run-${RUN_ID}.csv`)
    expect(await file.blob.text()).toBe("runId,jobId\r\n")
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(EXPORT_URL)
    expect(init.headers.Authorization).toBe("Bearer token-1")
    expect(init.headers.Accept).toContain("text/csv")
  })

  it("falls back to schedule-run-<id>.<ext> when the filename header is not readable", async () => {
    fetchMock.mockResolvedValueOnce(new Response("{}", { status: 200 }))

    const file = await scheduleExportApi.download(RUN_ID, "json")

    expect(file.filename).toBe(`schedule-run-${RUN_ID}.json`)
    expect(fetchMock.mock.calls[0][0]).toBe(`http://localhost:8080/api/schedules/${RUN_ID}/export.json`)
  })

  it.each([
    [403, "NOT_APPROVED", `Schedule run ${RUN_ID} is PENDING, not APPROVED, so it cannot be exported`],
    [409, "STALE", `Schedule run ${RUN_ID} is stale: all of its slots ended by 2026-10-21T03:00:00Z`],
    [409, "INVALIDATED", `Schedule run ${RUN_ID} is invalidated: job 7 is no longer in your jobs`],
  ])("surfaces a %i %s refusal without refreshing the token", async (status, reason, message) => {
    fetchMock.mockResolvedValueOnce(envelope(status, message, reason))

    const error = await scheduleExportApi.download(RUN_ID, "csv").catch((e) => e)

    expect(error).toBeInstanceOf(ScheduleExportError)
    expect(error).toMatchObject({ status, reason, message })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  // Spring Boot's default error body for a rejected token, as a real server sends it (MockMvc sends none).
  const securityRejection = () =>
    new Response(JSON.stringify({ timestamp: "2026-11-01T03:07:12Z", status: 403, error: "Forbidden", path: "/x" }), {
      status: 403,
      headers: { "Content-Type": "application/json" },
    })

  it.each([
    ["Spring's default error body", securityRejection],
    ["an empty body", () => new Response("", { status: 403 })],
  ])("refreshes the token and retries once when the security layer rejects it with %s", async (_, rejection) => {
    fetchMock.mockImplementation(async (url: string, init: { headers: Record<string, string> }) => {
      if (url === REFRESH_URL) {
        return new Response(JSON.stringify({ success: true, message: "ok", accessToken: "token-2" }), { status: 200 })
      }
      return init.headers.Authorization === "Bearer token-2" ? csvFile() : rejection()
    })

    const file = await scheduleExportApi.download(RUN_ID, "csv")

    expect(file.filename).toBe(`schedule-run-${RUN_ID}.csv`)
    expect(fetchMock.mock.calls.map(([url]) => url)).toEqual([EXPORT_URL, REFRESH_URL, EXPORT_URL])
  })

  it("reports an expired session when the refresh fails too", async () => {
    fetchMock.mockImplementation(async (url: string) =>
      url === REFRESH_URL ? envelope(401, "Refresh token expired") : securityRejection(),
    )

    const error = await scheduleExportApi.download(RUN_ID, "csv").catch((e) => e)

    expect(error).toMatchObject({ status: 403, reason: undefined })
    expect(error.message).toMatch(/session has expired/)
  })

  it("passes through the not-found message for missing or unowned runs", async () => {
    fetchMock.mockResolvedValueOnce(envelope(404, "Optimization plan not found"))

    await expect(scheduleExportApi.download(RUN_ID, "csv")).rejects.toMatchObject({
      status: 404,
      message: "Optimization plan not found",
    })
  })
})
