import type { ConstraintDraft, ConstraintParseResponse } from "./api"

export function constraintReviewBlocker(result: ConstraintParseResponse, reviewedDraft: ConstraintDraft, currentDraft: ConstraintDraft): string | null {
  if (JSON.stringify(reviewedDraft) !== JSON.stringify(currentDraft)) return "The job fields changed. Parse again to review the latest values."
  if (result.status === "INVALID" || result.validationError) return result.validationError || "The proposed values are invalid. Reword the constraints and parse again."
  if (result.ambiguities.length) return "Clarify the questions below in your text, then parse again."
  if (result.unsupported.length) return "Remove or reword the unsupported text below, then parse again."
  if (!result.evidence.length) return "No supported constraints were found. Reword your text and parse again."
  if (result.conflicts.some(conflict => !conflict.phrases.some(phrase => phrase.startsWith("form: ")))) return "The text gives conflicting values. Reword it and parse again."
  if (result.fields.originalStartTime && !/:00:00(?:\.0+)?Z?$/.test(result.fields.originalStartTime)) return "The start time must be on the hour. Reword the start time and parse again."
  return null
}
