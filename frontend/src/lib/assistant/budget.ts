import { randomUUID } from "node:crypto"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"

/**
 * An application-level token budget for the assistant, from CHAT_TOKEN_BUDGET.
 *
 * This is a best-effort control, not a spend guarantee. The only hard limit on money is the spend
 * limit set on the Anthropic account (Console > Limits); set one there. What this does:
 *
 * - Before a call it reserves a conservative upper bound (see `inputTokenUpperBound`) plus the
 *   whole output allowance, for every attempt the SDK may make. A call that could push the total
 *   past the budget is refused.
 * - After the call it swaps the reservation for the usage the API reported, cache reads included.
 *   If real usage is higher than the reservation, the real number is recorded: the total can pass
 *   the budget by that overrun, and later calls are refused until it is reset.
 * - A call that ends without a usage report keeps its reservation as spent.
 * - The total AND the in-flight reservations live in one JSON file that is read and changed under
 *   a lock file. Several server processes on the same machine therefore share one budget and
 *   cannot both reserve the last tokens. Reservations of a crashed process expire after a few
 *   minutes.
 *
 * Where it stops being reliable:
 * - Separate machines, containers or serverless instances each have their own file. Point
 *   CHAT_USAGE_FILE at storage they all share, or rely on the provider limit.
 * - If the file cannot be written or locked, counting falls back to this process's memory only.
 *   That is logged once. It is still checked, but it is per process and resets on restart.
 * - If the file exists but cannot be read (corrupt), every call is refused until it is fixed or
 *   deleted. It does not quietly start again from zero.
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

/**
 * A conservative ceiling on the input tokens of a request, from the text that will be sent.
 *
 * Counting characters and dividing is not a ceiling: dense text (code, other scripts, long
 * identifiers) can use more tokens than that. A token always stands for at least one byte of the
 * UTF-8 text, so the byte count is used. That over-reserves by roughly three times for English,
 * which is fine because the reservation is replaced by real usage when the call ends. A fixed
 * allowance covers the framing the API adds around each message. No tokenizer for these models is
 * public, so treat this as very likely, not proven, to be an upper bound.
 */
export function inputTokenUpperBound(parts: readonly string[]): number {
  const PER_MESSAGE_OVERHEAD = 16
  const FIXED_OVERHEAD = 64
  return parts.reduce((sum, text) => sum + Buffer.byteLength(text, "utf8") + PER_MESSAGE_OVERHEAD, FIXED_OVERHEAD)
}

// --- Shared state -------------------------------------------------------------------------------

const LOCK_STALE_MS = 10_000
const LOCK_WAIT_MS = 2_000
const RESERVATION_TTL_MS = 3 * 60_000

interface Held {
  amount: number
  expiresAt: number
}

interface State {
  used: number
  reservations: Record<string, Held>
}

class CorruptStateError extends Error {}

function usageFile(): string {
  return process.env.CHAT_USAGE_FILE || path.join(os.tmpdir(), "gacs-chat-usage.json")
}

function emptyState(): State {
  return { used: 0, reservations: {} }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null && !Array.isArray(value)
}

/** A missing file is a fresh start. A file that exists but makes no sense is an error, never zero. */
function readState(file: string): State {
  let raw: string
  try {
    raw = fs.readFileSync(file, "utf8")
  } catch (error) {
    if ((error as NodeJS.ErrnoException).code === "ENOENT") return emptyState()
    throw error
  }
  let data: unknown
  try {
    data = JSON.parse(raw)
  } catch {
    throw new CorruptStateError("usage file is not valid JSON")
  }
  if (!isRecord(data) || typeof data.used !== "number" || !Number.isFinite(data.used) || data.used < 0) {
    throw new CorruptStateError("usage file has no valid total")
  }
  const state: State = { used: data.used, reservations: {} }
  if (isRecord(data.reservations)) {
    for (const [id, held] of Object.entries(data.reservations)) {
      if (isRecord(held) && typeof held.amount === "number" && typeof held.expiresAt === "number") {
        state.reservations[id] = { amount: Math.max(0, held.amount), expiresAt: held.expiresAt }
      }
    }
  }
  return state
}

function writeState(file: string, state: State) {
  // Write beside the file and rename, so a reader never sees half a file.
  const tmp = `${file}.${process.pid}.tmp`
  fs.writeFileSync(tmp, JSON.stringify({ ...state, updatedAt: new Date().toISOString() }), "utf8")
  fs.renameSync(tmp, file)
}

function pruneExpired(state: State, now: number) {
  for (const [id, held] of Object.entries(state.reservations)) {
    if (held.expiresAt <= now) delete state.reservations[id]
  }
}

function reservedTotal(state: State): number {
  return Object.values(state.reservations).reduce((sum, held) => sum + held.amount, 0)
}

