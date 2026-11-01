"use client"

import * as React from "react"
import { CheckCircle2, History, RefreshCw, RotateCcw, XCircle } from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { DecisionPanel } from "@/components/ui/efferd-dashboard-2-utils/decision-panel"
import { ScheduleExportButtons } from "@/components/schedule-export/schedule-export-buttons"
import { auditApi, getStoredUser, type AuditActionType, type AuditEvent } from "@/lib/api"
import {
  SCHEDULE_DECISION_TYPES,
  exportUnavailableReason,
  formatAuditTimestamp,
  latestDecisionByRun,
} from "@/lib/audit-utils"

const HISTORY_SIZE = 50

const decisionStyles: Record<string, string> = {
  APPROVE: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30",
  REJECT: "bg-red-500/15 text-red-600 dark:text-red-400 border border-red-500/30",
  REVISE: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30",
}

function DecisionBadge({ action }: { action: AuditActionType }) {
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold ${decisionStyles[action] ?? ""}`}>
      {action === "APPROVE" ? (
        <CheckCircle2 className="h-3 w-3 text-emerald-500" />
      ) : action === "REJECT" ? (
        <XCircle className="h-3 w-3 text-red-500" />
      ) : (
        <RotateCcw className="h-3 w-3 text-amber-500" />
      )}
      {action}
    </span>
  )
}

export default function ApprovalsPage() {
  const [planApproved, setPlanApproved] = React.useState(false)
  const [decisions, setDecisions] = React.useState<AuditEvent[]>([])
  const [operator, setOperator] = React.useState<string | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [reloadKey, setReloadKey] = React.useState(0)
  const [loadedKey, setLoadedKey] = React.useState<number | null>(null)
  const isLoading = loadedKey !== reloadKey

  // The signed-in operator's own decisions: only the owner of a run can decide on it or export it.
  React.useEffect(() => {
    let cancelled = false
    const email = getStoredUser()?.email ?? null
    const request = email ? auditApi.list({ actor: email, size: HISTORY_SIZE }) : Promise.resolve(null)
    request
      .then((res) => {
        if (cancelled) return
        // The actor filter matches substrings, so keep exact matches only.
        const own = (res?.data?.content ?? []).filter(
          (e) => e.actor.toLowerCase() === email?.toLowerCase() && SCHEDULE_DECISION_TYPES.includes(e.actionType),
        )
        setOperator(email)
        setDecisions(own)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setDecisions([])
        setError(err instanceof Error ? err.message : "Failed to load decision history")
      })
      .finally(() => {
        if (!cancelled) setLoadedKey(reloadKey)
      })
    return () => {
      cancelled = true
    }
  }, [reloadKey])

  const latest = React.useMemo(() => latestDecisionByRun(decisions), [decisions])

  return (
    <div id="approvals-root" className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Operator Approvals & Dispatch
            </h1>
            <DataStatusBadge
              size="sm"
              status="live"
              source="Human-in-the-Loop Recommendation Engine"
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Review algorithmic schedule optimizations with full impact projections before issuing grid dispatch commands.
          </p>
        </div>
      </div>

      {/* Main Decision Cockpit Panel */}
      <DecisionPanel
        planApproved={planApproved}
        onApprove={() => setPlanApproved(true)}
        onReject={() => setPlanApproved(false)}
        onReset={() => setPlanApproved(false)}
      />

      {/* Decision history from the audit log, with approved-schedule downloads */}
      <Card>
        <CardHeader className="flex flex-row items-start justify-between gap-4">
          <div className="space-y-1.5">
            <CardTitle className="text-base font-semibold flex items-center gap-2">
              <History className="h-4 w-4 text-emerald-500" />
              Recent Operator Decision History
            </CardTitle>
            <CardDescription className="text-xs">
              Your approve, reject and revise decisions from the audit log. An approved run can be downloaded as CSV or JSON while its approval still stands.
            </CardDescription>
          </div>
          <Button
            variant="outline"
            size="sm"
            className="gap-2"
            onClick={() => setReloadKey((k) => k + 1)}
            disabled={isLoading}
          >
            <RefreshCw className={`h-4 w-4 ${isLoading ? "animate-spin" : ""}`} /> Refresh
          </Button>
        </CardHeader>
        <CardContent>
          {error && (
            <div className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-600 dark:text-red-400">
              <span className="flex items-center gap-2">
                <XCircle className="h-4 w-4" /> {error}
              </span>
              <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => setReloadKey((k) => k + 1)}>
                Retry
              </Button>
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  <th className="pb-3 pl-2">Decision ID</th>
                  <th className="pb-3">Timestamp (UTC)</th>
                  <th className="pb-3">Decision</th>
                  <th className="pb-3">Run</th>
                  <th className="pb-3">Rationale</th>
                  <th className="pb-3 pr-2 text-right">Approved Schedule</th>
                </tr>
              </thead>
              <tbody className={`divide-y divide-border/60 ${isLoading ? "opacity-50" : ""}`}>
                {decisions.map((item) => (
                  <tr key={item.id} className="hover:bg-muted/40 transition-colors align-top">
                    <td className="py-3 pl-2 font-mono text-xs font-semibold text-foreground">
                      AUD-{item.id}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono whitespace-nowrap">
                      {formatAuditTimestamp(item.timestamp)}
                    </td>
                    <td className="py-3">
                      <DecisionBadge action={item.actionType} />
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono" title={item.runId}>
                      {item.runId.length > 13 ? `${item.runId.slice(0, 13)}…` : item.runId}
                    </td>
                    <td className="py-3 text-xs text-foreground max-w-xs truncate" title={item.operatorRationale}>
                      {item.operatorRationale}
                    </td>
                    <td className="py-3 pr-2 text-right">
                      {item.actionType === "APPROVE" ? (
                        <ScheduleExportButtons runId={item.runId} unavailableReason={exportUnavailableReason(item, latest)} />
                      ) : (
                        <span className="text-[11px] text-muted-foreground">—</span>
                      )}
                    </td>
                  </tr>
                ))}
                {!isLoading && !error && decisions.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-xs text-muted-foreground">
                      {operator
                        ? "No operator decisions recorded yet. Approved schedule runs will appear here."
                        : "Sign in to see your decisions."}
                    </td>
                  </tr>
                )}
                {isLoading && decisions.length === 0 && (
                  <tr>
                    <td colSpan={6} className="py-8 text-center text-xs text-muted-foreground">
                      <RefreshCw className="mx-auto mb-2 h-4 w-4 animate-spin text-emerald-500" />
                      Loading decision history…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
