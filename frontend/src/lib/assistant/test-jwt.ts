import { createHmac } from "node:crypto"

/** For tests only: makes a token the way the Spring backend does (JJWT, HMAC, claim "userId"). */
export const TEST_JWT_SECRET = "test-secret-key-that-is-at-least-32-bytes-long!"

const b64 = (value: unknown) => Buffer.from(typeof value === "string" ? value : JSON.stringify(value)).toString("base64url")

export function signToken(
  userId: number | string | null,
  { secret = TEST_JWT_SECRET, alg = "HS256", expiresInSeconds = 900, claims = {} }: { secret?: string; alg?: string; expiresInSeconds?: number; claims?: Record<string, unknown> } = {},
): string {
  const now = Math.floor(Date.now() / 1000)
  const payload = { sub: "user@example.com", ...(userId === null ? {} : { userId }), iat: now, exp: now + expiresInSeconds, ...claims }
  const head = `${b64({ alg })}.${b64(payload)}`
  const hash = { HS256: "sha256", HS384: "sha384", HS512: "sha512" }[alg] ?? "sha256"
  return `${head}.${createHmac(hash, secret).update(head).digest("base64url")}`
}
