"use client"

import * as React from "react"
import { AlertTriangle, Clock, Minus, TrendingDown, TrendingUp } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { HelpTip } from "@/components/ui/help-tip"
import { StatCard } from "@/components/home/StatCard"
import { NeedsApprovalCard } from "@/components/home/NeedsApprovalCard"
import { UpcomingJobsTable } from "@/components/home/UpcomingJobsTable"
import { unwrap, useLoad } from "@/components/home/use-load"
import { ForecastCard } from "@/components/forecasts/ForecastCard"
import { DEFAULT_ZONE, useForecastCard } from "@/components/forecasts/use-forecast-card"
import { facilityApi, freshnessApi, priceApi, type FacilityUtilization } from "@/lib/api"
import { datasetFreshnessText } from "@/lib/data-freshness"
import { formatMarketTime } from "@/lib/date-utils"
import { currentSettlementMetrics } from "@/lib/settlement-price"

const FIVE_MINUTES = 5 * 60_000

const hoursFormat = new Intl.NumberFormat("en-US", { maximumFractionDigits: 1 })

/** The day-ahead price feed includes future hours, so the metrics pick the current hour from it. */
async function loadPrices(zone: string) {
  const end = new Date()
  const start = new Date(end)
  start.setUTCDate(start.getUTCDate() - 2)
  const history = await unwrap(
    priceApi.getHistory({ location: zone, startDate: start.toISOString().slice(0, 10), endDate: end.toISOString().slice(0, 10) }),
    "Unable to load settlement prices",
  )
  return history.points
}

function usageCard(resource: "CPU" | "GPU", data: FacilityUtilization) {
  const usage = resource === "CPU" ? data.cpu : data.gpu
  if (data.state === "NO_APPROVED_PLAN") return { value: "—", supporting: `No approved plan, limit ${hoursFormat.format(usage.limit)} ${resource}-h` }
  if (usage.percent == null || usage.used == null) return { value: "—", supporting: `No ${resource} capacity` }
  const over = usage.percent > 100
  return {
    value: `${Math.round(usage.percent)}%`,
    supporting: `${over ? "Over capacity: " : ""}${hoursFormat.format(usage.used)} of ${hoursFormat.format(usage.limit)} ${resource}-h this hour`,
    supportingIcon: over ? <AlertTriangle aria-hidden="true" /> : undefined,
    progress: usage.percent,
    warn: over,
  }
}

const scheduledUseHelp = (
  <HelpTip label="scheduled use">From the approved plans. Measured use needs execution data.</HelpTip>
)

export default function HomePage() {
  const facility = useLoad("facility", () => unwrap(facilityApi.get(), "Unable to load the facility"))
  // Home uses the facility's price zone. If the facility cannot load, the default zone still gives a page.
  const zone = facility.loading ? null : facility.data?.location ?? DEFAULT_ZONE

  const prices = useLoad(zone ? `prices:${zone}` : null, () => loadPrices(zone ?? DEFAULT_ZONE), 15 * 60_000)
  const forecast = useForecastCard(zone)
  const utilization = useLoad("utilization", () => unwrap(facilityApi.utilization(), "Unable to load utilization"), FIVE_MINUTES)
  const freshness = useLoad("freshness", () => unwrap(freshnessApi.getStatus(), "Unable to load data status"), FIVE_MINUTES)

  // The current hour changes without new data, so the metrics follow the clock.
  const [now, setNow] = React.useState(() => Date.now())
  React.useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 60_000)
    return () => window.clearInterval(timer)
  }, [])
  const metrics = React.useMemo(() => currentSettlementMetrics(prices.data ?? [], now), [prices.data, now])

  const priceDataset = freshness.data?.datasets.find((d) => d.dataset === "electrical_price")
  const priceLoading = facility.loading || prices.loading

  const price = (() => {
    if (metrics.currentLmp == null) return { value: "—", supporting: "No price available" }
    const value = `${metrics.currentLmp < 0 ? "-" : ""}$${Math.abs(metrics.currentLmp).toFixed(2)}`
    if (metrics.stale && metrics.latest) {
      return { value, supporting: `Last available hour: ${formatMarketTime(metrics.latest.intervalStartUtc)}`, supportingIcon: <Clock aria-hidden="true" /> }
    }
    if (metrics.rollingAvgFormatted === "—") return { value, supporting: "No 24h average yet" }
    if (metrics.diffPercent < 0.05) return { value, supporting: "At the 24h average", supportingIcon: <Minus aria-hidden="true" /> }
    return {
      value,
      supporting: `${metrics.diffPercent.toFixed(1)}% ${metrics.isDrop ? "below" : "above"} the 24h average`,
      supportingIcon: metrics.isDrop ? <TrendingDown aria-hidden="true" /> : <TrendingUp aria-hidden="true" />,
    }
  })()

  const cpu = utilization.data ? usageCard("CPU", utilization.data) : { value: "—", supporting: "" }
  const gpu = utilization.data ? usageCard("GPU", utilization.data) : { value: "—", supporting: "" }

  return (
    <div id="home-root" className="min-w-0 space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Home</h1>

      {/* Only price data raises an alert here. The other datasets show in the top-bar popover. */}
      {priceDataset && priceDataset.status !== "HEALTHY" && (
        <Alert variant="warning">
          <AlertTriangle className="h-4 w-4" aria-hidden="true" />
          <AlertDescription className="min-w-0 break-words">
            {datasetFreshnessText(priceDataset, now)}. New plans use older prices.
          </AlertDescription>
        </Alert>
      )}

      <NeedsApprovalCard />

      <div className="grid min-w-0 grid-cols-1 gap-4 md:grid-cols-3">
        <StatCard
          label="Price now"
          unit="/MWh"
          loading={priceLoading}
          error={prices.error}
          onRetry={prices.reload}
          {...price}
        />
        <StatCard
          label={<>CPU scheduled use {scheduledUseHelp}</>}
          loading={utilization.loading}
          error={utilization.error}
          onRetry={utilization.reload}
          {...cpu}
        />
        <StatCard
          label={<>GPU scheduled use {scheduledUseHelp}</>}
          loading={utilization.loading}
          error={utilization.error}
          onRetry={utilization.reload}
          {...gpu}
        />
      </div>

      <ForecastCard
        {...forecast.card}
        onShowPastChange={undefined}
        footerAction={
          <Button asChild variant="link" size="sm" className="h-auto p-0 text-xs leading-4">
            <Link href="/forecasts">Details</Link>
          </Button>
        }
      />

      <UpcomingJobsTable />
    </div>
  )
}
