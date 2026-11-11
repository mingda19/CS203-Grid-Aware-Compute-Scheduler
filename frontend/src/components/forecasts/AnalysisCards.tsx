"use client"

import * as React from "react"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { MARKET_ZONE_LABEL } from "@/lib/date-utils"

interface ChartCardProps {
  title: string
  /** One plain line under the title saying what the chart shows. */
  description: string
  /** Switches on the right of the title. Below sm they move under it as one group. */
  controls?: React.ReactNode
  /** The chart's height, so the loading skeleton keeps the card's size. */
  height: number
  /** What the card loads, for "Could not load …" and the skeleton's label, for example "forecast accuracy". */
  subject: string
  loading: boolean
  error: boolean
  onRetry: () => void
  /** One muted line shown instead of the chart, or null when there is something to chart. */
  empty?: string | null
  children: React.ReactNode
}

/** A chart card on the Forecast page: a title and description, then the chart or its loading, error or empty state (STYLE §7). */
export function ChartCard({ title, description, controls, height, subject, loading, error, onRetry, empty, children }: ChartCardProps) {
  return (
    <Card className="min-w-0 overflow-hidden">
      <div className="flex min-w-0 flex-col gap-3 p-4 pb-2 sm:flex-row sm:items-start sm:justify-between sm:p-5 sm:pb-2">
        <div className="min-w-0 flex-1">
          <h2 className="text-sm font-semibold leading-5">{title}</h2>
          <p className="text-xs leading-4 text-muted-foreground">{description}</p>
        </div>
        {controls && <div className="flex shrink-0 flex-nowrap items-center gap-2">{controls}</div>}
      </div>
      <div className="min-w-0 px-2 pb-4 sm:px-4 sm:pb-5">
        {loading ? (
          <Skeleton className="w-full" style={{ height }} role="status" aria-label={`Loading ${subject}`} />
        ) : error ? (
          <div className="flex items-center justify-between gap-2 px-2 py-6">
            <span className="text-sm leading-5 text-muted-foreground">Could not load {subject}.</span>
            <Button variant="outline" size="sm" className="shrink-0" onClick={onRetry}>Retry</Button>
          </div>
        ) : empty ? (
          <p className="px-2 py-6 text-sm leading-5 text-muted-foreground">{empty}</p>
        ) : (
          children
        )}
      </div>
    </Card>
  )
}

/** The legend row under a chart. The unit comes last, once. */
export function ChartLegend({ children }: { children: React.ReactNode }) {
  return (
    <ul className="mt-2 flex flex-wrap items-center gap-x-4 gap-y-1 px-2 text-xs leading-4 text-muted-foreground">
      {children}
      <li className="whitespace-nowrap">$/MWh, {MARKET_ZONE_LABEL}</li>
    </ul>
  )
}
