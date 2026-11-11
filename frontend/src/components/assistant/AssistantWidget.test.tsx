// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { setAccessToken } from "@/lib/api"
import type { AssistantEvent } from "@/lib/assistant/protocol"
import { AssistantWidget, ASSISTANT_PROMPTS } from "./AssistantWidget"

// The widget and its hook run for real. Only the network is replaced: a fake fetch plays the part
// of /api/chat (newline-delimited events, as the route sends them) and of the token refresh.

interface ChatCall {
  headers: Headers
  payload: { messages: { role: string; content: string }[] }
  signal: AbortSignal | null | undefined
}

let chatCalls: ChatCall[]
let chatHandler: (call: ChatCall) => Response | Promise<Response>
let refreshHandler: () => Response

const line = (event: AssistantEvent) => `${JSON.stringify(event)}\n`
const answer = (...events: AssistantEvent[]) => new Response(events.map(line).join(""), { headers: { "Content-Type": "application/x-ndjson" } })
const text = (t: string): AssistantEvent => ({ type: "text", text: t })
const done: AssistantEvent = { type: "done", stop: "end" }
const refusedWith = (status: number, error: string, message: string, headers: Record<string, string> = {}, extra: object = {}) =>
  Response.json({ error, message, ...extra }, { status, headers })

/** A reply the test feeds by hand, and that ends when the browser aborts the request. */
function liveReply(signal: AbortSignal | null | undefined) {
  const encoder = new TextEncoder()
  let controller!: ReadableStreamDefaultController<Uint8Array>
  const body = new ReadableStream<Uint8Array>({
    start(c) {
      controller = c
      signal?.addEventListener("abort", () => {
        try {
          c.error(new DOMException("The operation was aborted.", "AbortError"))
        } catch {
          // Already closed.
        }
      })
    },
  })
  return {
    response: new Response(body, { headers: { "Content-Type": "application/x-ndjson" } }),
    push: (event: AssistantEvent) => controller.enqueue(encoder.encode(line(event))),
    end: () => controller.close(),
    breakConnection: () => controller.error(new Error("connection lost")),
  }
}

beforeEach(() => {
  chatCalls = []
  chatHandler = () => answer(text("Open **Jobs** and click **New job**."), done)
  refreshHandler = () => Response.json({ success: false }, { status: 401 })
  setAccessToken("token-1")
  vi.stubGlobal("fetch", async (input: string | URL | Request, init?: RequestInit) => {
    const url = typeof input === "string" ? input : input instanceof URL ? input.href : input.url
    if (url.endsWith("/api/chat")) {
      const call: ChatCall = { headers: new Headers(init?.headers), payload: JSON.parse(String(init?.body)), signal: init?.signal }
      chatCalls.push(call)
      return chatHandler(call)
    }
    if (url.endsWith("/api/auth/refresh")) return refreshHandler()
    throw new Error(`Unexpected request to ${url}`)
  })
})

afterEach(() => {
  cleanup()
  vi.useRealTimers()
  vi.unstubAllGlobals()
  setAccessToken(null)
})

async function openPanel(user = userEvent.setup()) {
  render(<AssistantWidget />)
  await user.click(screen.getByRole("button", { name: "Open GACS Assistant" }))
  return user
}
const box = () => screen.getByRole("textbox", { name: "Message GACS Assistant" }) as HTMLTextAreaElement
const sendButton = () => screen.getByRole("button", { name: "Send" }) as HTMLButtonElement

describe("opening and closing", () => {
  it("is a labelled, non-modal dialog that takes focus and gives it back on Escape", async () => {
    const user = userEvent.setup()
    await openPanel(user)
    const dialog = screen.getByRole("dialog", { name: "GACS Assistant" })
    expect(dialog.getAttribute("aria-modal")).toBe("false")
    await waitFor(() => expect(document.activeElement).toBe(box()))
    expect(screen.getByRole("log").getAttribute("aria-live")).toBe("polite")

    await user.keyboard("{Escape}")
    expect(screen.queryByRole("dialog")).toBeNull()
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Open GACS Assistant" }))
  })

  it("tells people the answers can be wrong and that redaction is best effort", async () => {
    await openPanel()
    expect(screen.getByText(/incomplete or wrong/)).toBeTruthy()
    expect(screen.getByText(/best-effort/)).toBeTruthy()
  })

  it("starts with the suggested questions", async () => {
    await openPanel()
    for (const prompt of ASSISTANT_PROMPTS) expect(screen.getByRole("button", { name: prompt })).toBeTruthy()
  })
})

