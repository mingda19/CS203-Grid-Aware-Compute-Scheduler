/**
 * Proves who is calling /api/chat by asking the Spring backend, which verifies the JWT.
 *
 * The gacs_logged_in cookie is only a UI hint that browser JavaScript writes, so it proves
 * nothing. The caller's bearer token is sent to GET /api/auth/me instead; the backend checks the
 * signature and expiry and says which user it belongs to.
 */

export type SessionResult =
  | { ok: true; userId: string }
  | { ok: false; reason: "unauthorized" | "unavailable" | "not_configured" }

const VERIFY_TIMEOUT_MS = 5_000
const MAX_TOKEN_CHARS = 4096

/** Where the backend is, from the server's own environment. Null when nothing is configured. */
export function backendBaseUrl(): string | null {
  const url =
    process.env.BACKEND_API_URL ||
    process.env.NEXT_PUBLIC_API_BASE_URL ||
    process.env.NEXT_PUBLIC_API_URL ||
    (process.env.NODE_ENV === "production" ? "" : "http://localhost:8080")
  return url.replace(/\/+$/, "") || null
}

export async function verifySession(authorization: string | null): Promise<SessionResult> {
  const match = authorization?.match(/^Bearer\s+(\S+)$/i)
  // No call to the backend for a request that carries no token at all.
  if (!match || match[1].length > MAX_TOKEN_CHARS) return { ok: false, reason: "unauthorized" }

  const base = backendBaseUrl()
  if (!base) return { ok: false, reason: "not_configured" }

  let response: Response
  try {
    response = await fetch(`${base}/api/auth/me`, {
      headers: { Authorization: `Bearer ${match[1]}`, Accept: "application/json" },
      cache: "no-store",
      signal: AbortSignal.timeout(VERIFY_TIMEOUT_MS),
    })
  } catch {
    return { ok: false, reason: "unavailable" }
  }
  if (response.status === 401 || response.status === 403) return { ok: false, reason: "unauthorized" }
  if (!response.ok) return { ok: false, reason: "unavailable" }

  try {
    const body = (await response.json()) as { success?: unknown; data?: { id?: unknown } }
    const id = body?.data?.id
    if (body?.success === false) return { ok: false, reason: "unauthorized" }
    if (typeof id === "number" || (typeof id === "string" && id.length > 0)) return { ok: true, userId: String(id) }
  } catch {
    // Fall through: the backend answered with something that is not a user.
  }
  return { ok: false, reason: "unavailable" }
}
