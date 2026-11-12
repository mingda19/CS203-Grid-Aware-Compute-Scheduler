import { spawn } from "node:child_process"
import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { fileURLToPath } from "node:url"
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest"
import { budgetStatus, inputTokenUpperBound, parseBudget, reserve, resetBudgetForTests, settle, totalTokens } from "./budget"

let dir: string
let file: string

beforeEach(() => {
  dir = fs.mkdtempSync(path.join(os.tmpdir(), "gacs-budget-"))
  file = path.join(dir, "usage.json")
  process.env.CHAT_USAGE_FILE = file
  process.env.CHAT_TOKEN_BUDGET = "1000"
  resetBudgetForTests()
})

afterEach(() => {
  delete process.env.CHAT_USAGE_FILE
  delete process.env.CHAT_TOKEN_BUDGET
  vi.restoreAllMocks()
})

describe("parseBudget", () => {
  it.each([
    ["250000", 250000],
    ["250,000", 250000],
    ["250_000", 250000],
    [" 1000 ", 1000],
  ])("reads %s", (raw, expected) => expect(parseBudget(raw)).toBe(expected))

  it("means no cap when unset and zero when unreadable", () => {
    expect(parseBudget(undefined)).toBeNull()
    expect(parseBudget("")).toBeNull()
    expect(parseBudget("lots")).toBe(0)
    expect(parseBudget("-5")).toBe(0)
  })
})

describe("inputTokenUpperBound", () => {
  it("is never below the UTF-8 byte count, however dense the text", () => {
    const dense = ["漢字".repeat(500), "😀".repeat(300), "a1b2c3_".repeat(200), "{[()]};:,.".repeat(200)]
    for (const text of dense) expect(inputTokenUpperBound([text])).toBeGreaterThanOrEqual(Buffer.byteLength(text, "utf8"))
  })

  it("is well above the old characters-divided-by-2.5 estimate for dense text", () => {
    const emoji = "😀".repeat(1000) // 1,000 characters (2,000 UTF-16 units), 4,000 bytes
    expect(inputTokenUpperBound([emoji])).toBeGreaterThan(Math.ceil(emoji.length / 2.5) + 50)
  })

  it("adds an allowance for each message", () => {
    expect(inputTokenUpperBound(["a", "a"])).toBeGreaterThan(inputTokenUpperBound(["aa"]))
  })
})

describe("token budget", () => {
  it("counts every kind of token", () => {
    expect(totalTokens({ input_tokens: 10, output_tokens: 5, cache_creation_input_tokens: 100, cache_read_input_tokens: 1000 })).toBe(1115)
  })

  it("refuses a call whose worst case could pass the cap", () => {
    expect(reserve(1001)).toBeNull()
    expect(reserve(1000)).not.toBeNull()
  })

  it("counts calls in flight, so two at once cannot pass the cap together", () => {
    expect(reserve(600)).not.toBeNull()
    expect(reserve(600)).toBeNull()
  })

  it("swaps the reservation for real usage and saves it", () => {
    const r = reserve(800)!
    settle(r, 300)
    expect(budgetStatus()).toMatchObject({ used: 300, reserved: 0, limit: 1000 })
    expect(JSON.parse(fs.readFileSync(file, "utf8")).used).toBe(300)
    expect(reserve(700)).not.toBeNull()
    expect(reserve(1)).toBeNull()
  })

  it("keeps the whole reservation as spent when usage is unknown", () => {
    settle(reserve(400)!, null)
    expect(budgetStatus().used).toBe(400)
  })

  it("does not settle twice", () => {
    const r = reserve(400)!
    settle(r, 100)
    settle(r, 100)
    expect(budgetStatus().used).toBe(100)
  })

  it("survives a restart by reading the saved total", () => {
    settle(reserve(900)!, 900)
    resetBudgetForTests()
    expect(budgetStatus().used).toBe(900)
    expect(reserve(200)).toBeNull()
  })

  it("refuses everything when the budget is unreadable", () => {
    process.env.CHAT_TOKEN_BUDGET = "oops"
    expect(reserve(1)).toBeNull()
  })

  it("has no cap when the budget is unset, but still keeps count", () => {
    delete process.env.CHAT_TOKEN_BUDGET
    settle(reserve(10_000_000)!, 5)
    expect(budgetStatus()).toMatchObject({ used: 5, limit: null })
  })

  it("lets a reservation of a vanished process expire so it cannot block the budget forever", () => {
    vi.spyOn(Date, "now").mockReturnValue(1_000_000)
    expect(reserve(900)).not.toBeNull()
    expect(reserve(900)).toBeNull()
    vi.spyOn(Date, "now").mockReturnValue(1_000_000 + 4 * 60_000)
    expect(reserve(900)).not.toBeNull()
  })
})

