"use client"

import * as React from "react"
import { authApi, getAccessToken } from "@/lib/api"
import type { AssistantErrorBody, AssistantErrorCode, AssistantEvent } from "@/lib/assistant/protocol"
import { cleanUserMessage, credentialTypes, type RedactionType } from "@/lib/assistant/redact"
import { MAX_HISTORY_MESSAGES } from "@/lib/assistant/validate"

export interface ChatMessage {
  id: string
  role: "user" | "assistant"
  /** For user messages, the redacted text: what was actually sent. */
  content: string
  redacted?: RedactionType[]
  state?: "streaming" | "done" | "truncated" | "refusal" | "error"
  error?: string
}

/** Conditions that stop the whole chat, shown once above the input instead of on a message. */
export interface ChatBlocker {
  code: Extract<AssistantErrorCode, "unauthorized" | "rate_limited" | "budget_exhausted" | "not_configured">
  message: string
  /**
   * Only for "rate_limited", which is a pause: when sending is allowed again (ms since epoch).
   * The other blockers do not end by themselves.
   */
  retryAt?: number
}

/** Used when a rate-limit answer carries no Retry-After. */
const DEFAULT_COOLDOWN_SECONDS = 60
const MAX_COOLDOWN_SECONDS = 15 * 60

function cooldownSeconds(header: string | null, body: number | undefined): number {
  const fromHeader = header !== null && /^\d+$/.test(header.trim()) ? Number(header) : undefined
  const seconds = fromHeader ?? body ?? DEFAULT_COOLDOWN_SECONDS
  return Math.min(MAX_COOLDOWN_SECONDS, Math.max(1, Math.ceil(seconds)))
}

/** A fresh access token from the refresh cookie, or null when the session is over. */
async function refreshedAccessToken(): Promise<string | null> {
  try {
    return (await authApi.refreshToken()).accessToken ?? null
  } catch {
    return null
  }
}

/**
 * Posts the conversation with the user's real access token: the server checks it with the backend.
 * An expired token is refreshed once and the request is sent again.
 */
async function postChat(payload: unknown, signal: AbortSignal): Promise<Response> {
  const post = (token: string | null) =>
    fetch("/api/chat", {
      method: "POST",
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      credentials: "same-origin",
      body: JSON.stringify(payload),
      signal,
    })
  const token = getAccessToken() ?? (await refreshedAccessToken())
  const response = await post(token)
  if (response.status !== 401) return response
  const fresh = await refreshedAccessToken()
  return fresh && fresh !== token ? post(fresh) : response
}

const BLOCKING: ReadonlySet<AssistantErrorCode> = new Set(["unauthorized", "rate_limited", "budget_exhausted", "not_configured"])

let sequence = 0
const nextId = () => `msg-${++sequence}`

/** Only completed turns are sent back as history; failed replies are left out. */
function toPayload(history: ChatMessage[]) {
  return history
    .filter((m) => m.role === "user" || m.state === "done" || m.state === "truncated")
    .filter((m) => m.content.trim().length > 0)
    .slice(-MAX_HISTORY_MESSAGES)
    .map(({ role, content }) => ({ role, content }))
}

/**
 * The conversation lives only in this hook's state. Nothing is written to storage, so it is gone
 * when the page reloads.
 */
