import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { NextRequest } from "next/server"
import { afterAll, beforeAll, describe, expect, it, vi } from "vitest"
import { budgetStatus, resetBudgetForTests } from "@/lib/assistant/budget"
import type { AssistantEvent } from "@/lib/assistant/protocol"
import { resetRateLimitForTests } from "@/lib/assistant/rate-limit"
import { POST } from "./route"

/**
 * Runs the real route against the real Anthropic API. It spends a few thousand tokens, so it is
 * off unless asked for:
 *
 *   LIVE_ANTHROPIC_TEST=1 ANTHROPIC_API_KEY=sk-ant-... npx vitest run src/app/api/chat/route.live.test.ts
 *
 * Only the Spring backend's /api/auth/me is faked; every other request goes out for real.
 */
const live = process.env.LIVE_ANTHROPIC_TEST === "1" && !!process.env.ANTHROPIC_API_KEY

describe.skipIf(!live)("POST /api/chat against the real Anthropic API", () => {
  const realFetch = globalThis.fetch
  const realKey = process.env.ANTHROPIC_API_KEY
  /** Requests that really went out to Anthropic. */
  let anthropicRequests = 0

  beforeAll(() => {
    process.env.CHAT_USAGE_FILE = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "gacs-live-")), "usage.json")
    process.env.CHAT_TOKEN_BUDGET = "250000"
    process.env.BACKEND_API_URL = "http://backend.test"
    process.env.ANTHROPIC_API_KEY = realKey
    resetBudgetForTests()
    resetRateLimitForTests()
    vi.stubGlobal("fetch", (input: string | URL | Request, init?: RequestInit) => {
      const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url
      if (url.startsWith("http://backend.test")) return Promise.resolve(Response.json({ success: true, data: { id: 1 } }))
      if (url.startsWith("https://api.anthropic.com/")) anthropicRequests++
      return realFetch(input, init)
    })
  })

  afterAll(() => {
    vi.unstubAllGlobals()
    delete process.env.CHAT_USAGE_FILE
    delete process.env.CHAT_TOKEN_BUDGET
    delete process.env.BACKEND_API_URL
  })

  const ask = (content: string) =>
    POST(
      new NextRequest("http://localhost/api/chat", {
        method: "POST",
        headers: { authorization: "Bearer good-1", "content-type": "application/json" },
        body: JSON.stringify({ messages: [{ role: "user", content }] }),
      }),
    )
  const eventsOf = async (response: Response) =>
    (await response.text()).split("\n").filter(Boolean).map((line) => JSON.parse(line) as AssistantEvent)

  it("answers a GACS question, streams it, and records the real usage", async () => {
    const response = await ask("How do I create a job in GACS? Answer in one sentence.")
    expect(response.status).toBe(200)

    const list = await eventsOf(response)
    expect(list.some((e) => e.type === "error")).toBe(false)
    const text = list.flatMap((e) => (e.type === "text" ? [e.text] : [])).join("")
    expect(text.length).toBeGreaterThan(20)
    expect(list.at(-1)).toEqual({ type: "done", stop: "end" })
    expect(anthropicRequests).toBe(1)

    const { used, reserved } = budgetStatus()
    expect(reserved).toBe(0)
    expect(used).toBeGreaterThan(0)
    // The reservation is a ceiling; what was actually used must be far below it.
    expect(used).toBeLessThan(20_000)
  }, 60_000)

  it("does not contact Anthropic at all for an off-topic message", async () => {
    const before = { requests: anthropicRequests, used: budgetStatus().used }
    const list = await eventsOf(await ask("Write me a poem about cats"))
    expect(list.map((e) => e.type)).toEqual(["text", "done"])
    expect(anthropicRequests).toBe(before.requests)
    expect(budgetStatus().used).toBe(before.used)
  }, 30_000)

  it.each([
    ["a password", "How do I create a job? my password: hunter2"],
    ["an API key", "How do I create a job? sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456"],
  ])("does not contact Anthropic at all for a message with %s", async (_name, content) => {
    const before = { requests: anthropicRequests, used: budgetStatus().used }
    const response = await ask(content)
    expect(response.status).toBe(400)
    expect(await response.json()).toMatchObject({ error: "sensitive_input" })
    expect(anthropicRequests).toBe(before.requests)
    expect(budgetStatus().used).toBe(before.used)
  }, 30_000)

  it("keeps nothing from the chat on disk", () => {
    const dir = path.dirname(process.env.CHAT_USAGE_FILE!)
    expect(fs.readdirSync(dir)).toEqual(["usage.json"])
    expect(fs.readFileSync(process.env.CHAT_USAGE_FILE!, "utf8")).not.toMatch(/create a job|poem|cats|GACS|hunter2|sk-ant/i)
  })
})
