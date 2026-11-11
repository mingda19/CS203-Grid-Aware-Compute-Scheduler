import Anthropic from "@anthropic-ai/sdk"
import type { NextRequest } from "next/server"
import { verifySession } from "@/lib/assistant/auth"
import { readBodyLimited } from "@/lib/assistant/body"
import { budgetStatus, inputTokenUpperBound, reserve, settle, totalTokens, type UsageLike } from "@/lib/assistant/budget"
import { ASSISTANT_SYSTEM_PROMPT } from "@/lib/assistant/knowledge"
import type { AssistantErrorBody, AssistantErrorCode, AssistantEvent } from "@/lib/assistant/protocol"
import { checkRate } from "@/lib/assistant/rate-limit"
import { isOnTopic, OFF_TOPIC_REPLY } from "@/lib/assistant/topic"
import { parseChatRequest } from "@/lib/assistant/validate"

/**
 * The in-app assistant. It forwards a redacted conversation to Claude and streams the reply.
 * The only thing it asks the Spring backend is who the caller is (GET /api/auth/me, to verify the
 * bearer token). It never touches the database, and it never stores or logs message text.
 */

// Node, not Edge: the token budget is saved to a file.
export const runtime = "nodejs"

const MODEL = "claude-haiku-5-5"
// Answers are meant to be short; this also bounds the cost of each call.
const MAX_OUTPUT_TOKENS = 800
// Bytes, enforced while the body is read.
const MAX_BODY_BYTES = 64 * 1024
// The SDK retries a failed request this many times. Each retry could be billed, so the budget
// reserves for every attempt. With 0, a failed call is simply reported and the user retries.
const MAX_RETRIES = 0

let client: Anthropic | null = null
function anthropic(apiKey: string): Anthropic {
  // The key is passed in explicitly so the SDK never falls back to a developer's local login.
  client ??= new Anthropic({ apiKey, maxRetries: MAX_RETRIES, timeout: 30_000 })
  return client
}

function fail(status: number, error: AssistantErrorCode, message: string, retryAfterSeconds?: number): Response {
  const body: AssistantErrorBody = { error, message, ...(retryAfterSeconds ? { retryAfterSeconds } : {}) }
  const headers: Record<string, string> = { "Cache-Control": "no-store" }
  if (retryAfterSeconds) headers["Retry-After"] = String(retryAfterSeconds)
  return Response.json(body, { status, headers })
}

const STREAM_HEADERS = {
  "Content-Type": "application/x-ndjson; charset=utf-8",
  "Cache-Control": "no-store",
  "X-Accel-Buffering": "no",
}

/** A complete answer in the same shape as a streamed one, for replies that never reach the model. */
function fixedReply(text: string): Response {
  const events: AssistantEvent[] = [{ type: "text", text }, { type: "done", stop: "end" }]
  return new Response(events.map((event) => `${JSON.stringify(event)}\n`).join(""), { headers: STREAM_HEADERS })
}

function upstreamMessage(error: unknown): { code: AssistantErrorCode; message: string } {
  if (error instanceof Anthropic.AuthenticationError || error instanceof Anthropic.PermissionDeniedError) {
    return { code: "not_configured", message: "The assistant's API key was not accepted." }
  }
  if (error instanceof Anthropic.RateLimitError) {
    return { code: "upstream", message: "The assistant is busy. Try again in a moment." }
  }
  if (error instanceof Anthropic.APIConnectionError) {
    return { code: "upstream", message: "Could not reach the assistant service. Try again." }
  }
  return { code: "upstream", message: "Something went wrong. Try again." }
}

