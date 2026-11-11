/**
 * A small sliding-window limit per client IP, kept in memory. It stops one browser tab or script
 * from draining the token budget; it is not a substitute for the budget itself.
 */

export const RATE_LIMIT_PER_MINUTE = 20
const WINDOW_MS = 60_000

const hits = new Map<string, number[]>()

export function clientIp(headers: Headers): string {
  const forwarded = headers.get("x-forwarded-for")
  if (forwarded) return forwarded.split(",")[0].trim()
  return headers.get("x-real-ip")?.trim() || "local"
}

/** Records a request and says whether it is allowed. */
export function allowRequest(key: string, now = Date.now(), limit = RATE_LIMIT_PER_MINUTE): boolean {
  const recent = (hits.get(key) ?? []).filter((t) => now - t < WINDOW_MS)
  if (recent.length >= limit) {
    hits.set(key, recent)
    return false
  }
  recent.push(now)
  hits.set(key, recent)
  // Keep the map from growing without bound.
  if (hits.size > 5000) {
    for (const [k, times] of hits) if (times.every((t) => now - t >= WINDOW_MS)) hits.delete(k)
  }
  return true
}
