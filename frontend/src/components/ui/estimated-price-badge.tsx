import { Info } from "lucide-react"

// Shared flag (masterplan section 5): icon + text, never color alone.
export function EstimatedPriceBadge() {
  return (
    <span
      title="Prices for part of this window are estimated from history, not forecast. The plan can change when forecast data arrives."
      className="inline-flex items-center gap-1 rounded-full border border-amber-500/40 bg-amber-500/10 px-2 py-0.5 text-xs font-medium text-amber-700 dark:text-amber-300"
    >
      <Info className="h-3 w-3" aria-hidden="true" />
      Estimated prices
    </span>
  )
}
