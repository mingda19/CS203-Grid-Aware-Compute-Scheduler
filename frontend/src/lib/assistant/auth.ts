import { createHmac, timingSafeEqual } from "node:crypto"

/**
 * Proves who is calling /api/chat from the bearer token alone. It does not call the Spring
 * backend, the database or anything else.
 *
 * The gacs_logged_in cookie is only a UI hint that browser JavaScript writes, so it proves
 * nothing. The backend signs its access tokens with an HMAC key (security.jwt.secret-key, the
 * JWT_SECRET_KEY environment variable), and its own filter accepts a token when the signature and
 * expiry check out. This does the same check with the same key, so it accepts exactly the tokens
 * the backend accepts, in microseconds and with nothing to exhaust.
 *
 * What that means:
 * - JWT_SECRET_KEY must be set for the Next.js server too. It is server-only: never give it a
 *   NEXT_PUBLIC_ name. Without it the assistant says it is not set up, and nobody is let in.
 * - Like the backend's own filter, this does not look the user up: a token stays good until it
 *   expires (15 minutes by default), even if that account was deleted or logged out meanwhile.
 */

export type SessionResult =
  | { ok: true; userId: string }
  | { ok: false; reason: "unauthorized" | "not_configured" }

const MAX_TOKEN_CHARS = 4096
/** The backend refuses a shorter key (HMAC-SHA256 needs 256 bits), so a shorter one here is a mistake. */
const MIN_KEY_BYTES = 32
/** JJWT picks HS256, HS384 or HS512 from the key's length. Any of them is the same key, so all are accepted. */
const HMAC_ALGORITHMS: Record<string, string> = { HS256: "sha256", HS384: "sha384", HS512: "sha512" }

function signingKey(): Buffer | null {
  const key = Buffer.from(process.env.JWT_SECRET_KEY ?? "", "utf8")
  return key.length >= MIN_KEY_BYTES ? key : null
}

function bearerToken(authorization: string | null): string | null {
  const match = authorization?.match(/^Bearer\s+(\S+)$/i)
  return match && match[1].length <= MAX_TOKEN_CHARS ? match[1] : null
}

function decodeJson(segment: string): Record<string, unknown> | null {
  try {
    const value: unknown = JSON.parse(Buffer.from(segment, "base64url").toString("utf8"))
    return typeof value === "object" && value !== null && !Array.isArray(value) ? (value as Record<string, unknown>) : null
  } catch {
    return null
  }
}

export function verifySession(authorization: string | null, now = Date.now()): SessionResult {
  const token = bearerToken(authorization)
  if (!token) return { ok: false, reason: "unauthorized" }
  const key = signingKey()
  if (!key) return { ok: false, reason: "not_configured" }

  const parts = token.split(".")
  if (parts.length !== 3) return { ok: false, reason: "unauthorized" }
  const [headerPart, payloadPart, signaturePart] = parts
  const header = decodeJson(headerPart)
  const payload = decodeJson(payloadPart)
  // Only the algorithms the backend signs with: never "none", never an asymmetric one.
  const algorithm = typeof header?.alg === "string" ? HMAC_ALGORITHMS[header.alg] : undefined
  if (!header || !payload || !algorithm) return { ok: false, reason: "unauthorized" }

  const expected = createHmac(algorithm, key).update(`${headerPart}.${payloadPart}`).digest()
  const given = Buffer.from(signaturePart, "base64url")
  if (given.length !== expected.length || !timingSafeEqual(given, expected)) return { ok: false, reason: "unauthorized" }

  // A token with no expiry would never run out, so it is refused.
  const { exp, nbf, userId } = payload
  if (typeof exp !== "number" || !Number.isFinite(exp) || exp * 1000 <= now) return { ok: false, reason: "unauthorized" }
  if (nbf !== undefined && (typeof nbf !== "number" || nbf * 1000 > now)) return { ok: false, reason: "unauthorized" }
  if (typeof userId !== "number" || !Number.isSafeInteger(userId) || userId < 0) return { ok: false, reason: "unauthorized" }
  return { ok: true, userId: String(userId) }
}
