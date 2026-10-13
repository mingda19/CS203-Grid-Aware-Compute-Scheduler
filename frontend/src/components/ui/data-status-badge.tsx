"use client"

import * as React from "react"
import { Radio, Database, Sparkles, AlertCircle, Info } from "lucide-react"
import { cn } from "@/lib/utils"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"

export type DataStatusType = "live" | "cached" | "demo" | "unavailable"

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
  demo: {
    label: "Demo data",
    dotClass: "bg-purple-500",
    badgeClass: "bg-purple-500/10 text-purple-600 dark:text-purple-400 border-purple-500/20",
    icon: Sparkles,
    defaultSource: "Simulated scenario & benchmark data",
  },
  unavailable: {
    label: "Unavailable",
    dotClass: "bg-rose-500",
    badgeClass: "bg-rose-500/10 text-rose-600 dark:text-rose-400 border-rose-500/20",
    icon: AlertCircle,
    defaultSource: "Telemetry feed unreachable or offline",
  },
}

import { formatSgtDateTime, formatSgtRelativeAgo } from "@/lib/date-utils"

export function DataStatusBadge({
  status,
  updatedAt,
  source,
  className,
  size = "default",
  showTooltip = true,
}: DataStatusBadgeProps) {
  const config = statusConfig[status] || statusConfig.demo
  const [relativeText, setRelativeText] = React.useState(() => (updatedAt ? formatSgtRelativeAgo(updatedAt) : ""))

  React.useEffect(() => {
    if (!updatedAt) return
    setRelativeText(formatSgtRelativeAgo(updatedAt))
    const timer = setInterval(() => {
      setRelativeText(formatSgtRelativeAgo(updatedAt))
    }, 15000)
    return () => clearInterval(timer)
  }, [updatedAt])

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
    : "Static / Mocked"

  const sgtDisplay = updatedAt ? formatSgtDateTime(updatedAt) : "Static / Mocked"

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
            <div>SGT: <span className="text-foreground font-semibold">{sgtDisplay}</span></div>
            <div className="text-[9px] opacity-70">UTC ISO: {rawUtcIso}</div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
