/**
 * A small sliding-window limit per signed-in user, kept in memory. It stops one account (or a
 * script using one stolen token) from draining the token budget.
 *
 * The key is the user id the backend returned for a verified token (see auth.ts). It is never
 * taken from a request header such as X-Forwarded-For, which the caller controls.
 *
 * The map lives in this process only. It resets on restart and each server instance keeps its
 * own, so with several instances a user can send up to the limit to each. It is a guard rail, not
 * the spend control: the token budget and the provider's spend limit are.
 */

export const RATE_LIMIT_PER_MINUTE = 20
export const RATE_LIMIT_WINDOW_MS = 60_000

const hits = new Map<string, number[]>()

export interface RateLimitResult {
  allowed: boolean
  /** Whole seconds until the next request would be allowed. 0 when it is allowed now. */
  retryAfterSeconds: number
}

/** Records a request and says whether it is allowed, and if not, how long to wait. */
export function checkRate(key: string, now = Date.now(), limit = RATE_LIMIT_PER_MINUTE): RateLimitResult {
  const recent = (hits.get(key) ?? []).filter((t) => now - t < RATE_LIMIT_WINDOW_MS)
  if (recent.length >= limit) {
    hits.set(key, recent)
    // The oldest request in the window leaves it first, which frees one slot.
    const wait = recent[recent.length - limit] + RATE_LIMIT_WINDOW_MS - now
    return { allowed: false, retryAfterSeconds: Math.max(1, Math.ceil(wait / 1000)) }
  }
  recent.push(now)
  hits.set(key, recent)
  // Keep the map from growing without bound.
  if (hits.size > 5000) {
    for (const [k, times] of hits) if (times.every((t) => now - t >= RATE_LIMIT_WINDOW_MS)) hits.delete(k)
  }
  return { allowed: true, retryAfterSeconds: 0 }
}

/** Test helper. */
export function resetRateLimitForTests() {
  hits.clear()
}
