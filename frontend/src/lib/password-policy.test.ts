import { expect, it } from "vitest"
import { passwordError } from "./password-policy"
it("accepts passphrases and the BCrypt byte boundary", () => {
  expect(passwordError("river lantern orbit meadow")).toBeNull()
  expect(passwordError("x".repeat(72))).toBeNull()
})
it("rejects short, common, and oversized passwords including Unicode", () => {
  for (const password of ["P@ssw0rd!", "PASSWORDPASSWORD", "x".repeat(73), "é".repeat(37), "😀".repeat(8)])
    expect(passwordError(password)).not.toBeNull()
})