export async function POST(request: NextRequest): Promise<Response> {
  // /api routes are outside the login redirect, and the gacs_logged_in cookie is only a hint that
  // browser code writes, so the bearer token is verified with the backend instead.
  const session = await verifySession(request.headers.get("authorization"))
  if (!session.ok) {
    if (session.reason === "unauthorized") return fail(401, "unauthorized", "Sign in to use the assistant.")
    if (session.reason === "not_configured") return fail(503, "not_configured", "The assistant is not set up yet.")
    return fail(503, "upstream", "Could not check your sign-in. Try again in a moment.")
  }
  // Keyed by the verified user, not by an address the caller can choose.
  const rate = checkRate(`user:${session.userId}`)
  if (!rate.allowed) {
    return fail(429, "rate_limited", `Too many messages. Try again in ${rate.retryAfterSeconds} seconds.`, rate.retryAfterSeconds)
  }
  const apiKey = process.env.ANTHROPIC_API_KEY
  if (!apiKey) return fail(503, "not_configured", "The assistant is not set up yet.")

  const read = await readBodyLimited(request, MAX_BODY_BYTES)
  if (!read.ok) {
    return read.reason === "too_large"
      ? fail(413, "invalid_request", "The conversation is too long.")
      : fail(400, "invalid_request", "The request could not be read.")
  }
  let body: unknown
  try {
    body = JSON.parse(read.text)
  } catch {
    return fail(400, "invalid_request", "The request could not be read.")
  }
  const parsed = parseChatRequest(body)
  // A credential in a message, or a malformed request: refused here, before the model is reached.
  if (!parsed.ok) return fail(400, parsed.sensitive ? "sensitive_input" : "invalid_request", parsed.error)

  // Nothing about GACS in the conversation: answer without spending tokens.
  if (!isOnTopic(parsed.messages)) return fixedReply(OFF_TOPIC_REPLY)

  // A conservative ceiling for every attempt the SDK may make, replaced by real usage afterwards.
  const perAttempt = inputTokenUpperBound([ASSISTANT_SYSTEM_PROMPT, ...parsed.messages.map((m) => m.content)]) + MAX_OUTPUT_TOKENS
  const reservation = reserve(perAttempt * (1 + MAX_RETRIES))
  if (!reservation) {
    return fail(429, "budget_exhausted", "The assistant has reached its usage limit.")
  }

  const upstream = new AbortController()
  request.signal.addEventListener("abort", () => upstream.abort())
  const encoder = new TextEncoder()

  const stream = new ReadableStream<Uint8Array>({
    async start(controller) {
      const send = (event: AssistantEvent) => {
        try {
          controller.enqueue(encoder.encode(`${JSON.stringify(event)}\n`))
        } catch {
          // The browser went away; the abort handler stops the upstream call.
        }
      }
      // Usage arrives in pieces (message_start, then message_delta); keep the largest of each.
      const seen: Required<{ [K in keyof UsageLike]: number }> = {
        input_tokens: 0, output_tokens: 0, cache_creation_input_tokens: 0, cache_read_input_tokens: 0,
      }
      let gotUsage = false
      const record = (usage: UsageLike | null | undefined) => {
        if (!usage) return
        gotUsage = true
        for (const key of Object.keys(seen) as (keyof typeof seen)[]) seen[key] = Math.max(seen[key], usage[key] ?? 0)
      }

      try {
        const reply = anthropic(apiKey).messages.stream(
          {
            model: MODEL,
            max_tokens: MAX_OUTPUT_TOKENS,
            // Speed: no thinking, lowest effort. Haiku 5.5 rejects temperature, so none is sent.
            thinking: { type: "disabled" },
            output_config: { effort: "low" },
            // The knowledge base is identical on every call, so it is cached.
            system: [{ type: "text", text: ASSISTANT_SYSTEM_PROMPT, cache_control: { type: "ephemeral" } }],
            messages: parsed.messages,
          },
          { signal: upstream.signal },
        )
        for await (const event of reply) {
          if (event.type === "message_start") record(event.message.usage)
          else if (event.type === "message_delta") record(event.usage)
          else if (event.type === "content_block_delta" && event.delta.type === "text_delta") {
            send({ type: "text", text: event.delta.text })
          }
        }
        const final = await reply.finalMessage()
        record(final.usage)
        settle(reservation, totalTokens(seen))
        // Token counts only, never message text: enough to watch spend and cache hits.
        const { used, limit } = budgetStatus()
        console.info(
          `[assistant] tokens in=${seen.input_tokens} cache_write=${seen.cache_creation_input_tokens} ` +
            `cache_read=${seen.cache_read_input_tokens} out=${seen.output_tokens} stop=${final.stop_reason} ` +
            `budget=${used}/${limit ?? "none"}`,
        )
        const stop = final.stop_reason === "refusal" ? "refusal" : final.stop_reason === "max_tokens" ? "max_tokens" : "end"
        send({ type: "done", stop })
      } catch (error) {
        // Refused before generating anything: nothing was spent. Cut off mid-reply: count the input
        // plus the whole output allowance. Nothing known: keep the full reservation as spent.
        const rejectedUpfront = error instanceof Anthropic.APIError && typeof error.status === "number" && !gotUsage
        const partial = totalTokens({ ...seen, output_tokens: 0 }) + MAX_OUTPUT_TOKENS
        settle(reservation, rejectedUpfront ? 0 : gotUsage ? partial : null)
        if (!upstream.signal.aborted) {
          // Log the kind of failure only, never the conversation.
          const status = error instanceof Anthropic.APIError ? error.status : undefined
          console.error(`[assistant] ${error instanceof Error ? error.constructor.name : "Error"}${status ? ` ${status}` : ""}`)
          send({ type: "error", ...upstreamMessage(error) })
        }
      } finally {
        try {
          controller.close()
        } catch {
          // Already closed by the browser.
        }
      }
    },
    cancel() {
      upstream.abort()
    },
  })

  return new Response(stream, { headers: STREAM_HEADERS })
}
