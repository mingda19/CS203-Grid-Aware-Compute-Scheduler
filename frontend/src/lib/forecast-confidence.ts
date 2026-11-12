/**
 * Forecast confidence banding (contract FR-10: "expose forecast confidence and data-freshness
 * indicators… The UI distinguishes high-confidence, low-confidence, and unavailable periods").
 *
 * The API serves `confidenceScore` per forecast interval on a 0-100 scale, and omits it while no
 * error reference exists for that model version. `priceApi.getForecast` (api.ts) divides it by 100,
 * so every score this module sees is 0-1. A bare
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

/** "82%, High", or "Not available" when the model has no error reference for the interval. */
export function formatConfidence(score: number | null | undefined): string {
  const band = confidenceBand(score)
  return band === "Not available" ? band : `${Math.round((score as number) * 100)}%, ${band}`
}