describe("when real usage is above the reservation", () => {
  it("records the real number in full, even past the budget", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined)
    const r = reserve(300)!
    settle(r, 1500)
    expect(budgetStatus()).toMatchObject({ used: 1500, reserved: 0, limit: 1000 })
    expect(warn).toHaveBeenCalledOnce()
  })

  it("refuses every later call until the count is reset", () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined)
    settle(reserve(300)!, 1500)
    expect(reserve(1)).toBeNull()
    fs.rmSync(file)
    expect(reserve(1)).not.toBeNull()
  })

  it("gives back an overestimate: a small real usage frees the rest of the reservation", () => {
    settle(reserve(900)!, 50)
    expect(reserve(900)).not.toBeNull()
  })
})

describe("when the usage file is damaged or unusable", () => {
  it.each([
    ["not JSON", "{oops"],
    ["empty", ""],
    ["a negative total", JSON.stringify({ used: -5 })],
    ["no total", JSON.stringify({ limit: 10 })],
    ["the wrong shape", "[1,2,3]"],
  ])("refuses everything when the file is %s, instead of starting again from zero", (_name, contents) => {
    vi.spyOn(console, "error").mockImplementation(() => undefined)
    fs.writeFileSync(file, contents)
    expect(reserve(1)).toBeNull()
    expect(budgetStatus().unreadable).toBe(true)
  })

  it("does not write over a damaged file when a call settles", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined)
    const r = reserve(100)! // fine: no file yet
    fs.writeFileSync(file, "{oops")
    settle(r, 100)
    expect(fs.readFileSync(file, "utf8")).toBe("{oops")
  })

  it("recovers once the damaged file is removed", () => {
    vi.spyOn(console, "error").mockImplementation(() => undefined)
    fs.writeFileSync(file, "{oops")
    expect(reserve(1)).toBeNull()
    fs.rmSync(file)
    expect(reserve(1)).not.toBeNull()
  })

  it("counts in this process's memory when the file cannot be written, and says so once", () => {
    const error = vi.spyOn(console, "error").mockImplementation(() => undefined)
    // A regular file where the folder should be: creating the usage file under it always fails.
    const blocker = path.join(dir, "not-a-folder")
    fs.writeFileSync(blocker, "")
    process.env.CHAT_USAGE_FILE = path.join(blocker, "usage.json")

    expect(reserve(600)).not.toBeNull()
    expect(reserve(600)).toBeNull() // still enforced within the process
    settle(reserve(100)!, 100)
    expect(budgetStatus().used).toBe(100)
    expect(error).toHaveBeenCalledOnce()
  })

  it("reads a total saved by the older file format", () => {
    fs.writeFileSync(file, JSON.stringify({ used: 950, limit: 1000, updatedAt: "2026-01-01T00:00:00Z" }))
    expect(reserve(100)).toBeNull()
    expect(reserve(50)).not.toBeNull()
  })
})

// Each child process is a separate Node process with its own memory, sharing only the file.
describe("two or more server processes sharing one budget", () => {
  const budgetModule = fileURLToPath(new URL("./budget.ts", import.meta.url))

  function runWorkers(count: number, script: string): Promise<string[]> {
    const workerFile = path.join(dir, "worker.mjs")
    fs.writeFileSync(workerFile, `import { reserve, settle } from ${JSON.stringify(budgetModule)}\n${script}`)
    return Promise.all(
      Array.from({ length: count }, () =>
        new Promise<string>((resolve, reject) => {
          const child = spawn(process.execPath, ["--no-warnings", workerFile], { env: process.env, stdio: ["ignore", "pipe", "pipe"] })
          let out = ""
          let err = ""
          child.stdout.on("data", (d) => (out += d))
          child.stderr.on("data", (d) => (err += d))
          child.on("error", reject)
          child.on("close", (code) => (code === 0 ? resolve(out.trim()) : reject(new Error(`worker exited ${code}: ${err}`))))
        }),
      ),
    )
  }

  it("cannot reserve more than the budget between them", async () => {
    // Budget 1000, reservations of 300: at most 3 can ever be held at once, across all processes.
    const results = await runWorkers(4, `let won = 0\nfor (let i = 0; i < 10; i++) if (reserve(300)) won++\nconsole.log(won)`)
    const total = results.reduce((sum, n) => sum + Number(n), 0)
    expect(total).toBe(3)
    expect(budgetStatus().reserved).toBe(900)
  }, 30_000)

  it("loses no updates when processes settle at the same time", async () => {
    process.env.CHAT_TOKEN_BUDGET = "10000000"
    await runWorkers(4, `for (let i = 0; i < 25; i++) { const r = reserve(10); settle(r, 10) }`)
    expect(budgetStatus()).toMatchObject({ used: 1000, reserved: 0 })
  }, 30_000)

  it("sees another process's spending", async () => {
    await runWorkers(1, `settle(reserve(800), 800)`)
    expect(reserve(300)).toBeNull()
    expect(budgetStatus().used).toBe(800)
  }, 30_000)
})
