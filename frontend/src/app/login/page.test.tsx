import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { expect, it, vi } from "vitest"
import LoginPage from "./page"

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

it("offers normal credential sign-in without demo account shortcuts", () => {
  const html = renderToStaticMarkup(<LoginPage />)
  expect(html).toContain('id="login-email"')
  expect(html).toContain('id="login-password"')
  expect(html).toContain("Create one")
  expect(html).not.toMatch(/Launch Demo|sandbox|pre-seeded|Select Demo Persona/i)
})
