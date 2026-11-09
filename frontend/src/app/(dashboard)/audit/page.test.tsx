import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { expect, it, vi } from "vitest"
import AuditPage from "./page"

vi.mock("next/navigation", () => ({ useRouter: () => ({ push: vi.fn(), replace: vi.fn() }) }))

it("has five table columns, with no Payload or Operator column, and its loading row spans all five", () => {
  const html = renderToStaticMarkup(<AuditPage />)
  expect(html).not.toContain("Payload")
  expect(html).not.toContain("Operator")
  expect(html.match(/<th /g)).toHaveLength(5)
  expect(html).toContain('colSpan="5"')
})
