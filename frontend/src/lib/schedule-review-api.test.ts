import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { ApiRequestError, scheduleReviewApi, setAccessToken } from "./api"

const runId = "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44"
const envelope = (status: number, data: unknown, message = "result") => new Response(JSON.stringify({ success: status < 400, data, message }), { status })

beforeEach(() => setAccessToken("token-1"))
afterEach(() => { vi.unstubAllGlobals(); setAccessToken(null) })

describe("schedule review API", () => {
  it("loads paginated owned plans and persisted details without caching", async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope(200, {}))
    vi.stubGlobal("fetch", fetchMock)
    await scheduleReviewApi.list(2)
    await scheduleReviewApi.detail(runId)
    expect(fetchMock.mock.calls[0][0]).toBe("http://localhost:8080/api/schedules?page=2&size=20")
    expect(fetchMock.mock.calls[1][0]).toBe(`http://localhost:8080/api/schedules/${runId}`)
    expect(fetchMock.mock.calls[0][1].cache).toBe("no-store")
    expect(fetchMock.mock.calls[1][1].headers.Authorization).toBe("Bearer token-1")
  })

  it.each(["approve", "reject", "revise"] as const)("posts a %s justification without dispatching", async action => {
    const fetchMock = vi.fn().mockResolvedValue(envelope(200, { reviewState: "APPROVED" }))
    vi.stubGlobal("fetch", fetchMock)
    await scheduleReviewApi.decide(runId, action, "Reviewed constraints and costs")
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe(`http://localhost:8080/api/schedules/${runId}/${action}`)
    expect(init.method).toBe("POST")
    expect(JSON.parse(init.body)).toEqual({ comment: "Reviewed constraints and costs" })
  })

  it("adds the approved run through the gated apply endpoint instead of creating individual calendar entries", async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope(200, []))
    vi.stubGlobal("fetch", fetchMock)
    await scheduleReviewApi.apply(runId)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    expect(fetchMock.mock.calls[0][0]).toBe(`http://localhost:8080/api/schedules/${runId}/apply`)
    expect(fetchMock.mock.calls[0][1].method).toBe("POST")
    expect(fetchMock.mock.calls[0][1].headers.Authorization).toBe("Bearer token-1")
  })

  it("reports rejected approval and stale schedule errors when adding to the calendar", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(envelope(403, { reason: "NOT_APPROVED" }, "Approval required"))
      .mockResolvedValueOnce(envelope(401, null))
      .mockResolvedValueOnce(envelope(409, { reason: "STALE" }, "Schedule expired"))
    vi.stubGlobal("fetch", fetchMock)
    await expect(scheduleReviewApi.apply(runId)).rejects.toMatchObject({ status: 403, reason: "NOT_APPROVED" })
    await expect(scheduleReviewApi.apply(runId)).rejects.toMatchObject({ status: 409, reason: "STALE" })
  })

  it("preserves 409 status and the refusal reason for conflict banners", async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope(409, { reason: "STALE" }, "Schedule superseded"))
    vi.stubGlobal("fetch", fetchMock)
    await expect(scheduleReviewApi.decide(runId, "approve", "Checked")).rejects.toMatchObject({ status: 409, reason: "STALE", message: "Schedule superseded" })
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })

  it("preserves conflicts following token refresh without retrying the decision again", async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(envelope(401, null))
      .mockResolvedValueOnce(new Response(JSON.stringify({ accessToken: "token-2" }), { status: 200 }))
      .mockResolvedValueOnce(envelope(409, { reason: "INVALIDATED" }, "Job changed"))
    vi.stubGlobal("fetch", fetchMock)
    await expect(scheduleReviewApi.decide(runId, "approve", "Checked")).rejects.toBeInstanceOf(ApiRequestError)
    expect(fetchMock).toHaveBeenCalledTimes(3)
    expect(fetchMock.mock.calls[2][1].headers.Authorization).toBe("Bearer token-2")
  })

  it("does not convert forbidden responses into success", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(envelope(403, null, "Forbidden")))
    await expect(scheduleReviewApi.detail(runId)).rejects.toMatchObject({ status: 403 })
  })
})

