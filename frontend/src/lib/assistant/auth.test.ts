import { createHmac } from "node:crypto"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { verifySession } from "./auth"
import { signToken, TEST_JWT_SECRET } from "./test-jwt"

const bearer = (token: string) => `Bearer ${token}`
const b64 = (value: unknown) => Buffer.from(JSON.stringify(value)).toString("base64url")

beforeEach(() => {
  process.env.JWT_SECRET_KEY = TEST_JWT_SECRET
})

afterEach(() => {
  delete process.env.JWT_SECRET_KEY
  vi.unstubAllGlobals()
})

describe("verifySession", () => {
  it("accepts a token the backend would have signed and returns its user id", () => {
    expect(verifySession(bearer(signToken(42)))).toEqual({ ok: true, userId: "42" })
  })

  it("never touches the network", () => {
    const fetchSpy = vi.fn()
    vi.stubGlobal("fetch", fetchSpy)
    verifySession(bearer(signToken(1)))
    verifySession(bearer("forged"))
    verifySession(null)
    expect(fetchSpy).not.toHaveBeenCalled()
  })

  it.each([
    ["no header", null],
    ["an empty header", ""],
    ["a Basic header", "Basic dXNlcjpwYXNz"],
    ["a bare Bearer", "Bearer"],
    ["a Bearer with spaces in the token", "Bearer a b"],
  ])("refuses %s", (_name, header) => {
    expect(verifySession(header)).toEqual({ ok: false, reason: "unauthorized" })
  })

  it("accepts a lower-case scheme", () => {
    expect(verifySession(`bearer ${signToken(5)}`)).toEqual({ ok: true, userId: "5" })
  })

  describe("a token that is not good", () => {
    const refused = { ok: false, reason: "unauthorized" }

    it("is refused when signed with a different key", () => {
      expect(verifySession(bearer(signToken(1, { secret: "another-secret-key-that-is-32-bytes-long!!" })))).toEqual(refused)
    })

    it("is refused when the payload was changed after signing", () => {
      const [head, , signature] = signToken(1).split(".")
      const tampered = `${head}.${b64({ userId: 999, exp: Math.floor(Date.now() / 1000) + 900 })}.${signature}`
      expect(verifySession(bearer(tampered))).toEqual(refused)
    })

    it("is refused when it has expired", () => {
      expect(verifySession(bearer(signToken(1, { expiresInSeconds: -1 })))).toEqual(refused)
    })

    it("is refused at the very second it expires, and accepted a second before", () => {
      const token = signToken(1, { expiresInSeconds: 100 })
      const exp = JSON.parse(Buffer.from(token.split(".")[1], "base64url").toString()).exp as number
      expect(verifySession(bearer(token), exp * 1000 - 1000).ok).toBe(true)
      expect(verifySession(bearer(token), exp * 1000)).toEqual(refused)
    })

    it("is refused before its not-before time", () => {
      const future = Math.floor(Date.now() / 1000) + 3600
      expect(verifySession(bearer(signToken(1, { claims: { nbf: future } })))).toEqual(refused)
    })

    it("is refused with no expiry: it would never run out", () => {
      expect(verifySession(bearer(signToken(1, { claims: { exp: undefined } })))).toEqual(refused)
    })

    it("is refused with no user id, or one that is not a number", () => {
      expect(verifySession(bearer(signToken(null)))).toEqual(refused)
      expect(verifySession(bearer(signToken("42")))).toEqual(refused)
      expect(verifySession(bearer(signToken(-3)))).toEqual(refused)
      expect(verifySession(bearer(signToken(1.5)))).toEqual(refused)
    })

    it('is refused with alg "none"', () => {
      const unsigned = `${b64({ alg: "none" })}.${b64({ userId: 1, exp: Math.floor(Date.now() / 1000) + 900 })}.`
      expect(verifySession(bearer(unsigned))).toEqual(refused)
    })

    it("is refused with an algorithm the backend does not use", () => {
      expect(verifySession(bearer(signToken(1, { alg: "RS256" })))).toEqual(refused)
      expect(verifySession(bearer(signToken(1, { alg: "HS1" })))).toEqual(refused)
    })

    it("is refused with an empty or truncated signature", () => {
      const [head, payload, signature] = signToken(1).split(".")
      expect(verifySession(bearer(`${head}.${payload}.`))).toEqual(refused)
      expect(verifySession(bearer(`${head}.${payload}.${signature.slice(0, 10)}`))).toEqual(refused)
      expect(verifySession(bearer(`${head}.${payload}.${signature}AAAA`))).toEqual(refused)
    })

    it.each([
      ["not a JWT at all", "forged"],
      ["two parts", "a.b"],
      ["four parts", "a.b.c.d"],
      ["parts that are not JSON", "bm90anNvbg.bm90anNvbg.c2ln"],
      ["a payload that is not an object", `${b64({ alg: "HS256" })}.${b64([1])}.c2ln`],
      ["a token over 4,096 characters", "x".repeat(4097)],
    ])("is refused when it is %s", (_name, token) => {
      expect(verifySession(bearer(token))).toEqual(refused)
    })

    it("is refused when its signature is right for a different algorithm label", () => {
      // Same key and signature bytes as HS256, but the header claims HS512: the check is redone with HS512.
      const head = `${b64({ alg: "HS512" })}.${b64({ userId: 1, exp: Math.floor(Date.now() / 1000) + 900 })}`
      const sha256Signature = createHmac("sha256", TEST_JWT_SECRET).update(head).digest("base64url")
      expect(verifySession(bearer(`${head}.${sha256Signature}`))).toEqual(refused)
    })
  })

  describe("the signing key", () => {
    it("says the assistant is not set up when JWT_SECRET_KEY is missing, and lets nobody in", () => {
      delete process.env.JWT_SECRET_KEY
      expect(verifySession(bearer(signToken(1)))).toEqual({ ok: false, reason: "not_configured" })
    })

    it("says so when the key is shorter than the backend allows", () => {
      process.env.JWT_SECRET_KEY = "too-short"
      expect(verifySession(bearer(signToken(1, { secret: "too-short" })))).toEqual({ ok: false, reason: "not_configured" })
    })

    it("still refuses a missing token first, without revealing the set-up state", () => {
      delete process.env.JWT_SECRET_KEY
      expect(verifySession(null)).toEqual({ ok: false, reason: "unauthorized" })
    })

    it.each([
      ["HS384", "a-longer-secret-key-for-hs384-needs-at-least-48-bytes!!"],
      ["HS512", "a-much-longer-secret-key-for-hs512-which-needs-at-least-64-bytes-to-sign"],
    ])("accepts a %s token, which is what the backend signs with a key of that length", (alg, secret) => {
      process.env.JWT_SECRET_KEY = secret
      expect(verifySession(bearer(signToken(7, { secret, alg })))).toEqual({ ok: true, userId: "7" })
    })

    it("uses the key's exact characters, as the backend does (UTF-8, no decoding)", () => {
      const secret = "päss-wörd-with-ünïcode-that-is-over-32-bytes-long"
      process.env.JWT_SECRET_KEY = secret
      expect(verifySession(bearer(signToken(3, { secret })))).toEqual({ ok: true, userId: "3" })
    })
  })
})
