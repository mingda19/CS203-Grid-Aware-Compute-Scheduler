import { cleanUserMessage, credentialTypes, MAX_CLEAN_CHARS, MAX_MESSAGE_CHARS, typedLength, type RedactionType } from "./redact"

/** Only the most recent messages are sent; older ones add cost and slow the reply. */
export const MAX_HISTORY_MESSAGES = 10
/** The assistant's own replies are capped by max_tokens, so this only stops a forged client. */
export const MAX_ASSISTANT_CHARS = 6000
const MAX_INCOMING_MESSAGES = 50

export interface ChatTurn {
  role: "user" | "assistant"
  content: string
}

export type ParseResult =
  | { ok: true; messages: ChatTurn[]; redacted: RedactionType[] }
  // `sensitive` marks a message that holds a password, key or token: refused, never sent on.
  | { ok: false; error: string; sensitive?: true }

export const SENSITIVE_INPUT_MESSAGE =
  "That message looks like it contains a password, key or token, so it was not sent. Remove it and try again."

/**
 * Checks the request body and returns the messages that may be sent to the model: well formed,
 * recent, redacted, starting and ending with the user. Anything but role and content is dropped.
 *
 * Length rules for a user message (the input box stops typing at MAX_MESSAGE_CHARS):
 * 1. The text as sent must be at most MAX_CLEAN_CHARS. This bounds the work done below.
 * 2. Its length once normalised and redacted, with each placeholder counted as one character
 *    (`typedLength`), must be at most MAX_MESSAGE_CHARS. This is the documented limit, enforced
 *    here so a modified client cannot exceed it.
 * 3. Redaction may then make the text longer (a placeholder is wider than an IPv4 address), up to
 *    MAX_CLEAN_CHARS.
 *
 * A user message that holds a credential (password, key, token, private key, login in a link) is
 * refused as a whole. Nothing is sent to the model, so no tokens are spent on it.
 */
export function parseChatRequest(body: unknown): ParseResult {
  if (typeof body !== "object" || body === null || !Array.isArray((body as { messages?: unknown }).messages)) {
    return { ok: false, error: "Expected { messages: [...] }." }
  }
  const raw = (body as { messages: unknown[] }).messages
  if (raw.length === 0) return { ok: false, error: "No messages." }
  if (raw.length > MAX_INCOMING_MESSAGES) return { ok: false, error: "Too many messages." }

  const turns: ChatTurn[] = []
  for (const item of raw) {
    if (typeof item !== "object" || item === null) return { ok: false, error: "Each message must be an object." }
    const { role, content } = item as { role?: unknown; content?: unknown }
    if (role !== "user" && role !== "assistant") return { ok: false, error: "Role must be user or assistant." }
    if (typeof content !== "string") return { ok: false, error: "Content must be text." }
    if (content.length > (role === "user" ? MAX_CLEAN_CHARS : MAX_ASSISTANT_CHARS)) {
      return { ok: false, error: "A message is too long." }
    }
    if (role === "user" && typedLength(content) > MAX_MESSAGE_CHARS) {
      return { ok: false, error: `Messages can be at most ${MAX_MESSAGE_CHARS} characters.` }
    }
    turns.push({ role, content })
  }

  let recent = turns.slice(-MAX_HISTORY_MESSAGES)
  // The API needs the first message to be the user's.
  while (recent.length > 0 && recent[0].role !== "user") recent = recent.slice(1)
  if (recent.length === 0 || recent[recent.length - 1].role !== "user") {
    return { ok: false, error: "The last message must be the user's." }
  }

  // Redact every turn, not just the newest: a modified client could put anything in history.
  const redacted: RedactionType[] = []
  const messages: ChatTurn[] = []
  for (const turn of recent) {
    const clean = cleanUserMessage(turn.content, turn.role === "user" ? MAX_CLEAN_CHARS : MAX_ASSISTANT_CHARS)
    if (turn.role === "user" && credentialTypes(clean.types).length > 0) {
      return { ok: false, error: SENSITIVE_INPUT_MESSAGE, sensitive: true }
    }
    for (const type of clean.types) if (!redacted.includes(type)) redacted.push(type)
    if (clean.text.length === 0) {
      if (turn.role === "user") return { ok: false, error: "A message is empty." }
      continue
    }
    messages.push({ role: turn.role, content: clean.text })
  }
  return { ok: true, messages, redacted }
}
