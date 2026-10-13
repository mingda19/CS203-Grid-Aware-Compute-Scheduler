"use client"

import * as React from "react"
import {
  Activity,
  AlertCircle,
  CheckCircle2,
  CircleDashed,
  RefreshCw,
  XCircle,
} from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"

type EndpointState = "checking" | "online" | "offline"
type EndpointCheck = { name: string; path: string; state: EndpointState; latency?: number }

const monitoredEndpoints = [
  { name: "Authentication API", path: "/api/auth/me" },
  { name: "Admin API", path: "/api/admin/users" },
]
const apiBase = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080"

export function EndpointPipelineStatus() {
  const [checks, setChecks] = React.useState<EndpointCheck[]>(
    monitoredEndpoints.map((item) => ({ ...item, state: "checking" as const }))
  )
  const [lastChecked, setLastChecked] = React.useState<Date | null>(null)
  const [refreshing, setRefreshing] = React.useState(false)

  const refresh = React.useCallback(async () => {
    setRefreshing(true)
    setChecks(monitoredEndpoints.map((item) => ({ ...item, state: "checking" as const })))
    const next = await Promise.all(
      monitoredEndpoints.map(async (item): Promise<EndpointCheck> => {
        const started = performance.now()
        try {
          const response = await fetch(`${apiBase}${item.path}`, {
            credentials: "include",
            cache: "no-store",
          })
          return {
            ...item,
            state:
              response.ok || response.status === 401 || response.status === 403
                ? "online"
                : "offline",
            latency: Math.round(performance.now() - started),
          }
        } catch {
          return { ...item, state: "offline", latency: Math.round(performance.now() - started) }
        }
      })
    )
    setChecks(next)
    setLastChecked(new Date())
    setRefreshing(false)
  }, [])

  React.useEffect(() => {
    void refresh()
    const timer = window.setInterval(() => void refresh(), 30_000)
    return () => window.clearInterval(timer)
  }, [refresh])

  const onlineCount = checks.filter((check) => check.state === "online").length
  const pipelineStages = [
    "Grid data ingestion",
    "Forecast generation",
    "Workload optimization",
    "Schedule dispatch",
  ]

  return (
    <Card>
      <CardHeader className="flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between">
        <div>
          <CardTitle className="flex items-center gap-2">
            <Activity className="h-4 w-4 text-emerald-500" />
            Pipeline & endpoint status
          </CardTitle>
          <CardDescription className="mt-1">
            Backend connectivity checks and pipeline telemetry availability.
          </CardDescription>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={() => void refresh()}
          disabled={refreshing}
          className="gap-2"
        >
          <RefreshCw className={`h-3.5 w-3.5 ${refreshing ? "animate-spin" : ""}`} /> Refresh
        </Button>
      </CardHeader>
      <CardContent className="grid gap-5 lg:grid-cols-2">
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold uppercase tracking-wider text-muted-foreground">
              API endpoints
            </span>
            <span className="text-muted-foreground">
              {onlineCount}/{checks.length} reachable
            </span>
          </div>
          {checks.map((check) => (
            <div
              key={check.path}
              className="flex items-center justify-between rounded-lg border border-border/70 px-3 py-2.5"
            >
              <div className="flex items-center gap-2.5">
                {check.state === "checking" ? (
                  <CircleDashed className="h-4 w-4 animate-spin text-muted-foreground" />
                ) : check.state === "online" ? (
                  <CheckCircle2 className="h-4 w-4 text-emerald-500" />
                ) : (
                  <XCircle className="h-4 w-4 text-red-500" />
                )}
                <div>
                  <p className="text-sm font-medium">{check.name}</p>
                  <code className="text-[11px] text-muted-foreground">{check.path}</code>
                </div>
              </div>
              <span className="text-xs text-muted-foreground">
                {check.state === "checking"
                  ? "Checking"
                  : check.state === "online"
                  ? `${check.latency} ms`
                  : "Offline"}
              </span>
            </div>
          ))}
          <p className="text-[11px] text-muted-foreground">
            Last checked {lastChecked?.toLocaleTimeString() ?? "—"} · auto refresh 30 sec
          </p>
        </div>
        <div className="space-y-3">
          <div className="flex items-center justify-between text-xs">
            <span className="font-semibold uppercase tracking-wider text-muted-foreground">
              Pipeline stages
            </span>
            <span className="inline-flex items-center gap-1 text-amber-500">
              <AlertCircle className="h-3.5 w-3.5" />
              No telemetry API
            </span>
          </div>
          {pipelineStages.map((stage, index) => (
            <div
              key={stage}
              className="flex items-center gap-2.5 rounded-lg border border-border/70 px-3 py-2.5"
            >
              <span className="flex h-5 w-5 items-center justify-center rounded-full bg-muted text-[10px] text-muted-foreground">
                {index + 1}
              </span>
              <span className="text-sm">{stage}</span>
              <span className="ml-auto text-[11px] text-muted-foreground">No status data</span>
            </div>
          ))}
          <p className="text-[11px] text-muted-foreground">
            Pipeline status will appear when execution health endpoints are available.
          </p>
        </div>
      </CardContent>
    </Card>
  )
}