/**
 * Task 1. The revoke button reportedly "did nothing" while an exception reached the backend's global
 * handler. Two halves of that are client-side: the declared response type did not admit
 * `action: "REVOKE"` at all, and every non-envelope failure (a 404 from a server older than this
 * page, an HTML error page from a proxy, a dropped connection) collapsed to "Request failed with
 * status N" or to a raw TypeError - nothing an operator could act on, which is what "did nothing"
 * looked like from the outside.
 */
describe("revoking an approval", () => {
  it("posts to the revoke endpoint and reports the recorded REVOKE action", async () => {
    const fetchMock = vi.fn().mockResolvedValue(envelope(200, {
      decisionId: 9, runId, action: "REVOKE", operatorId: 1, comment: "Cooling margin lost",
      decidedAt: "2026-11-04T10:00:00Z", reviewState: "REJECTED",
    }))
    vi.stubGlobal("fetch", fetchMock)
    const response = await scheduleReviewApi.decide(runId, "revoke", "Cooling margin lost")
    expect(fetchMock.mock.calls[0][0]).toBe(`http://localhost:8080/api/schedules/${runId}/revoke`)
    expect(fetchMock.mock.calls[0][1].method).toBe("POST")
    // Typed, not cast: `action` has to admit "REVOKE" for this to compile at all.
    expect(response.data?.action).toBe("REVOKE")
    expect(response.data?.reviewState).toBe("REJECTED")
  })

  it("explains a missing endpoint instead of reporting a bare status", async () => {
    // What a browser newer than its server actually receives: Spring's own error page, not an envelope.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(
      new Response("<html><body>Whitelabel Error Page</body></html>",
        { status: 404, headers: { "Content-Type": "text/html" } })))
    await expect(scheduleReviewApi.decide(runId, "revoke", "Halt it")).rejects.toMatchObject({ status: 404 })
    await expect(scheduleReviewApi.decide(runId, "revoke", "Halt it"))
      .rejects.toThrow(/not available on the server \(HTTP 404\)/)
  })

  it("explains a method the server does not allow on the decision path", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response("", { status: 405 })))
    await expect(scheduleReviewApi.decide(runId, "revoke", "Halt it"))
      .rejects.toThrow(/not available on the server \(HTTP 405\)/)
  })

  it("turns a transport failure into a readable ApiRequestError rather than a raw TypeError", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new TypeError("Failed to fetch")))
    const failure = await scheduleReviewApi.decide(runId, "revoke", "Halt it").catch(error => error)
    expect(failure).toBeInstanceOf(ApiRequestError)
    expect(failure.status).toBe(0)
    expect(failure.message).toMatch(/could not be reached/)
  })

  it("does not render a non-string message or a non-string reason from a malformed body", async () => {
    // A gateway answering valid JSON of the wrong shape: `message` an object, `data.reason` a number.
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(new Response(
      JSON.stringify({ success: false, message: { nested: "oops" }, data: { reason: 7 } }), { status: 409 })))
    const failure = await scheduleReviewApi.decide(runId, "revoke", "Halt it").catch(error => error)
    expect(failure.status).toBe(409)
    expect(failure.message).toBe("Request failed with status 409")
    expect(failure.reason).toBeUndefined()
  })

  it("still prefers the backend's own envelope message when there is one", async () => {
    vi.stubGlobal("fetch", vi.fn().mockResolvedValue(envelope(409, { reason: "ALREADY_DECIDED" },
      "Schedule run is pending, not approved, so there is no approval to revoke")))
    await expect(scheduleReviewApi.decide(runId, "revoke", "Halt it")).rejects.toMatchObject({
      status: 409, reason: "ALREADY_DECIDED",
      message: "Schedule run is pending, not approved, so there is no approval to revoke",
    })
  })
})
