/**
 * Splits the optimiser's run summary into its bullet lines and its closing notes.
 *
 * <p>The backend writes the summary as point form: lines beginning "- " are the facts of the run,
 * and any line that does not is a caveat closing it off (always at least the reminder that the
 * figures come from a price forecast). Parsing it here rather than rendering the raw string means
 * the UI can emit a real list instead of a block of preformatted text, and means a summary that
 * arrives in some other shape - an older stored run, say - still renders as readable paragraphs
 * instead of disappearing.
 */
export interface ExplanationSummary {
  /** Facts about the run, already stripped of their leading "- ". */
  bullets: string[]
  /** Trailing caveats, in order. */
  notes: string[]
}

export function parseExplanationSummary(text: string | null | undefined): ExplanationSummary {
  const bullets: string[] = []
  const notes: string[] = []
  for (const raw of (text ?? "").split("\n")) {
    const line = raw.trim()
    if (!line) continue
    if (line.startsWith("- ")) bullets.push(line.slice(2).trim())
    else notes.push(line)
  }
  return { bullets, notes }
}
