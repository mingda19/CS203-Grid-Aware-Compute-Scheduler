import type { ConstraintDisagreement, ConstraintDraft, ConstraintParseResponse } from "./api"

/**
 * The operator's answer to each disagreement, keyed by field.
 *
 * Keyed by field rather than by index so an answer cannot silently attach to a different question
 * when a re-parse returns the disagreements in another order. A field appears at most once.
 */
export type DisagreementAnswers = Partial<Record<keyof ConstraintDraft, string>>

export function constraintReviewBlocker(
  result: ConstraintParseResponse,
  reviewedDraft: ConstraintDraft,
  currentDraft: ConstraintDraft,
  answers: DisagreementAnswers = {},
): string | null {
  if (JSON.stringify(reviewedDraft) !== JSON.stringify(currentDraft)) return "The job fields changed. Parse again to review the latest values."
  if (result.status === "INVALID" || result.validationError) return result.validationError || "The proposed values are invalid. Reword the constraints and parse again."
  // Before the generic messages below: an unanswered disagreement is the most specific thing wrong,
  // and it names what was actually seen. "No supported constraints were found" would be misleading
  // here - something was found, by one reader and not the other.
  const unanswered = (result.disagreements ?? []).filter(d => !answers[d.field])
  if (unanswered.length) {
    return unanswered.length === 1
      ? "Answer the question below about what the text means, then confirm."
      : `Answer the ${unanswered.length} questions below about what the text means, then confirm.`
  }
  if (result.ambiguities.length) return "Clarify the questions below in your text, then parse again."
  if (result.unsupported.length) return "Remove or reword the unsupported text below, then parse again."
  if (!result.evidence.length) return "No supported constraints were found. Reword your text and parse again."
  if (result.conflicts.some(conflict => !conflict.phrases.some(phrase => phrase.startsWith("form: ")))) return "The text gives conflicting values. Reword it and parse again."
  if (result.fields.originalStartTime && !/:00:00(?:\.0+)?Z?$/.test(result.fields.originalStartTime)) return "The start time must be on the hour. Reword the start time and parse again."
  return null
}

/**
 * The proposed values with the operator's disagreement answers applied.
 *
 * Without this the selection would be cosmetic: the parser's value sits in `result.fields` whatever
 * the operator chose, so answering "remove it" has to actually remove it here. `DROP`, `IGNORE` and
 * `ENTER_VALUE` put the field back to what the form held before the parse (`draft`): the operator
 * said the text does not set it, or that they will set it themselves, so a value the parser guessed
 * must not stand in for it. Clearing the field instead would also wipe a value the operator had
 * already typed into the form.
 */
export function applyDisagreementAnswers(
  fields: ConstraintDraft,
  disagreements: ConstraintDisagreement[],
  answers: DisagreementAnswers,
  draft: ConstraintDraft,
): ConstraintDraft {
  let result = { ...fields }
  for (const disagreement of disagreements) {
    const answer = answers[disagreement.field]
    if (answer === "DROP" || answer === "IGNORE" || answer === "ENTER_VALUE") {
      result = { ...result, [disagreement.field]: draft[disagreement.field] }
    }
  }
  return result
}
