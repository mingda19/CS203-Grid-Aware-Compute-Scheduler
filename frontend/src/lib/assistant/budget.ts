import fs from "node:fs"
import os from "node:os"
import path from "node:path"

/**
 * A hard cap on the tokens the assistant may spend, from CHAT_TOKEN_BUDGET.
 *
 * Strict by design:
 * - Before a call, the worst case (a generous input estimate plus max_tokens) is reserved. A call
 *   that could push the total past the cap is refused, so the cap is never exceeded.
 * - After the call, the reservation is swapped for the real usage the API reported. Every token
 *   counts at full weight, cache reads included.
 * - When a call ends without a usage report (a dropped connection), the reservation is kept as spent.
 * - The total is saved to a file, so restarting the dev server does not reset it.
 *
 * The file lives on one machine. On serverless hosting each instance keeps its own file, so set a
 * spend limit in the Anthropic Console as the real guarantee there.
 */

export interface UsageLike {
  input_tokens?: number | null
  output_tokens?: number | null
  cache_creation_input_tokens?: number | null
  cache_read_input_tokens?: number | null
}

export function totalTokens(usage: UsageLike): number {
  return (
    (usage.input_tokens ?? 0) +
    (usage.output_tokens ?? 0) +
    (usage.cache_creation_input_tokens ?? 0) +
    (usage.cache_read_input_tokens ?? 0)
  )
}

/**
 * Reads CHAT_TOKEN_BUDGET. Accepts "250000", "250,000" or "250_000".
 * Unset means no app-level cap. Anything unreadable means zero, so a typo fails closed.
 */
export function parseBudget(raw: string | undefined): number | null {
  if (raw === undefined || raw.trim() === "") return null
  const cleaned = raw.trim().replace(/[,_\s]/g, "")
  if (!/^\d+$/.test(cleaned)) return 0
  return Number(cleaned)
}

function usageFile(): string {
  return process.env.CHAT_USAGE_FILE || path.join(os.tmpdir(), "gacs-chat-usage.json")
}

function readUsed(file: string): number {
  try {
    const data = JSON.parse(fs.readFileSync(file, "utf8")) as { used?: unknown }
    return typeof data.used === "number" && Number.isFinite(data.used) && data.used >= 0 ? data.used : 0
  } catch {
    return 0
  }
}

function writeUsed(file: string, used: number, limit: number | null) {
  try {
    fs.writeFileSync(file, JSON.stringify({ used, limit, updatedAt: new Date().toISOString() }), "utf8")
  } catch {
    // A read-only disk must not break the chat. The in-memory total still applies.
  }
}

// Calls in flight in this process. Kept in memory: they settle within seconds.
let reserved = 0
let memoryUsed = 0

export interface Reservation {
  amount: number
  settled: boolean
}

export interface BudgetStatus {
  limit: number | null
  used: number
  reserved: number
}

export function budgetStatus(): BudgetStatus {
  const used = Math.max(memoryUsed, readUsed(usageFile()))
  return { limit: parseBudget(process.env.CHAT_TOKEN_BUDGET), used, reserved }
}

/** Reserves `amount` tokens, or returns null when that could exceed the cap. */
export function reserve(amount: number): Reservation | null {
  const { limit, used } = budgetStatus()
  memoryUsed = used
  if (limit !== null && used + reserved + amount > limit) return null
  reserved += amount
  return { amount, settled: false }
}

/** Swaps the reservation for what was really spent. `actual` null means unknown: keep the reservation as spent. */
export function settle(reservation: Reservation, actual: number | null) {
  if (reservation.settled) return
  reservation.settled = true
  reserved = Math.max(0, reserved - reservation.amount)
  const spent = actual === null ? reservation.amount : actual
  if (spent <= 0) return
  const file = usageFile()
  memoryUsed = Math.max(memoryUsed, readUsed(file)) + spent
  writeUsed(file, memoryUsed, parseBudget(process.env.CHAT_TOKEN_BUDGET))
}

/** Rough upper bound on input tokens. Real usage is about one token per 3 to 4 characters of English. */
export function estimateInputTokens(chars: number): number {
  return Math.ceil(chars / 2.5) + 50
}

/** Test helper. */
export function resetBudgetForTests() {
  reserved = 0
  memoryUsed = 0
}
