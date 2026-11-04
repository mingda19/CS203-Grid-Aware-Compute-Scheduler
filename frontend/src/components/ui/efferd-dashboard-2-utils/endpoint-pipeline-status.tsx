"use client"

import * as React from "react"
import { RefreshCw } from "lucide-react"
import { freshnessApi, type DataFreshnessResponse } from "@/lib/api"
import { formatMarketDateTime } from "@/lib/date-utils"
import { freshnessSummary } from "@/lib/data-freshness"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { DataStatusBadge } from "@/components/ui/data-status-badge"

export function EndpointPipelineStatus() {
  const [response, setResponse] = React.useState<DataFreshnessResponse | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [loading, setLoading] = React.useState(true)
  const [refreshKey, setRefreshKey] = React.useState(0)
  React.useEffect(() => {
    let active = true
    const poll = async () => {
      try {
        const result = await freshnessApi.getStatus()
        if (!active) return
        if (!result.success || !result.data) throw new Error(result.message || "Data freshness is unavailable")
        setResponse(result.data)
        setError(null)
      } catch (error) {
        if (active) { setResponse(null); setError(error instanceof Error ? error.message : "Data freshness is unavailable") }
      } finally { if (active) setLoading(false) }
    }
    void poll()
    const timer = window.setInterval(() => void poll(), 300_000)
    return () => { active = false; window.clearInterval(timer) }
  }, [refreshKey])
  return <Card>
    <CardHeader className="flex flex-row items-start justify-between gap-4">
      <div><CardTitle>Dataset freshness</CardTitle><CardDescription className="mt-1">Latest available source records. Updated every five minutes.</CardDescription></div>
      <Button variant="outline" size="sm" disabled={loading} onClick={() => { setLoading(true); setRefreshKey(key => key + 1) }}><RefreshCw className="mr-2 h-4 w-4" />Refresh</Button>
    </CardHeader>
    <CardContent>
      {loading ? <p role="status" className="text-sm text-muted-foreground">Checking source data…</p>
        : error ? <p role="alert" className="text-sm text-destructive">{error}</p>
        : !response?.datasets.length ? <p className="text-sm text-muted-foreground">No dataset status is available.</p>
        : <div className="grid gap-3 sm:grid-cols-2">{response.datasets.map(dataset => <div key={dataset.dataset} className="rounded-lg border p-3">
          <div className="flex items-center justify-between gap-2"><span className="text-sm font-medium">{dataset.dataset}</span><DataStatusBadge size="sm" status={freshnessSummary({ ...response, datasets: [dataset] }).status} source={dataset.message} updatedAt={dataset.latestRecordTimestamp} /></div>
          <p className="mt-2 text-xs text-muted-foreground">{dataset.message}</p>
        </div>)}</div>}
      {response && <p className="mt-3 text-xs text-muted-foreground">Checked {formatMarketDateTime(response.checkedAt)}</p>}
    </CardContent>
  </Card>
}
