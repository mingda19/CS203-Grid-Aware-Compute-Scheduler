"use client"

import { HelpCircle } from "lucide-react"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"

// The shared "?" help marker (masterplan section 5). A real button so keyboard users can focus it.
export function HelpTip({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <TooltipProvider>
      <Tooltip>
        <TooltipTrigger asChild>
          <button type="button" aria-label={`Help: ${label}`} className="inline-flex align-middle text-muted-foreground hover:text-foreground">
            <HelpCircle className="h-3.5 w-3.5" />
          </button>
        </TooltipTrigger>
        <TooltipContent className="max-w-xs text-xs">{children}</TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
