"use client"

import * as React from "react"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Progress } from "@/components/ui/progress"
import { Skeleton } from "@/components/ui/skeleton"
import { cn } from "@/lib/utils"

interface StatCardProps {
  /** Short caption. May hold a help marker. */
  label: React.ReactNode
  /** The one large number, for example "62%". Use "—" when there is none. */
  value: string
  /** Small unit on the same line as the number, for example "/MWh". */
  unit?: string
  /** One supporting line. It truncates and its full text shows as a tooltip. */
  supporting: string
  /** An icon in front of the line when it states a trend or a warning. The text always carries the meaning. */
  supportingIcon?: React.ReactNode
  /** 0 to 100. The bar stops at 100 even if the value is higher. */
  progress?: number | null
  /** Warning state: colours the number with the warning token. Pair it with an icon in `supporting`. */
  warn?: boolean
  loading?: boolean
  error?: boolean
  onRetry?: () => void
}

/** Same structure for every stat: caption, one number, one supporting line, optional bar. */
export function StatCard({ label, value, unit, supporting, supportingIcon, progress, warn, loading, error, onRetry }: StatCardProps) {
  return (
    <Card className="min-w-0">
      <CardContent className="min-w-0 space-y-2 p-4 pt-4 sm:p-5 sm:pt-5">
        <div className="flex min-w-0 items-center gap-1.5 text-xs leading-4 text-muted-foreground">{label}</div>
        {loading ? (
          <div className="space-y-2" role="status" aria-label="Loading">
            <Skeleton className="h-9 w-24" />
            <Skeleton className="h-4 w-40 max-w-full" />
          </div>
        ) : (
          <>
            <div className="flex min-w-0 items-baseline gap-1.5">
              <span
                className={cn(
                  "min-w-0 truncate text-2xl font-semibold leading-8 tabular-nums sm:text-3xl sm:leading-9",
                  warn ? "text-warning-foreground" : "text-foreground",
                )}
              >
                {error ? "—" : value}
              </span>
              {unit && !error && <span className="shrink-0 text-xs leading-4 text-muted-foreground">{unit}</span>}
            </div>
            {error ? (
              <div className="flex min-w-0 items-center justify-between gap-2">
                <span className="min-w-0 truncate text-xs leading-4 text-muted-foreground">Could not load</span>
                {onRetry && <Button variant="outline" size="sm" className="h-7 shrink-0 px-2 text-xs" onClick={onRetry}>Retry</Button>}
              </div>
            ) : (
              <div
                className={cn(
                  "flex min-w-0 items-center gap-1.5 text-xs leading-4 [&>svg]:h-3.5 [&>svg]:w-3.5 [&>svg]:shrink-0",
                  warn ? "text-warning-foreground" : "text-muted-foreground",
                )}
              >
                {supportingIcon}
                <span className="min-w-0 truncate" title={supporting}>{supporting}</span>
              </div>
            )}
            {progress != null && !error && (
              <Progress
                value={Math.min(100, Math.max(0, progress))}
                className="h-2"
                aria-label={typeof label === "string" ? label : undefined}
              />
            )}
          </>
        )}
      </CardContent>
    </Card>
  )
}
