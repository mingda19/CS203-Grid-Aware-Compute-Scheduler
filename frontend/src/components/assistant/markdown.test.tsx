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

  describe("malformed or hostile model output", () => {
    it("shows links as plain text: no anchor, no javascript: URL", () => {
      const out = html("[click](javascript:alert(1)) and <a href=\"javascript:alert(1)\">x</a> and https://example.com")
      expect(out).not.toMatch(/<a[\s>]/)
      expect(out).toContain("&lt;a href=")
      expect(out).toContain("[click](javascript:alert(1))")
    })

    it("shows images and raw tags as text", () => {
      const out = html("![x](http://evil/x.png) <script>alert(1)</script> <iframe src=x></iframe>")
      expect(out).not.toMatch(/<(img|script|iframe)/)
      expect(out).toContain("&lt;script&gt;")
    })

    it("leaves an unclosed bold or code marker as plain text", () => {
      expect(html("Click **New job")).toContain("Click **New job")
      expect(html("Use `Start time")).toContain("Use `Start time")
      expect(html("Click **New job")).not.toContain("<strong")
    })

    it("does not bold across lines or inside code", () => {
      expect(html("**one\ntwo**")).not.toContain("<strong")
      const out = html("`**not bold**`")
      expect(out).not.toContain("<strong")
      expect(out).toContain("**not bold**")
    })

    it("renders nothing for empty or blank text without failing", () => {
      expect(html("")).toBe("<div class=\"space-y-2\"></div>")
      expect(html(" \n\n \n")).toBe("<div class=\"space-y-2\"></div>")
    })

    it("handles a list that switches kind, and an indented continuation line", () => {
      const out = html("1. first\n   still first\n- bullet\n2) second")
      expect(out.match(/<ol/g)).toHaveLength(2)
      expect(out).toContain("<ul")
      expect(out).toContain("first still first")
    })

    it("keeps lone markers and odd bullets from hanging or crashing", () => {
      for (const text of ["-", "- ", "1.", "#", "# ", "***", "``", "**", "\u2022 item", "-\t\titem", "\r\n\r\n"]) {
        expect(() => html(text)).not.toThrow()
      }
    })

    it("stays fast on a very long reply and on pathological repeats", () => {
      const started = Date.now()
      html(`${"**a** `b` ".repeat(5000)}\n${"*".repeat(50_000)}\n${"`".repeat(50_000)}`)
      html("- x\n".repeat(5000))
      expect(Date.now() - started).toBeLessThan(2000)
    })

    it("does not execute or interpret entities in text", () => {
      const out = html("&lt;b&gt; &#60;script&#62;")
      expect(out).not.toContain("<script")
      expect(out).toContain("&amp;lt;b&amp;gt;")
    })
  })
})
