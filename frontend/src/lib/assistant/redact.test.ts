import { describe, expect, it } from "vitest"
import { cleanUserMessage, credentialTypes, MAX_CLEAN_CHARS, passesLuhn, redact, sanitize, typedLength } from "./redact"

describe("redact", () => {
  it.each([
    ["email", "Contact me at jane.doe+ops@datacenter.io please", "[REDACTED_EMAIL]", "EMAIL"],
    ["Singapore mobile", "call 9123 4567 tomorrow", "[REDACTED_PHONE]", "PHONE"],
    ["international phone", "my number is +65 9123 4567", "[REDACTED_PHONE]", "PHONE"],
    ["US phone", "office (512) 555-0100", "[REDACTED_PHONE]", "PHONE"],
    ["NRIC", "my NRIC is S1234567D", "[REDACTED_NRIC]", "NRIC"],
    ["FIN", "FIN G7654321K here", "[REDACTED_NRIC]", "NRIC"],
    ["card", "card 4111 1111 1111 1111 exp 12/29", "[REDACTED_CARD]", "CARD"],
    ["IPv4", "the server is 10.0.12.254", "[REDACTED_IP]", "IP"],
    ["JWT", "token eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U", "[REDACTED_JWT]", "JWT"],
    ["Anthropic key", "key sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456", "[REDACTED_API_KEY]", "API_KEY"],
    ["GitHub token", "ghp_abcdefghijklmnopqrstuvwxyz0123456789", "[REDACTED_API_KEY]", "API_KEY"],
    ["AWS key", "AKIAIOSFODNN7EXAMPLE", "[REDACTED_API_KEY]", "API_KEY"],
    ["Bearer header", "Authorization: Bearer abcdefghijklmnop.qrstuvwx", "[REDACTED_API_KEY]", "API_KEY"],
    ["database URL", "postgres://gacs:s3cretPass@db.internal:5432/gacs", "postgres://[REDACTED_CREDENTIALS]@", "CREDENTIALS"],
    ["password label", "password: hunter2", "password: [REDACTED_SECRET]", "SECRET"],
    ["password sentence", "my password is Tr0ub4dor&3", "password is [REDACTED_SECRET]", "SECRET"],
    ["otp", "otp=482913", "otp=[REDACTED_SECRET]", "SECRET"],
    ["long hex", "hash 9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08", "[REDACTED_SECRET]", "SECRET"],
    ["private key", "-----BEGIN RSA PRIVATE KEY-----\nMIIEow\n-----END RSA PRIVATE KEY-----", "[REDACTED_PRIVATE_KEY]", "PRIVATE_KEY"],
  ])("removes a %s", (_name, input, expected, type) => {
    const result = redact(input)
    expect(result.text).toContain(expected)
    expect(result.types).toContain(type)
  })

  it("does not leave the secret itself behind", () => {
    const { text } = redact("login admin@datacenter.io with password: Sup3rSecret!! and card 4111111111111111")
    expect(text).not.toMatch(/admin@datacenter\.io|Sup3rSecret|4111111111111111/)
  })

  it.each([
    "How do I reset my password?",
    "my password is rejected",
    "What is the password policy?",
    "The job runs 2026-11-11 09:00 to 2026-11-11 17:00 CT",
    "It needs 32 CPU hours and 8 GPU hours",
    "Why was run 7f3c1e52 not approved?",
    "Plan saves $12.40 (8.1%) at $42.10 /MWh",
    "Job 101 is stuck in Needs attention",
  ])("leaves ordinary questions alone: %s", (input) => {
    expect(redact(input)).toEqual({ text: input, types: [] })
  })

  it("keeps a 16-digit number that fails the card checksum", () => {
    expect(redact("order 1234567812345678").types).not.toContain("CARD")
  })

  it("does not redact a placeholder twice", () => {
    const { text, types } = redact("token: eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N")
    expect(text).toBe("token: [REDACTED_JWT]")
    expect(types).toEqual(["JWT"])
  })
})

describe("passesLuhn", () => {
  it("accepts valid and rejects invalid numbers", () => {
    expect(passesLuhn("4111111111111111")).toBe(true)
    expect(passesLuhn("4111111111111112")).toBe(false)
  })
})