describe("asking a question", () => {
  it("sends a suggested question with the user's access token and shows the answer", async () => {
    const user = await openPanel()
    await user.click(screen.getByRole("button", { name: "How do I create a job?" }))

    await screen.findByText("New job")
    expect(chatCalls).toHaveLength(1)
    expect(chatCalls[0].headers.get("Authorization")).toBe("Bearer token-1")
    expect(chatCalls[0].payload).toEqual({ messages: [{ role: "user", content: "How do I create a job?" }] })
    // Markdown is rendered, not shown raw.
    expect(screen.getByText("New job").tagName).toBe("STRONG")
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull()
    await waitFor(() => expect((sendButton() as HTMLButtonElement).disabled).toBe(true)) // nothing typed yet
  })

  it("shows the text as it streams, before the answer is finished", async () => {
    const reply = { current: undefined as ReturnType<typeof liveReply> | undefined }
    chatHandler = (call) => {
      reply.current = liveReply(call.signal)
      return reply.current.response
    }
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")

    await waitFor(() => expect(reply.current).toBeDefined())
    expect(screen.getByRole("log").getAttribute("aria-busy")).toBe("true")
    act(() => reply.current!.push(text("A reviewer ")))
    await screen.findByText(/A reviewer/)
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)

    act(() => {
      reply.current!.push(text("approves the plan."))
      reply.current!.push(done)
      reply.current!.end()
    })
    await screen.findByText(/A reviewer approves the plan\./)
    await waitFor(() => expect(screen.getByRole("log").getAttribute("aria-busy")).toBe("false"))
  })

  it("sends on Enter, keeps a new line on Shift+Enter, and ignores an empty box", async () => {
    const user = await openPanel()
    await user.type(box(), "first line{Shift>}{Enter}{/Shift}second line")
    expect(box().value).toBe("first line\nsecond line")
    expect(chatCalls).toHaveLength(0)

    await user.keyboard("{Enter}")
    await waitFor(() => expect(chatCalls).toHaveLength(1))
    expect(chatCalls[0].payload.messages[0].content).toBe("first line\nsecond line")
    expect(box().value).toBe("")

    await user.keyboard("{Enter}")
    expect(chatCalls).toHaveLength(1)
  })

  it("shows what will be removed while typing, and sends the redacted text", async () => {
    const user = await openPanel()
    await user.type(box(), "Why can jane@datacenter.io not approve?")
    expect(screen.getByText(/Will be removed before sending: email/)).toBeTruthy()

    await user.keyboard("{Enter}")
    await screen.findByText(/Removed before sending: email/)
    expect(chatCalls[0].payload.messages[0].content).toBe("Why can [REDACTED_EMAIL] not approve?")
    expect(JSON.stringify(chatCalls[0].payload)).not.toContain("jane@")
  })

  it("stops the text box at 1,000 characters", async () => {
    await openPanel()
    expect(box().maxLength).toBe(1000)
  })

  it("sends the earlier turns as history on the next question", async () => {
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")
    await screen.findByText("New job")
    await user.type(box(), "and for admins?{Enter}")
    await waitFor(() => expect(chatCalls).toHaveLength(2))
    expect(chatCalls[1].payload.messages.map((m) => m.role)).toEqual(["user", "assistant", "user"])
  })
})

