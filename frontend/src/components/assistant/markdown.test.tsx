import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, expect, it } from "vitest"
import { Markdown } from "./markdown"

const html = (text: string) => renderToStaticMarkup(<Markdown text={text} />)

describe("Markdown", () => {
  it("renders paragraphs, lists, bold and code", () => {
    const out = html("Steps:\n\n1. Click **New job**.\n2. Pick a `Start time`.\n\n- one\n- two")
    expect(out).toContain("<ol")
    expect(out).toContain("<li>Click <strong class=\"font-semibold\">New job</strong>.</li>")
    expect(out).toContain(">Start time</code>")
    expect(out).toContain("<ul")
  })

  it("never turns model output into HTML", () => {
    const out = html("<img src=x onerror=alert(1)> **<b>hi</b>**")
    expect(out).not.toContain("<img")
    expect(out).not.toContain("<b>")
    expect(out).toContain("&lt;img")
  })

  it("shows headings as bold paragraphs", () => {
    expect(html("## Approvals\nText")).toContain("<p class=\"font-semibold\">Approvals</p><p>Text</p>")
  })
})
