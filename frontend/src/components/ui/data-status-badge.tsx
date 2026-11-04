"use client"

import * as React from "react"
import { Radio, Database, AlertCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"

export type DataStatusType = "live" | "cached" | "unavailable"

export interface DataStatusBadgeProps {
  status: DataStatusType
  updatedAt?: string | Date | null
  source?: string
  className?: string
  size?: "sm" | "default"
  showTooltip?: boolean
}

const statusConfig: Record<
  DataStatusType,
  {
    label: string
    dotClass: string
    badgeClass: string
    icon: React.ComponentType<{ className?: string }>
    defaultSource: string
  }
> = {
  live: {
    label: "Live",
    dotClass: "bg-emerald-500 animate-pulse",
    badgeClass: "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border-emerald-500/20",
    icon: Radio,
    defaultSource: "Real-time ERCOT telemetry endpoint",
  },
  cached: {
    label: "Cached",
    dotClass: "bg-sky-500",
    badgeClass: "bg-sky-500/10 text-sky-600 dark:text-sky-400 border-sky-500/20",
    icon: Database,
    defaultSource: "PostgreSQL query cache",
  },
  unavailable: {
    label: "Unavailable",
    dotClass: "bg-rose-500",
    badgeClass: "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20",
    icon: AlertCircle,
    defaultSource: "Telemetry feed unreachable or offline",
  },
}

import { formatMarketDateTime, formatRelativeAgo } from "@/lib/date-utils"

export function DataStatusBadge({
  status,
  updatedAt,
  source,
  className,
  size = "default",
  showTooltip = true,
}: DataStatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.unavailable
  const [now, setNow] = React.useState(() => Date.now())
  const relativeText = updatedAt ? formatRelativeAgo(updatedAt, now) : ""
  React.useEffect(() => {
    const timer = setInterval(() => setNow(Date.now()), 15000)
    return () => clearInterval(timer)
  }, [])

  const effectiveSource = source || config.defaultSource
  const Icon = config.icon

  const badgeNode = (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-full border font-mono font-medium tracking-tight select-none transition-colors",
        size === "sm" ? "px-2 py-0.5 text-[10px]" : "px-2.5 py-1 text-xs",
        config.badgeClass,
        className
      )}
    >
      <span className={cn("h-1.5 w-1.5 rounded-full shrink-0", config.dotClass)} />
      <span>{config.label}</span>
      {relativeText && (
        <>
          <span className="opacity-40">·</span>
          <span className="font-sans text-[11px] font-normal opacity-85">{relativeText}</span>
        </>
      )}
    </span>
  )

  if (!showTooltip) {
    return badgeNode
  }

  const rawUtcIso = updatedAt
    ? typeof updatedAt === "string"
      ? updatedAt
      : updatedAt.toISOString()
    : "Timestamp unavailable"

  const marketDisplay = updatedAt ? formatMarketDateTime(updatedAt) : "Timestamp unavailable"

  return (
    <TooltipProvider delayDuration={150}>
      <Tooltip>
        <TooltipTrigger asChild>{badgeNode}</TooltipTrigger>
        <TooltipContent
          side="top"
          className="max-w-xs space-y-1 p-2.5 text-xs bg-popover/95 backdrop-blur-sm border-border shadow-lg"
        >
          <div className="flex items-center gap-1.5 font-semibold text-foreground">
            <Icon className="h-3.5 w-3.5 text-muted-foreground" />
            <span>Data Provenance: {config.label}</span>
          </div>
          <p className="text-muted-foreground text-[11px] leading-snug">
            {effectiveSource}
          </p>
          <div className="text-[10px] text-muted-foreground/90 font-mono pt-1 border-t border-border/50 space-y-0.5">
            <div>Local: <span className="text-foreground font-semibold">{marketDisplay}</span></div>
            <div className="text-[9px] opacity-70">UTC ISO: {rawUtcIso}</div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
