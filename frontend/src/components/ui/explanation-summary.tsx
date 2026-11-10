import * as React from "react"
import { cn } from "@/lib/utils"
import { parseExplanationSummary } from "@/lib/explanation-summary"

/**
 * Renders the optimiser's run summary as a list.
 *
 * The summary is point form, so it is marked up as a real `<ul>`: a screen reader announces how
 * many findings there are, and the closing caveat stays visually distinct from the facts above it
 * rather than being one more line in a wall of text. Text is interpolated normally, never as raw
 * HTML - it is server-generated, but the rule applies to every free-text field here.
 */
export function ExplanationSummary({
  text,
  className,
  emptyText = "No summary was recorded for this run.",
}: {
  text: string | null | undefined
  className?: string
  emptyText?: string
}) {
  const { bullets, notes } = parseExplanationSummary(text)

  if (!bullets.length && !notes.length) {
    return <p className={cn("text-xs text-muted-foreground", className)}>{emptyText}</p>
  }

  return (
    <div className={cn("space-y-2", className)}>
      {bullets.length > 0 && (
        <ul className="space-y-1">
          {bullets.map((line, i) => (
            <li key={i} className="flex gap-2 text-xs leading-relaxed text-foreground">
              <span aria-hidden="true" className="select-none text-muted-foreground">•</span>
              <span>{line}</span>
            </li>
          ))}
        </ul>
      )}
      {notes.map((note, i) => (
        <p key={i} className="text-xs italic leading-relaxed text-muted-foreground">{note}</p>
      ))}
    </div>
  )
}
