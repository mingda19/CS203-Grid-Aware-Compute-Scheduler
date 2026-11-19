import { afterEach, expect, it, vi } from "vitest"
import { readTheme } from "./theme"

afterEach(() => { vi.unstubAllGlobals() })

const withStorage = (getItem: () => string | null) =>
  vi.stubGlobal("window", { localStorage: { getItem } })

it("defaults to dark when nothing valid is saved or storage is unavailable", () => {
  expect(readTheme()).toBe("dark") // no window
  withStorage(() => null)
  expect(readTheme()).toBe("dark")
  withStorage(() => "purple")
  expect(readTheme()).toBe("dark")
  withStorage(() => { throw new Error("blocked") })
  expect(readTheme()).toBe("dark")
})

it("returns a saved choice", () => {
  for (const choice of ["light", "dark", "system"]) {
    withStorage(() => choice)
    expect(readTheme()).toBe(choice)
  }
})
