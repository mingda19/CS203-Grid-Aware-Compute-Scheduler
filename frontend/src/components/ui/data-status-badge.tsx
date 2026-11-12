"use client"

import * as React from "react"
import { CheckCircle2, Clock, AlertCircle } from "lucide-react"
import { cn } from "@/lib/utils"
import { Badge } from "@/components/ui/badge"
import { Tooltip, TooltipContent, TooltipProvider, TooltipTrigger } from "@/components/ui/tooltip"
import { formatMarketDateTime, formatRelativeAgo } from "@/lib/date-utils"

/** `cached` means the data is old (delayed). Do not pass it while data loads: show a skeleton or nothing. */
export type DataStatusType = "live" | "cached" | "unavailable"

export interface DataStatusBadgeProps {
  status: DataStatusType
  updatedAt?: string | Date | null
  source?: string
  className?: string
  size?: "sm" | "default"
  showTooltip?: boolean
  /** Shown last inside the badge, for example the top bar's open arrow. */
  children?: React.ReactNode
}

// Each state has an icon and a text label, so colour never carries the meaning alone.
const statusConfig: Record<
  DataStatusType,
  {
    label: string
    badgeClass: string
    icon: React.ComponentType<{ className?: string }>
    defaultSource: string
  }
> = {
  live: {
    label: "Up to date",
    badgeClass: "border-success-foreground/30 bg-success/10 text-success-foreground",
    icon: CheckCircle2,
    defaultSource: "Real-time ERCOT telemetry endpoint",
  },
  cached: {
    label: "Delayed",
    badgeClass: "border-warning-foreground/30 bg-warning/10 text-warning-foreground",
    icon: Clock,
    defaultSource: "PostgreSQL query cache",
  },
  unavailable: {
    label: "Unavailable",
    badgeClass: "border-destructive-foreground/30 bg-destructive/10 text-destructive-foreground",
    icon: AlertCircle,
    defaultSource: "Telemetry feed unreachable or offline",
  },
}

export function DataStatusBadge({
  status,
  updatedAt,
  source,
  className,
  size = "default",
  showTooltip = true,
  children,
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

  // A badge never wraps: whitespace-nowrap and shrink-0. Its neighbour truncates instead.
  const badgeNode = (
    <Badge
      variant="outline"
      className={cn(
        "shrink-0 gap-1.5 whitespace-nowrap select-none",
        size === "sm" ? "px-2 py-0.5" : "px-2.5 py-1",
        config.badgeClass,
        className
      )}
    >
      <Icon className="h-3.5 w-3.5 shrink-0" aria-hidden="true" />
      <span>{config.label}</span>
      {relativeText && <span className="ml-1 font-normal">{relativeText}</span>}
      {children}
    </Badge>
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
          collisionPadding={8}
          className="max-w-xs space-y-1 p-2.5 text-xs leading-4 bg-popover border-border shadow-lg"
        >
          <div className="flex items-center gap-1.5 font-semibold text-foreground">
            <Icon className="h-3.5 w-3.5 shrink-0 text-muted-foreground" aria-hidden="true" />
            <span>{config.label}</span>
          </div>
          <p className="text-muted-foreground">{effectiveSource}</p>
          <div className="space-y-0.5 border-t border-border pt-1 text-muted-foreground">
            <div>Local: <span className="font-semibold text-foreground">{marketDisplay}</span></div>
            <div className="break-all">UTC: {rawUtcIso}</div>
          </div>
        </TooltipContent>
      </Tooltip>
    </TooltipProvider>
  )
}