describe("when the answer fails", () => {
  it("offers a retry after a stream that breaks, and the retry does not send the failed reply", async () => {
    chatHandler = () => answer(text("Open Jo"), { type: "error", code: "upstream", message: "Something went wrong. Try again." })
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")
    await screen.findByText("Something went wrong. Try again.")

    chatHandler = () => answer(text("Open **Jobs**."), done)
    await user.click(screen.getByRole("button", { name: "Retry" }))

    await screen.findByText("Jobs")
    expect(screen.queryByText("Something went wrong. Try again.")).toBeNull()
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull()
    expect(chatCalls).toHaveLength(2)
    expect(chatCalls[1].payload.messages).toEqual([{ role: "user", content: "How do I create a job?" }])
  })

  it("says so when the connection drops mid-answer", async () => {
    const reply = { current: undefined as ReturnType<typeof liveReply> | undefined }
    chatHandler = (call) => {
      reply.current = liveReply(call.signal)
      return reply.current.response
    }
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")
    await waitFor(() => expect(reply.current).toBeDefined())
    act(() => reply.current!.push(text("A reviewer")))
    await screen.findByText(/A reviewer/)
    act(() => reply.current!.breakConnection())

    await screen.findByText("Could not reach the assistant. Check your connection and try again.")
    expect((screen.getByRole("button", { name: "Retry" }) as HTMLButtonElement).disabled).toBe(false)
  })

  it("says so when the answer ends with no finish event", async () => {
    chatHandler = () => answer(text("Open Jo"))
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")
    await screen.findByText("The answer was cut off. Try again.")
  })

  it("shows a server error on the message, not as a blocker", async () => {
    chatHandler = () => refusedWith(502, "upstream", "Could not reach the assistant service. Try again.")
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")
    await screen.findByText("Could not reach the assistant service. Try again.")
    expect(screen.queryByRole("alert")).toBeNull()
    expect((box() as HTMLButtonElement).disabled).toBe(false)
  })

  it("shows a refusal gently", async () => {
    chatHandler = () => answer({ type: "done", stop: "refusal" })
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")
    await screen.findByText(/I can't help with that/)
  })
})

describe("an aborted request", () => {
  it("aborts the request when the chat is cleared mid-answer, and shows no error", async () => {
    const reply = { current: undefined as ReturnType<typeof liveReply> | undefined }
    chatHandler = (call) => {
      reply.current = liveReply(call.signal)
      return reply.current.response
    }
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")
    await waitFor(() => expect(reply.current).toBeDefined())
    act(() => reply.current!.push(text("A reviewer")))
    await screen.findByText(/A reviewer/)

    await user.click(screen.getByRole("button", { name: "Clear chat" }))

    expect(chatCalls[0].signal?.aborted).toBe(true)
    await waitFor(() => expect((screen.getByRole("button", { name: ASSISTANT_PROMPTS[0] }) as HTMLButtonElement).disabled).toBe(false))
    expect(screen.queryByText(/could not reach/i)).toBeNull()
    expect(screen.queryByRole("button", { name: "Retry" })).toBeNull()
    expect(screen.queryByText(/A reviewer/)).toBeNull()
  })

  it("aborts the request when the widget goes away", async () => {
    const reply = { current: undefined as ReturnType<typeof liveReply> | undefined }
    chatHandler = (call) => {
      reply.current = liveReply(call.signal)
      return reply.current.response
    }
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")
    await waitFor(() => expect(reply.current).toBeDefined())
    cleanup()
    expect(chatCalls[0].signal?.aborted).toBe(true)
  })
})

describe("clearing the chat", () => {
  it("empties the conversation and brings back the suggestions", async () => {
    const user = await openPanel()
    expect((screen.getByRole("button", { name: "Clear chat" }) as HTMLButtonElement).disabled).toBe(true)
    await user.type(box(), "How do approvals work?{Enter}")
    await screen.findByText("New job")

    await user.click(screen.getByRole("button", { name: "Clear chat" }))
    expect(screen.queryByText("New job")).toBeNull()
    expect((screen.getByRole("button", { name: ASSISTANT_PROMPTS[0] }) as HTMLButtonElement).disabled).toBe(false)
    expect((screen.getByRole("button", { name: "Clear chat" }) as HTMLButtonElement).disabled).toBe(true)

    // The next question starts from nothing: no old turns are sent.
    await user.type(box(), "What can admins change?{Enter}")
    await waitFor(() => expect(chatCalls).toHaveLength(2))
    expect(chatCalls[1].payload.messages).toEqual([{ role: "user", content: "What can admins change?" }])
  })
})

describe("a stop that does not end by itself", () => {
  const exhausted = () => refusedWith(429, "budget_exhausted", "The assistant has reached its usage limit.")

  it("blocks sending, with a message, and keeps blocking after the chat is cleared", async () => {
    chatHandler = exhausted
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")

    await screen.findByText("The assistant has reached its usage limit.")
    expect((box() as HTMLButtonElement).disabled).toBe(true)
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)

    await user.click(screen.getByRole("button", { name: "Clear chat" }))
    expect(screen.getByText("The assistant has reached its usage limit.")).toBeTruthy()
    expect((box() as HTMLButtonElement).disabled).toBe(true)
    for (const prompt of ASSISTANT_PROMPTS) expect((screen.getByRole("button", { name: prompt }) as HTMLButtonElement).disabled).toBe(true)
    expect(chatCalls).toHaveLength(1)
  })

  it("does not lift the stop after time passes", async () => {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    chatHandler = exhausted
    await openPanel(userEvent.setup({ advanceTimers: vi.advanceTimersByTime }))
    fireEvent.change(box(), { target: { value: "How do approvals work?" } })
    fireEvent.click(sendButton())
    await screen.findByText("The assistant has reached its usage limit.")

    act(() => vi.advanceTimersByTime(60 * 60 * 1000))
    expect(screen.getByText("The assistant has reached its usage limit.")).toBeTruthy()
    expect((box() as HTMLButtonElement).disabled).toBe(true)
  })

  it("stops for good when the assistant is not set up", async () => {
    chatHandler = () => refusedWith(503, "not_configured", "The assistant is not set up yet.")
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")
    await screen.findByText("The assistant is not set up yet.")
    expect((box() as HTMLButtonElement).disabled).toBe(true)
  })
})

