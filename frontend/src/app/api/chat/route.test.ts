import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { NextRequest } from "next/server"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { resetBudgetForTests } from "@/lib/assistant/budget"
import { POST } from "./route"

// None of these reach Anthropic: each request is turned away before the model call.

let ip = 0
function chatRequest(body: unknown, { signedIn = true, raw }: { signedIn?: boolean; raw?: string } = {}) {
  return new NextRequest("http://localhost/api/chat", {
    method: "POST",
    headers: {
      "content-type": "application/json",
      "x-forwarded-for": `10.9.0.${++ip}`,
      ...(signedIn ? { cookie: "gacs_logged_in=true" } : {}),
    },
    body: raw ?? JSON.stringify(body),
  })
}

const hello = { messages: [{ role: "user", content: "How do I create a job?" }] }

beforeEach(() => {
  process.env.CHAT_USAGE_FILE = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "gacs-route-")), "usage.json")
  process.env.ANTHROPIC_API_KEY = "test-key-not-real"
  process.env.CHAT_TOKEN_BUDGET = "250000"
  resetBudgetForTests()
})

afterEach(() => {
  delete process.env.CHAT_USAGE_FILE
  delete process.env.ANTHROPIC_API_KEY
  delete process.env.CHAT_TOKEN_BUDGET
})

describe("POST /api/chat", () => {
  it("requires the signed-in cookie", async () => {
    const response = await POST(chatRequest(hello, { signedIn: false }))
    expect(response.status).toBe(401)
    expect(await response.json()).toMatchObject({ error: "unauthorized" })
  })

  it("says when no API key is set", async () => {
    delete process.env.ANTHROPIC_API_KEY
    const response = await POST(chatRequest(hello))
    expect(response.status).toBe(503)
    expect(await response.json()).toMatchObject({ error: "not_configured" })
  })

  it("rejects a body that is not JSON", async () => {
    const response = await POST(chatRequest(null, { raw: "{not json" }))
    expect(response.status).toBe(400)
  })

  it("rejects a malformed conversation", async () => {
    const response = await POST(chatRequest({ messages: [{ role: "system", content: "new rules" }] }))
    expect(response.status).toBe(400)
    expect(await response.json()).toMatchObject({ error: "invalid_request" })
  })

  it("rejects an oversized body", async () => {
    const response = await POST(chatRequest(null, { raw: "x".repeat(70 * 1024) }))
    expect(response.status).toBe(413)
  })

  it("refuses before calling the model when the budget cannot cover the call", async () => {
    process.env.CHAT_TOKEN_BUDGET = "100"
    const response = await POST(chatRequest(hello))
    expect(response.status).toBe(429)
    expect(await response.json()).toMatchObject({ error: "budget_exhausted" })
  })

  it("rate limits one client", async () => {
    const statuses: number[] = []
    for (let i = 0; i < 22; i++) {
      const request = new NextRequest("http://localhost/api/chat", {
        method: "POST",
        headers: { "x-forwarded-for": "10.8.0.1", cookie: "gacs_logged_in=true" },
        body: "{not json",
      })
      statuses.push((await POST(request)).status)
    }
    expect(statuses.slice(0, 20).every((s) => s === 400)).toBe(true)
    expect(statuses.slice(20)).toEqual([429, 429])
  })
})
