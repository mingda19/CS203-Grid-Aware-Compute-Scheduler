"use client"

import { Info } from "lucide-react"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"

const NOTE = "Prices for part of this window are estimated from history, not forecast. The plan can change when forecast data arrives."

// Shared flag (masterplan section 5): icon + text, never color alone.
// compact: bare icon (text stays for screen readers) with an instant popup note on hover or focus.
export function EstimatedPriceBadge({ compact = false }: { compact?: boolean }) {
  if (compact) {
    return (
      <TooltipProvider>
        <Tooltip delayDuration={0}>
          <TooltipTrigger asChild>
            <button type="button" className="inline-flex shrink-0 text-warning-foreground">
              <Info className="h-4 w-4" aria-hidden="true" />
              <span className="sr-only">Estimated prices</span>
            </button>
          </TooltipTrigger>
          <TooltipContent className="max-w-xs text-xs">{NOTE}</TooltipContent>
        </Tooltip>
      </TooltipProvider>
    )
  }
  return (
    <span
      title={NOTE}
      className="inline-flex shrink-0 items-center whitespace-nowrap gap-1 rounded-full border border-warning/40 bg-warning/10 px-2 py-0.5 text-xs font-medium text-warning-foreground"
    >
      <Info className="h-3 w-3" aria-hidden="true" />
      Estimated prices
    </span>
  )
}