describe("signing in", () => {
  it("sends no Authorization header, and stops, when there is no session", async () => {
    setAccessToken(null)
    chatHandler = () => refusedWith(401, "unauthorized", "Sign in to use the assistant.")
    const user = await openPanel()
    await user.type(box(), "How do approvals work?{Enter}")

    await screen.findByText("Sign in to use the assistant.")
    expect(chatCalls[0].headers.get("Authorization")).toBeNull()
    expect((box() as HTMLButtonElement).disabled).toBe(true)
  })

  it("refreshes an expired token once and sends the question again with the new one", async () => {
    chatHandler = (call) =>
      call.headers.get("Authorization") === "Bearer token-2"
        ? answer(text("Open **Jobs**."), done)
        : refusedWith(401, "unauthorized", "Sign in to use the assistant.")
    refreshHandler = () => Response.json({ success: true, accessToken: "token-2" })
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")

    await screen.findByText("Jobs")
    expect(chatCalls.map((c) => c.headers.get("Authorization"))).toEqual(["Bearer token-1", "Bearer token-2"])
    expect(screen.queryByRole("alert")).toBeNull()
  })
})

describe("a rate limit", () => {
  const limited = (seconds: number | null) => () =>
    refusedWith(429, "rate_limited", "Too many messages. Try again in 30 seconds.", seconds === null ? {} : { "Retry-After": String(seconds) })

  // The clock also follows real time (shouldAdvanceTime), which findBy* needs, so a slow machine
  // moves it a little on its own. The checks below keep several seconds of slack on both sides.
  const wait = (ms: number) => act(async () => { await vi.advanceTimersByTimeAsync(ms) })

  async function hitTheLimit(seconds: number | null) {
    vi.useFakeTimers({ shouldAdvanceTime: true })
    chatHandler = limited(seconds)
    await openPanel(userEvent.setup({ advanceTimers: vi.advanceTimersByTime }))
    fireEvent.change(box(), { target: { value: "How do approvals work?" } })
    fireEvent.click(sendButton())
    await screen.findByText("Too many messages. Try again in 30 seconds.")
  }

  it("pauses sending for the Retry-After time, then lets the user carry on", async () => {
    await hitTheLimit(30)
    fireEvent.change(box(), { target: { value: "Another question" } })
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)
    for (const prompt of ASSISTANT_PROMPTS) expect(screen.queryByRole("button", { name: prompt })).toSatisfy((b) => b === null || (b as HTMLButtonElement).disabled)

    // Enter during the wait sends nothing.
    fireEvent.keyDown(box(), { key: "Enter" })
    expect(chatCalls).toHaveLength(1)

    await wait(20_000)
    expect(screen.getByText("Too many messages. Try again in 30 seconds.")).toBeTruthy()
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)

    await wait(15_000)
    expect(screen.queryByText("Too many messages. Try again in 30 seconds.")).toBeNull()
    expect((sendButton() as HTMLButtonElement).disabled).toBe(false)

    chatHandler = () => answer(text("Open **Jobs**."), done)
    fireEvent.click(sendButton())
    await screen.findByText("Jobs")
    expect(chatCalls).toHaveLength(2)
  })

  it("waits a default minute when the server gives no Retry-After", async () => {
    await hitTheLimit(null)
    await wait(50_000)
    expect(screen.getByText("Too many messages. Try again in 30 seconds.")).toBeTruthy()
    await wait(15_000)
    expect(screen.queryByText("Too many messages. Try again in 30 seconds.")).toBeNull()
  })

  it("is not skipped by clearing the chat", async () => {
    await hitTheLimit(30)
    fireEvent.click(screen.getByRole("button", { name: "Clear chat" }))
    expect(screen.getByText("Too many messages. Try again in 30 seconds.")).toBeTruthy()
    fireEvent.change(box(), { target: { value: "Another question" } })
    expect((sendButton() as HTMLButtonElement).disabled).toBe(true)
  })

  it("does not stop the text box: the user can keep writing while they wait", async () => {
    await hitTheLimit(30)
    expect((box() as HTMLButtonElement).disabled).toBe(false)
  })
})

