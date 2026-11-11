import fs from "node:fs"
import os from "node:os"
import path from "node:path"
import { afterEach, beforeEach, describe, expect, it } from "vitest"
import { budgetStatus, parseBudget, reserve, resetBudgetForTests, settle, totalTokens } from "./budget"

let file: string

beforeEach(() => {
  file = path.join(fs.mkdtempSync(path.join(os.tmpdir(), "gacs-budget-")), "usage.json")
  process.env.CHAT_USAGE_FILE = file
  process.env.CHAT_TOKEN_BUDGET = "1000"
  resetBudgetForTests()
})

afterEach(() => {
  delete process.env.CHAT_USAGE_FILE
  delete process.env.CHAT_TOKEN_BUDGET
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
})