export function useAssistantChat() {
  const [messages, setMessages] = React.useState<ChatMessage[]>([])
  const [blocker, setBlocker] = React.useState<ChatBlocker | null>(null)
  const [busy, setBusy] = React.useState(false)
  const messagesRef = React.useRef<ChatMessage[]>([])
  const abortRef = React.useRef<AbortController | null>(null)
  const blockerRef = React.useRef<ChatBlocker | null>(null)

  React.useEffect(() => {
    messagesRef.current = messages
  }, [messages])
  const cooldownTimer = React.useRef<number | null>(null)

  React.useEffect(
    () => () => {
      abortRef.current?.abort()
      if (cooldownTimer.current !== null) window.clearTimeout(cooldownTimer.current)
    },
    [],
  )

  /**
   * Sets or lifts the blocker. A rate limit is a pause, so it also starts its timer here, at the
   * moment the limit is applied, and lifts itself when the wait is over. It is not left to an
   * effect: when React happens to run an effect would then decide when the wait starts. Budget and
   * set-up blockers have no timer and stay until the page is reloaded or an admin acts.
   */
  const applyBlocker = React.useCallback((next: ChatBlocker | null) => {
    blockerRef.current = next
    setBlocker(next)
    if (cooldownTimer.current !== null) {
      window.clearTimeout(cooldownTimer.current)
      cooldownTimer.current = null
    }
    if (next?.code === "rate_limited" && next.retryAt !== undefined) {
      cooldownTimer.current = window.setTimeout(() => {
        cooldownTimer.current = null
        if (blockerRef.current?.code === "rate_limited") applyBlocker(null)
      }, Math.max(0, next.retryAt - Date.now()))
    }
  }, [])

  const run = React.useCallback(async (history: ChatMessage[]) => {
    const replyId = nextId()
    const withReply: ChatMessage[] = [...history, { id: replyId, role: "assistant", content: "", state: "streaming" }]
    messagesRef.current = withReply
    setMessages(withReply)
    setBusy(true)
    applyBlocker(null)
    const controller = new AbortController()
    abortRef.current = controller
    const update = (patch: (m: ChatMessage) => Partial<ChatMessage>) =>
      setMessages((prev) => prev.map((m) => (m.id === replyId ? { ...m, ...patch(m) } : m)))

    try {
      const response = await postChat({ messages: toPayload(history) }, controller.signal)
      if (!response.ok || !response.body) {
        const data = (await response.json().catch(() => null)) as AssistantErrorBody | null
        const code = data?.error ?? "upstream"
        const message = data?.message ?? "Something went wrong. Try again."
        if (BLOCKING.has(code)) {
          const retryAt =
            code === "rate_limited"
              ? Date.now() + cooldownSeconds(response.headers.get("Retry-After"), data?.retryAfterSeconds) * 1000
              : undefined
          applyBlocker({ code: code as ChatBlocker["code"], message, ...(retryAt ? { retryAt } : {}) })
          setMessages((prev) => prev.filter((m) => m.id !== replyId))
        } else {
          update(() => ({ state: "error", error: message }))
        }
        return
      }

      const reader = response.body.getReader()
      const decoder = new TextDecoder()
      let buffer = ""
      let finished = false
      for (;;) {
        const { value, done } = await reader.read()
        if (done) break
        buffer += decoder.decode(value, { stream: true })
        let newline: number
        while ((newline = buffer.indexOf("\n")) >= 0) {
          const line = buffer.slice(0, newline).trim()
          buffer = buffer.slice(newline + 1)
          if (!line) continue
          let event: AssistantEvent
          try {
            event = JSON.parse(line) as AssistantEvent
          } catch {
            continue
          }
          if (event.type === "text") {
            const text = event.text
            update((m) => ({ content: m.content + text }))
          } else if (event.type === "done") {
            finished = true
            const state = event.stop === "refusal" ? "refusal" : event.stop === "max_tokens" ? "truncated" : "done"
            update(() => ({ state }))
          } else if (event.type === "error") {
            finished = true
            if (BLOCKING.has(event.code)) {
              const retryAt = event.code === "rate_limited" ? Date.now() + DEFAULT_COOLDOWN_SECONDS * 1000 : undefined
              applyBlocker({ code: event.code as ChatBlocker["code"], message: event.message, ...(retryAt ? { retryAt } : {}) })
            }
            update(() => ({ state: "error", error: event.message }))
          }
        }
      }
      if (!finished) update(() => ({ state: "error", error: "The answer was cut off. Try again." }))
    } catch {
      if (controller.signal.aborted) return
      update(() => ({ state: "error", error: "Could not reach the assistant. Check your connection and try again." }))
    } finally {
      if (abortRef.current === controller) {
        abortRef.current = null
        setBusy(false)
      }
    }
  }, [applyBlocker])

  /** Cleans and redacts the text, then sends it. Returns false when there was nothing to send or it held a credential. */
  const send = React.useCallback(
    (raw: string): boolean => {
      if (abortRef.current || blockerRef.current?.code === "rate_limited") return false
      const { text, types } = cleanUserMessage(raw)
      if (!text) return false
      // A password, key or token is never sent, not even redacted: the widget tells the person why.
      if (credentialTypes(types).length > 0) return false
      const user: ChatMessage = { id: nextId(), role: "user", content: text, redacted: types }
      void run([...messagesRef.current, user])
      return true
    },
    [run],
  )

  /** Asks again for the last failed answer. */
  const retry = React.useCallback(() => {
    if (abortRef.current || blockerRef.current?.code === "rate_limited") return
    const current = messagesRef.current
    const last = current[current.length - 1]
    if (!last || last.role !== "assistant" || last.state !== "error") return
    void run(current.slice(0, -1))
  }, [run])

  const clear = React.useCallback(() => {
    abortRef.current?.abort()
    abortRef.current = null
    messagesRef.current = []
    setMessages([])
    setBusy(false)
    // Clearing the chat does not lift a stop, nor skip a rate-limit wait.
    const code = blockerRef.current?.code
    if (code !== "budget_exhausted" && code !== "not_configured" && code !== "rate_limited") applyBlocker(null)
  }, [applyBlocker])

  /** True while a rate-limit wait is running: sending is paused, not stopped for good. */
  const cooling = blocker?.code === "rate_limited"

  return { messages, blocker, busy, cooling, send, retry, clear }
}
