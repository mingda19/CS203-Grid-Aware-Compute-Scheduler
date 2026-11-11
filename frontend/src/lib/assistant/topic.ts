import type { ChatTurn } from "./validate"

/**
 * A conservative topic gate that runs before any model call.
 *
 * What it is: a vocabulary check. A conversation passes when the latest user message mentions
 * something GACS-related, or is a short follow-up in a conversation that already did. A message
 * with nothing to do with GACS gets a fixed reply, without spending any tokens.
 *
 * What it is not: a guarantee. A person can mention a GACS word and ask for something else, and
 * a follow-up can drift. The system prompt (knowledge.ts) tells the model to stay on topic, and
 * that is also only an instruction. The gate is deliberately loose, because wrongly turning away a
 * real question is worse than letting an off-topic one through.
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
      // Talking to the assistant itself
      "help", "assistant", "hi", "hello", "hey", "thanks?", "thank you", "who are you", "what can you do", "get(?:ting)? started",
    ].join("|") +
    String.raw`)\b`,
  "i",
)

/** A follow-up such as "why?" or "and for admins?" is short and has no vocabulary of its own. */
const FOLLOW_UP_MAX_CHARS = 80

export const OFF_TOPIC_REPLY =
  "I can only help with using GACS: creating jobs, plans and approvals, the schedule, and ERCOT prices and the forecast. " +
  "Try asking, for example, **How do I create a job?**"

export function isOnTopic(messages: readonly ChatTurn[]): boolean {
  const userTurns = messages.filter((m) => m.role === "user")
  const latest = userTurns[userTurns.length - 1]
  if (!latest) return false
  if (GACS_VOCABULARY.test(latest.content)) return true
  const earlier = userTurns.slice(0, -1)
  return latest.content.length <= FOLLOW_UP_MAX_CHARS && earlier.some((m) => GACS_VOCABULARY.test(m.content))
}
