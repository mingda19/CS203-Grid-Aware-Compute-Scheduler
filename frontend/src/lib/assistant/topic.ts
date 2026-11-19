import { looksLikeOverride } from "./injection"
import type { ChatTurn } from "./validate"

/**
 * A strict topic gate that runs before any model call. A message goes to the model only when all of
 * it is about GACS; otherwise the server answers with a fixed reply and spends no tokens.
 *
 * How: the latest user message is cut into sentences and clauses ("a. b?", "... and what is ...",
 * "... also ..."). Every piece has to mention something GACS-related (or be a greeting, or a short
 * follow-up that clearly refers back to what was said, such as "and for admins?" or "why is that?").
 * A piece about something else, a request for a poem or translation, or an attempt to change the
 * assistant's rules, turns the whole message away, even if the rest is about GACS. The person can
 * send the GACS part again on its own.
 *
 * It will sometimes turn away a harmless message (a friendly remark with no GACS word in it). That
 * is the intended trade: the bot only answers questions about GACS.
 *
 * What it is not: proof the model stays on topic. It is a word list, so an unrelated request that
 * happens to use a GACS word ("plan my holiday") passes. Only the user's own messages count: an
 * assistant message in the history never makes a conversation on topic, because the browser
 * supplies it. The system prompt (knowledge.ts) is the second line of defence, and is also only an
 * instruction. A model-based classifier is the next step if this must be airtight.
 */

const GACS_VOCABULARY = new RegExp(
  String.raw`\b(?:` +
    [
      // The product and its data
      "gacs", "grid", "ercot", "texas", "electric\\w*", "power", "energy", "price\\w*", "pricing", "forecast\\w*", "mwh", "kwh", "\\$\\s?\\d*\\s?/?\\s?mwh",
      // Jobs, plans and approvals
      "jobs?", "workloads?", "schedul\\w*", "plans?", "planning", "planner", "approv\\w*", "reject\\w*", "revis\\w*", "recompute\\w*", "review\\w*",
      "flexib\\w*", "flexible", "lock(?:ed|ing)?", "exempt", "deadline", "window", "priority", "template", "constraint\\w*", "parse",
      "cpu", "gpu", "hours?", "capacity", "facility", "data ?cent(?:er|re)s?", "saving\\w*", "saves?", "cost\\w*", "cheap\\w*", "expensive",
      "status", "needs attention", "scheduling", "running", "completed", "cancel\\w*", "delete[sd]?", "create[sd]?", "edit\\w*",
      // Where things are in the app
      "audit", "admin\\w*", "notification\\w*", "bell", "menu", "home", "dashboard", "page", "button", "tab", "calendar", "export\\w*", "csv", "ics",
      "welcome guide", "guide", "central time", "ct", "cst", "cdt", "time ?zone", "utc",
      // Account
      "log ?in", "log ?out", "sign ?in", "sign ?out", "account", "password", "otp", "verif\\w*", "role", "permission\\w*", "user", "users",
    ].join("|") +
    String.raw`)\b`,
  "i",
)

/**
 * Talking to the assistant itself. These let a message through on its own ("hi", "what can you
 * do?") but do not make a conversation count as being about GACS: a greeting must not let a
 * short unrelated question ride in behind it as a follow-up.
 */
const ABOUT_THE_ASSISTANT = /\b(?:help|assistant|hi|hello|hey|thanks?|thank you|who are you|what can you do|get(?:ting)? started)\b/i

/** Tasks that are not about using GACS, whatever else the message mentions. */
const UNRELATED_TASKS: readonly RegExp[] = [
  /\b(?:write|compose|tell|give|make)\b[^.?!\n]{0,25}\b(?:poem|poetry|story|song|lyrics|joke|essay|limerick|haiku|riddle)\b/i,
  /\btranslate\b[^.?!\n]{0,60}\b(?:into|to)\s+(?:french|spanish|german|chinese|mandarin|japanese|korean|malay|tamil|hindi|arabic|latin)\b/i,
  /\b(?:homework|recipe|horoscope)\b/i,
]

const asksForSomethingElse = (text: string) => looksLikeOverride(text) || UNRELATED_TASKS.some((task) => task.test(text))

/** Where a message starts a new request: sentence ends (a dot inside "12.40" or "10.0.0.1" is not one), "also", "by the way", and "and <new question>". */
const SEGMENT_BREAK =
  /[.?!;]+(?=\s|$)|\n+|\b(?:also|additionally|plus|by the way|btw|separately|on another note|unrelated)\b[,:]?|,?\s+and\s+(?=(?:what|who|whom|where|when|how|why|can|could|do|does|is|are|will|would|should|tell|give|write|show|explain|list)\b)/gi

/** Words that tie a short piece back to what was already said, or ask for a style of answer. */
const CONTINUATION =
  /\b(?:it|this|that|these|those|them|they|one|ones|more|else|then|next|example|elaborate|clarify|instead|briefly|short|shorter|concise|sentence|simply|steps|detail|details|summar\w*|bullets?)\b/i

/** A piece that is only a courtesy or a one-word follow-up ("please", "why?", "and then?"). */
const FILLER_ONLY = /^(?:(?:please|pls|thanks?|thank you|thx|ok(?:ay)?|yes|no|sure|sorry|really|again|and|so|why|how|more|then|next)\b[\s,]*)+$/i

const FOLLOW_UP_MAX_CHARS = 60

export const OFF_TOPIC_REPLY =
  "I can only help with using GACS: creating jobs, plans and approvals, the schedule, and ERCOT prices and the forecast. " +
  "Please keep each message to GACS questions, for example **How do I create a job?**"

export function isOnTopic(messages: readonly ChatTurn[]): boolean {
  const userTurns = messages.filter((m) => m.role === "user")
  const latest = userTurns[userTurns.length - 1]
  if (!latest || asksForSomethingElse(latest.content)) return false

  const pieces = latest.content
    .split(SEGMENT_BREAK)
    .map((piece) => piece.trim())
    .filter((piece) => /[\p{L}\p{N}]/u.test(piece))
  if (pieces.length === 0) return false
  const aboutGacs = (piece: string) => GACS_VOCABULARY.test(piece)
  const aboutAssistant = (piece: string) => ABOUT_THE_ASSISTANT.test(piece)

  // Something on topic was said before: in this message, or in an earlier message of the user's
  // that was not itself turned away.
  const earlierOnTopic = userTurns.slice(0, -1).some((m) => !asksForSomethingElse(m.content) && aboutGacs(m.content))
  const context = earlierOnTopic || pieces.some(aboutGacs)

  return pieces.every(
    (piece) =>
      aboutGacs(piece) ||
      aboutAssistant(piece) ||
      (context && (FILLER_ONLY.test(piece) || (piece.length <= FOLLOW_UP_MAX_CHARS && CONTINUATION.test(piece)))),
  )
}