describe("sanitize and cleanUserMessage", () => {
  it("removes invisible and control characters and squeezes whitespace", () => {
    expect(sanitize("  hi​   there\u0007\n\n\n\nbye  ")).toBe("hi there\n\nbye")
  })

  it("catches an email split by a zero-width space", () => {
    expect(cleanUserMessage("jane​@datacenter.io").text).toBe("[REDACTED_EMAIL]")
  })

  it("redacts before cutting the length, so a secret is never half left", () => {
    const key = "sk-ant-api03-abcdefghijklmnopqrstuvwxyz123456"
    const input = `${"a ".repeat(740)}${key}`
    const { text } = cleanUserMessage(input)
    expect(text.length).toBeLessThanOrEqual(MAX_CLEAN_CHARS)
    expect(text).not.toContain("sk-ant-api03-abc")
  })
})

describe("IPv6 addresses", () => {
  it.each([
    ["full", "host 2001:0db8:85a3:0000:0000:8a2e:0370:7334 is down"],
    ["compressed", "the router is 2001:db8::1 today"],
    ["loopback", "connect to ::1 please"],
    ["link-local", "fe80::1ff:fe23:4567:890a failed"],
  ])("removes a %s address", (_name, input) => {
    const result = redact(input)
    expect(result.types).toEqual(["IP"])
    expect(result.text).toContain("[REDACTED_IP]")
    expect(result.text).not.toMatch(/db8|::1|fe80|8a2e/)
  })

  it.each([
    "The window is 10:30:45 CT",
    "Ratio 3:1 or 16:9",
    "Use std::vector here",
    "Start 2026-11-11T09:00:00-06:00",
    "A :: B",
  ])("leaves times and ordinary colons alone: %s", (input) => {
    expect(redact(input)).toEqual({ text: input, types: [] })
  })
})

describe("formats that matter for this app", () => {
  it.each([
    ["an email with a plus tag", "ops+night@gacs-datacenter.com.sg"],
    ["an email in angle brackets", "from <Jane Tan> <jane.tan@corp.example.org>"],
    ["an uppercase email", "JANE@DATACENTER.IO"],
    ["a refresh token in a header", "X-Refresh-Token: 7d8f2b1c9a3e4f5061728394a5b6c7d8e9f0a1b2c3d4e5f60718293a4b5c6d7e"],
    ["a quoted secret", 'client_secret="abc 123 def"'],
    ["a JSON password", '{"password": "hunter2"}'],
    ["a Singapore number with country code", "WhatsApp +65 8123 4567"],
  ])("removes %s", (_name, input) => {
    expect(redact(input).types.length).toBeGreaterThan(0)
  })

  it("redacts the same text the same way twice (safe to run in the browser and on the server)", () => {
    const once = redact("mail jane@datacenter.io from 10.0.0.1 with password: hunter2").text
    expect(redact(once).text).toBe(once)
  })
})

describe("known limits (best effort)", () => {
  // These are documented gaps, not features. If a rule is added for one, move it up.
  it.each([
    ["a spelled-out email", "jane at datacenter dot io"],
    ["a password with no label", "it is Tr0ub4dor&3"],
    ["a name and address", "Jane Tan, 12 Orchard Road"],
  ])("does not catch %s", (_name, input) => {
    expect(redact(input).types).toEqual([])
  })
})

describe("typedLength", () => {
  it("counts a placeholder as one character", () => {
    expect(typedLength("a 1.1.1.1 b")).toBe("a # b".length)
  })

  it("ignores characters normalisation removes", () => {
    expect(typedLength("  hi\u200b\u200b  ")).toBe(2)
  })

  it("never counts more than the text that was typed", () => {
    const typed = "mail jane@datacenter.io or 10.0.0.1, key sk-ant-api03-abcdefghijklmnopqrstuvwxyz1"
    expect(typedLength(typed)).toBeLessThan(typed.length)
  })
})

describe("credentialTypes", () => {
  it("picks out passwords, keys, tokens and logins in links", () => {
    expect(credentialTypes(["PRIVATE_KEY", "JWT", "API_KEY", "CREDENTIALS", "SECRET"])).toHaveLength(5)
  })

  it("leaves personal details to be redacted and sent", () => {
    expect(credentialTypes(["EMAIL", "PHONE", "CARD", "NRIC", "IP"])).toEqual([])
  })

  it("is what redact reports for a credential and for a plain email", () => {
    expect(credentialTypes(redact("password: hunter2").types)).toEqual(["SECRET"])
    expect(credentialTypes(redact("ask jane@datacenter.io").types)).toEqual([])
  })
})
