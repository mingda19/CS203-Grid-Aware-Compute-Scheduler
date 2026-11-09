import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { expect, it, vi } from "vitest"
import AuditPage from "./page"

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

it("has six table columns, with no Payload column, and its loading row spans all six", () => {
  const html = renderToStaticMarkup(<AuditPage />)
  expect(html).not.toContain("Payload")
  expect(html.match(/<th /g)).toHaveLength(6)
  expect(html).toContain('colSpan="6"')
})
