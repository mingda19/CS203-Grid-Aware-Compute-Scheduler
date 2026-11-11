"use client"

import * as React from "react"
import type { AssistantErrorBody, AssistantErrorCode, AssistantEvent } from "@/lib/assistant/protocol"
import { cleanUserMessage, type RedactionType } from "@/lib/assistant/redact"
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

  React.useEffect(() => {
    messagesRef.current = messages
  }, [messages])

  React.useEffect(() => () => abortRef.current?.abort(), [])

  const run = React.useCallback(async (history: ChatMessage[]) => {
    const replyId = nextId()
    const withReply: ChatMessage[] = [...history, { id: replyId, role: "assistant", content: "", state: "streaming" }]
    messagesRef.current = withReply
    setMessages(withReply)
    setBusy(true)
    setBlocker(null)
    const controller = new AbortController()
    abortRef.current = controller
    const update = (patch: (m: ChatMessage) => Partial<ChatMessage>) =>
      setMessages((prev) => prev.map((m) => (m.id === replyId ? { ...m, ...patch(m) } : m)))

    try {
      const response = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        credentials: "same-origin",
        body: JSON.stringify({ messages: toPayload(history) }),
        signal: controller.signal,
      })
      if (!response.ok || !response.body) {
        const data = (await response.json().catch(() => null)) as AssistantErrorBody | null
        const code = data?.error ?? "upstream"
        const message = data?.message ?? "Something went wrong. Try again."
        if (BLOCKING.has(code)) {
          setBlocker({ code: code as ChatBlocker["code"], message })
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
            if (BLOCKING.has(event.code)) setBlocker({ code: event.code as ChatBlocker["code"], message: event.message })
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
  }, [])

  /** Cleans and redacts the text, then sends it. Returns false when there was nothing to send. */
  const send = React.useCallback(
    (raw: string): boolean => {
      if (abortRef.current) return false
      const { text, types } = cleanUserMessage(raw)
      if (!text) return false
      const user: ChatMessage = { id: nextId(), role: "user", content: text, redacted: types }
      void run([...messagesRef.current, user])
      return true
    },
    [run],
  )

  /** Asks again for the last failed answer. */
  const retry = React.useCallback(() => {
    if (abortRef.current) return
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
    setBlocker((current) => (current?.code === "budget_exhausted" || current?.code === "not_configured" ? current : null))
  }, [])

  return { messages, blocker, busy, send, retry, clear }
}
