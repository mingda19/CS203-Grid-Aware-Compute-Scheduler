import * as React from "react"

/**
 * Just enough markdown for the assistant's replies: paragraphs, bullet and numbered lists, bold and
 * inline code. Everything is built as React elements, never as HTML, so model output cannot inject
 * markup. Headings are shown as bold paragraphs.
 */

const INLINE = /(`[^`\n]+`|\*\*[^*\n]+\*\*)/g
const BULLET = /^\s*[-*•]\s+/
const NUMBERED = /^\s*\d+[.)]\s+/
const HEADING = /^#{1,6}\s+/

function inline(text: string): React.ReactNode[] {
  const nodes: React.ReactNode[] = []
  let last = 0
  for (const match of text.matchAll(INLINE)) {
    const index = match.index ?? 0
    if (index > last) nodes.push(text.slice(last, index))
    const token = match[0]
    nodes.push(
      token.startsWith("`") ? (
        <code key={index} className="rounded bg-background px-1 font-mono text-xs">{token.slice(1, -1)}</code>
      ) : (
        <strong key={index} className="font-semibold">{token.slice(2, -2)}</strong>
      ),
    )
    last = index + token.length
  }
  if (last < text.length) nodes.push(text.slice(last))
  return nodes
}

export function Markdown({ text }: { text: string }) {
  const lines = text.split("\n")
  const blocks: React.ReactNode[] = []
  let i = 0
  while (i < lines.length) {
    const line = lines[i]
    if (!line.trim()) {
      i++
      continue
    }
    if (HEADING.test(line)) {
      blocks.push(<p key={blocks.length} className="font-semibold">{inline(line.replace(HEADING, "").trim())}</p>)
      i++
      continue
    }
    const listMarker = BULLET.test(line) ? BULLET : NUMBERED.test(line) ? NUMBERED : null
    if (listMarker) {
      const items: string[] = []
      while (i < lines.length && lines[i].trim()) {
        if (listMarker.test(lines[i])) items.push(lines[i].replace(listMarker, ""))
        else if (/^\s+/.test(lines[i]) && items.length > 0) items[items.length - 1] += ` ${lines[i].trim()}`
        else break
        i++
      }
      const List = listMarker === BULLET ? "ul" : "ol"
      blocks.push(
        <List key={blocks.length} className={`${listMarker === BULLET ? "list-disc" : "list-decimal"} space-y-1 pl-5`}>
          {items.map((item, n) => <li key={n}>{inline(item)}</li>)}
        </List>,
      )
      continue
    }
    const paragraph: string[] = []
    while (i < lines.length && lines[i].trim() && !BULLET.test(lines[i]) && !NUMBERED.test(lines[i]) && !HEADING.test(lines[i])) {
      paragraph.push(lines[i].trim())
      i++
    }
    blocks.push(
      <p key={blocks.length}>
        {paragraph.map((part, n) => (
          <React.Fragment key={n}>
            {n > 0 && <br />}
            {inline(part)}
          </React.Fragment>
        ))}
      </p>,
    )
  }
  return <div className="space-y-2">{blocks}</div>
}