function sleep(ms: number) {
  Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, ms)
}

/** Takes the lock file (created exclusively). Returns the function that gives it back. */
function acquireLock(lockPath: string): () => void {
  const deadline = Date.now() + LOCK_WAIT_MS
  for (;;) {
    try {
      fs.closeSync(fs.openSync(lockPath, "wx"))
      return () => {
        try {
          fs.unlinkSync(lockPath)
        } catch {
          // Already gone.
        }
      }
    } catch (error) {
      if ((error as NodeJS.ErrnoException).code !== "EEXIST") throw error
    }
    try {
      // A lock left by a process that died is taken over.
      if (Date.now() - fs.statSync(lockPath).mtimeMs > LOCK_STALE_MS) fs.unlinkSync(lockPath)
    } catch {
      // Released by its owner in the meantime.
    }
    if (Date.now() > deadline) throw new Error("timed out waiting for the usage lock")
    sleep(5)
  }
}

// Used only when the file cannot be locked or written.
let fallback = emptyState()
const warned = new Set<string>()

function warnOnce(key: string, message: string) {
  if (warned.has(key)) return
  warned.add(key)
  console.error(`[assistant] ${message}`)
}

/**
 * Runs `change` on the shared state while holding the lock, then saves it. If the file cannot be
 * used, the same change runs on this process's memory instead. A corrupt file is not worked
 * around: CorruptStateError reaches the caller.
 */
function transact<T>(change: (state: State, now: number) => T): T {
  const file = usageFile()
  const now = Date.now()
  let release: (() => void) | null = null
  try {
    fs.mkdirSync(path.dirname(file), { recursive: true })
    release = acquireLock(`${file}.lock`)
    const state = readState(file)
    pruneExpired(state, now)
    const result = change(state, now)
    writeState(file, state)
    return result
  } catch (error) {
    if (error instanceof CorruptStateError) {
      warnOnce("corrupt", `The usage file ${file} is unreadable (${error.message}). The assistant is refusing requests until it is fixed or deleted.`)
      throw error
    }
    warnOnce("fallback", `Cannot use the usage file ${file} (${(error as Error).message}). Counting in memory for this process only.`)
    pruneExpired(fallback, now)
    return change(fallback, now)
  } finally {
    release?.()
  }
}

// --- Public API ---------------------------------------------------------------------------------

export interface Reservation {
  id: string
  amount: number
  settled: boolean
}

export interface BudgetStatus {
  limit: number | null
  used: number
  reserved: number
  /** True when the usage file could not be read. Nothing is allowed while this is set. */
  unreadable: boolean
}

export function budgetStatus(): BudgetStatus {
  const limit = parseBudget(process.env.CHAT_TOKEN_BUDGET)
  const now = Date.now()
  let state: State
  try {
    state = readState(usageFile())
  } catch (error) {
    if (error instanceof CorruptStateError) return { limit, used: limit ?? 0, reserved: 0, unreadable: true }
    state = fallback
  }
  const live = { used: state.used, reservations: { ...state.reservations } }
  pruneExpired(live, now)
  return { limit, used: live.used, reserved: reservedTotal(live), unreadable: false }
}

/** Reserves `amount` tokens, or returns null when that could exceed the budget (or it is unreadable). */
export function reserve(amount: number): Reservation | null {
  const limit = parseBudget(process.env.CHAT_TOKEN_BUDGET)
  try {
    return transact((state, now) => {
      if (limit !== null && state.used + reservedTotal(state) + amount > limit) return null
      const id = randomUUID()
      state.reservations[id] = { amount, expiresAt: now + RESERVATION_TTL_MS }
      return { id, amount, settled: false }
    })
  } catch (error) {
    if (error instanceof CorruptStateError) return null
    throw error
  }
}

/**
 * Swaps the reservation for what was really spent. `actual` null means unknown: keep the
 * reservation as spent. Real usage above the reservation is recorded in full.
 */
export function settle(reservation: Reservation, actual: number | null) {
  if (reservation.settled) return
  reservation.settled = true
  const spent = Math.max(0, actual === null ? reservation.amount : actual)
  if (spent > reservation.amount) {
    console.warn(`[assistant] usage ${spent} was above the reserved ${reservation.amount} tokens`)
  }
  try {
    transact((state) => {
      delete state.reservations[reservation.id]
      state.used += spent
    })
  } catch (error) {
    // An unreadable file stays as it is: writing over it would hide the problem.
    if (!(error instanceof CorruptStateError)) throw error
  }
}

/** Test helper: forget this process's memory. The file is the caller's to clean up. */
export function resetBudgetForTests() {
  fallback = emptyState()
  warned.clear()
}