describe("a password, key or token in the box", () => {
  it("is never sent: the box keeps it, a warning says why, and sending is off", async () => {
    const user = await openPanel()
    await user.type(box(), "Why is login failing? password: hunter2")

    const warning = screen.getByRole("status")
    expect(warning.textContent).toMatch(/Remove the password or secret to send this/)
    expect(sendButton().disabled).toBe(true)
    expect(screen.queryByText(/Will be removed before sending/)).toBeNull()

    await user.keyboard("{Enter}")
    expect(chatCalls).toHaveLength(0)
    expect(box().value).toBe("Why is login failing? password: hunter2")
  })

  it("can be sent once the credential is taken out", async () => {
    const user = await openPanel()
    await user.type(box(), "How do I create a job? password: hunter2")
    expect(sendButton().disabled).toBe(true)

    await user.clear(box())
    await user.type(box(), "How do I create a job?")
    expect(screen.queryByRole("status")).toBeNull()
    expect(sendButton().disabled).toBe(false)
    await user.keyboard("{Enter}")
    await waitFor(() => expect(chatCalls).toHaveLength(1))
  })

  it.each([
    ["an API key", "key sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456", /API key/],
    ["a login in a link", "postgres://gacs:s3cretPass@db.internal/gacs", /login in a link/],
  ])("warns about %s", async (_name, typed, label) => {
    const user = await openPanel()
    await user.type(box(), typed)
    expect(screen.getByRole("status").textContent).toMatch(label)
    expect(sendButton().disabled).toBe(true)
  })

  it("still just removes personal details, and sends", async () => {
    const user = await openPanel()
    await user.type(box(), "Why can jane@datacenter.io not approve?")
    expect(screen.queryByRole("status")).toBeNull()
    expect(sendButton().disabled).toBe(false)
  })

  it("uses the same warning look as the other notices: an Alert with an icon and text", async () => {
    const user = await openPanel()
    await user.type(box(), "password: hunter2")
    const warning = screen.getByRole("status")
    expect(warning.className).toMatch(/border-warning-foreground\/40 bg-warning\/10 text-warning-foreground/)
    expect(warning.querySelector("svg[aria-hidden='true']")).not.toBeNull()
    expect(warning.textContent!.length).toBeGreaterThan(10)
  })
})

describe("nothing from the chat is kept in the browser", () => {
  it("leaves local and session storage free of what was asked and answered", async () => {
    const user = await openPanel()
    await user.type(box(), "How do I create a job? zebra-quartz-77{Enter}")
    await screen.findByText("New job")

    const stored = [localStorage, sessionStorage].flatMap((store) => Object.keys(store).map((key) => `${key}=${store.getItem(key)}`)).join("\n")
    expect(stored).not.toMatch(/zebra|quartz|create a job|New job/i)
    expect(document.cookie).not.toMatch(/zebra|quartz/i)
  })

  it("starts empty after the widget is mounted again", async () => {
    const user = await openPanel()
    await user.type(box(), "How do I create a job?{Enter}")
    await screen.findByText("New job")
    cleanup()
    await openPanel()
    expect(screen.queryByText("New job")).toBeNull()
    expect(screen.getByRole("button", { name: ASSISTANT_PROMPTS[0] })).toBeTruthy()
  })
})

