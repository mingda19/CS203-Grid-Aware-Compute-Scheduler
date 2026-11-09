import { afterEach, describe, expect, it, vi } from "vitest"
import { jobsApi, setAccessToken } from "./api"

afterEach(() => { vi.unstubAllGlobals(); setAccessToken(null) })

describe("constraint parse API", () => {
  it("posts raw text, UTC draft and market timezone without saving a job", async () => {
    setAccessToken("test-token")
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true, data: { status: "PARSED" } }), { status: 200 }))
    vi.stubGlobal("fetch", fetchMock)
    const payload = { rawConstraints: "can wait 4 hours", timeZone: "America/Chicago", draft: { originalStartTime: "2026-11-01T14:00:00", deadline: null, flexibilityWindowHours: 0, priority: "NORMAL" as const, isExempt: false } }
    await jobsApi.parseConstraints(payload)
    expect(fetchMock).toHaveBeenCalledTimes(1)
    const [url, init] = fetchMock.mock.calls[0]
    expect(url).toBe("http://localhost:8080/api/jobs/parse-constraints")
    expect(init.method).toBe("POST")
    expect(init.headers.Authorization).toBe("Bearer test-token")
    expect(JSON.parse(init.body)).toEqual(payload)
  })
})
