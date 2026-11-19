import { beforeEach, describe, expect, it } from "vitest"
import { checkRate, RATE_LIMIT_PER_MINUTE, RATE_LIMIT_WINDOW_MS, resetRateLimitForTests } from "./rate-limit"

beforeEach(resetRateLimitForTests)

describe("checkRate", () => {
  it("allows up to the limit, then refuses", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) expect(checkRate("user:1", 1000).allowed).toBe(true)
    expect(checkRate("user:1", 1000).allowed).toBe(false)
  })

  it("keeps each user's count separate", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) checkRate("user:1", 1000)
    expect(checkRate("user:1", 1000).allowed).toBe(false)
    expect(checkRate("user:2", 1000).allowed).toBe(true)
  })

  it("says how long to wait: until the oldest request leaves the window", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) checkRate("user:1", 10_000 + i)
    const refused = checkRate("user:1", 40_000)
    expect(refused).toEqual({ allowed: false, retryAfterSeconds: 30 })
  })

  it("always asks for at least one second", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) checkRate("user:1", 0)
    expect(checkRate("user:1", RATE_LIMIT_WINDOW_MS - 1).retryAfterSeconds).toBe(1)
  })

  it("allows requests again once the wait is over", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) checkRate("user:1", 0)
    expect(checkRate("user:1", 30_000).allowed).toBe(false)
    expect(checkRate("user:1", RATE_LIMIT_WINDOW_MS).allowed).toBe(true)
  })

  it("does not count refused requests against the user", () => {
    for (let i = 0; i < RATE_LIMIT_PER_MINUTE; i++) checkRate("user:1", 0)
    for (let i = 0; i < 50; i++) checkRate("user:1", 1000)
    expect(checkRate("user:1", RATE_LIMIT_WINDOW_MS).allowed).toBe(true)
  })
})
