/**
 * Forecast confidence banding (contract FR-10: "expose forecast confidence and data-freshness
 * indicators… The UI distinguishes high-confidence, low-confidence, and unavailable periods").
 *
 * The API serves `confidenceScore` per forecast interval, 0-1, normalised from the model's stored
 * forecast error ratio, and omits it while no error reference exists for that model version. A bare
 * percentage does not meet FR-10 on its own — it has to read as a judgement an operator can act on
 * before approving a schedule — so a score maps to one of three bands, and absence is its own band
 * rather than being shown as zero (FR-04: missing values must not silently become zeros).
 */

export type ConfidenceBand = "High" | "Moderate" | "Low" | "Not available"

/** At or above this, the model's recent error is small enough to plan on directly. */
export const HIGH_CONFIDENCE_FROM = 0.8
/** Below this, treat the interval as indicative only. */
export const MODERATE_CONFIDENCE_FROM = 0.5

export function confidenceBand(score: number | null | undefined): ConfidenceBand {
  if (score == null || !Number.isFinite(score)) return "Not available"
  if (score >= HIGH_CONFIDENCE_FROM) return "High"
  if (score >= MODERATE_CONFIDENCE_FROM) return "Moderate"
  return "Low"
}

/** Tailwind classes for a band, matching the dashboard's emerald / amber / rose signalling. */
export function confidenceTone(score: number | null | undefined): string {
  switch (confidenceBand(score)) {
    case "High": return "text-emerald-600 dark:text-emerald-400"
    case "Moderate": return "text-amber-600 dark:text-amber-400"
    case "Low": return "text-rose-600 dark:text-rose-400"
    default: return "text-muted-foreground"
  }
}

/** "82%, High", or "Not available" when the model has no error reference for the interval. */
export function formatConfidence(score: number | null | undefined): string {
  const band = confidenceBand(score)
  return band === "Not available" ? band : `${Math.round((score as number) * 100)}%, ${band}`
}

/**
 * The run-level figure for a set of forecast intervals: the mean of those that have a score, and how
 * many did not. Scored intervals are averaged rather than the whole horizon being treated as scored,
 * so a mostly-unscored horizon cannot look confident; `missing` is what the caller surfaces to say
 * how much of the horizon the number actually covers.
 */
export function aggregateConfidence(scores: Array<number | null | undefined>): {
  mean: number | null
  scored: number
  missing: number
} {
  const usable = scores.filter((s): s is number => s != null && Number.isFinite(s))
  return {
    mean: usable.length ? usable.reduce((total, s) => total + s, 0) / usable.length : null,
    scored: usable.length,
    missing: scores.length - usable.length,
  }
}
