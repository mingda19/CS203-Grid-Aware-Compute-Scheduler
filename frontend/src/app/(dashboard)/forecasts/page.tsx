"use client"

import * as React from "react"
import { TrendingDown, TrendingUp } from "lucide-react"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { Skeleton } from "@/components/ui/skeleton"
import { facilityApi } from "@/lib/api"
import { UnifiedPriceTimeline, type ForecastTelemetry } from "@/components/forecasts/UnifiedPriceTimeline"

const FALLBACK_ZONE = "LZ_NORTH"

export default function ForecastsPage() {
  // The zone starts as the facility's zone. The timeline's own selector changes it afterwards.
  const [hubLocation, setHubLocation] = React.useState<string | null>(null)
  const [telemetry, setTelemetry] = React.useState<ForecastTelemetry | null>(null)

  React.useEffect(() => {
    let active = true
    facilityApi.get()
      .then((response) => { if (active) setHubLocation(response.data?.location || FALLBACK_ZONE) })
      .catch(() => { if (active) setHubLocation(FALLBACK_ZONE) })
    return () => { active = false }
  }, [])

  const zone = hubLocation ?? FALLBACK_ZONE

  return (
    <div id="forecasts-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Price forecast</h1>

      {/* 3 Forecast Telemetry Metric Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* Card 1: Interval LMP Mean */}
        <Card className="hover:border-emerald-500/40 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Average Interval LMP
            </CardTitle>
            {telemetry?.isLoading ? (
              <Skeleton className="h-6 w-24 rounded-full" />
            ) : (
              <DataStatusBadge
                size="sm"
                status={telemetry?.avgLmp != null ? "live" : "unavailable"}
                source={`ERCOT SPP (${zone})`}
              />
            )}
          </CardHeader>
          <CardContent>
            {telemetry?.isLoading && telemetry.avgLmp == null ? (
              <div className="h-8 w-28 bg-muted/60 animate-pulse rounded my-1" />
            ) : (
              <div className="text-2xl font-bold font-mono text-foreground">
                {telemetry?.avgLmp != null ? `$${telemetry.avgLmp.toFixed(2)}` : "—"}{" "}
                <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">
              {telemetry?.currentLmp != null
                ? `Latest interval: $${telemetry.currentLmp.toFixed(2)}/MWh (${telemetry.totalIntervals} intervals)`
                : "Rolling operational window mean"}
            </p>
          </CardContent>
        </Card>

        {/* Card 2: Peak Spike in market local time */}
        <Card className="hover:border-amber-500/40 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Impending Peak Spike
            </CardTitle>
            <TrendingUp className="h-4 w-4 text-amber-500" />
          </CardHeader>
          <CardContent>
            {telemetry?.isLoading && telemetry.peakForecastPrice == null ? (
              <div className="h-8 w-28 bg-muted/60 animate-pulse rounded my-1" />
            ) : (
              <div className="text-2xl font-bold font-mono text-amber-500">
                {telemetry?.peakForecastPrice != null ? `$${telemetry.peakForecastPrice.toFixed(2)}` : "—"}{" "}
                <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">
              Peak window:{" "}
              <strong className="text-foreground">
                {telemetry?.peakForecastWindow || "—"}
              </strong>
            </p>
          </CardContent>
        </Card>

        {/* Card 3: Opportunity Valley in market local time */}
        <Card className="hover:border-emerald-500/40 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase tracking-wider">
              Lowest Opportunity Price
            </CardTitle>
            <TrendingDown className="h-4 w-4 text-emerald-500" />
          </CardHeader>
          <CardContent>
            {telemetry?.isLoading && telemetry.lowestOpportunityPrice == null ? (
              <div className="h-8 w-28 bg-muted/60 animate-pulse rounded my-1" />
            ) : (
              <div className="text-2xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
                {telemetry?.lowestOpportunityPrice != null ? `$${telemetry.lowestOpportunityPrice.toFixed(2)}` : "—"}{" "}
                <span className="text-xs font-normal text-muted-foreground">/ MWh</span>
              </div>
            )}
            <p className="text-xs text-muted-foreground mt-1">
              Opportunity window:{" "}
              <strong className="text-foreground">
                {telemetry?.lowestOpportunityWindow || "—"}
              </strong>
            </p>
          </CardContent>
        </Card>
      </div>

      {/* Primary Workstation: Continuous Unified Price Timeline */}
      {hubLocation ? (
        <UnifiedPriceTimeline
          hubLocation={hubLocation}
          onLocationChange={setHubLocation}
          onTelemetryChange={setTelemetry}
          variant="full"
          height={380}
        />
      ) : (
        <Skeleton className="h-[460px] w-full rounded-xl" />
      )}
    </div>
  )
}
