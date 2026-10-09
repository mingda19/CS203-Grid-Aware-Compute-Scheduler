"use client"

import * as React from "react"
import {
  FileText,
  Search,
  Download,
  Filter,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Shield,
  Clock,
  Eye,
  RefreshCw,
} from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"

interface AuditLogEvent {
  id: string
  timestamp: string
  operator: string
  action: "DISPATCH_EXECUTED" | "WORKLOAD_THROTTLED" | "SOLVER_RE_EVAL" | "ROLE_UPDATED" | "PLAN_REJECTED"
  target: string
  outcome: "SUCCESS" | "WARNING" | "FAILED"
  ipAddress: string
  details: Record<string, any>
}

const initialAuditLogs: AuditLogEvent[] = [
  {
    id: "AUD-89210",
    timestamp: "2026-10-10 00:28:15 UTC",
    operator: "admin@gacs.internal",
    action: "DISPATCH_EXECUTED",
    target: "WL-409, WL-108",
    outcome: "SUCCESS",
    ipAddress: "192.168.1.42",
    details: {
      recommendationId: "REC-2026-10-09-001",
      shiftedPowerMw: 2.4,
      projectedSavingsUsd: 3810,
      slaImpact: "0 SLA Violations",
      method: "TWO_STEP_OPERATOR_SIGN_OFF",
    },
  },
  {
    id: "AUD-89209",
    timestamp: "2026-10-10 00:15:30 UTC",
    operator: "system_scheduler",
    action: "SOLVER_RE_EVAL",
    target: "ERCOT_LZ_NORTH_HORIZON_24H",
    outcome: "SUCCESS",
    ipAddress: "127.0.0.1",
    details: {
      model: "XGBoost-LSTM v2.4",
      intervalCount: 288,
      objective: "MINIMIZE_EXPENSE_MAXIMIZE_GREEN",
      durationMs: 312,
    },
  },
  {
    id: "AUD-89208",
    timestamp: "2026-10-09 23:45:10 UTC",
    operator: "operator@gacs.internal",
    action: "WORKLOAD_THROTTLED",
    target: "WL-108 (ASIC Pod Alpha)",
    outcome: "SUCCESS",
    ipAddress: "192.168.1.55",
    details: {
      reason: "Peak wholesale price spike protection",
      curtailedPowerKw: 1800,
      overrideDurationMin: 90,
    },
  },
  {
    id: "AUD-89207",
    timestamp: "2026-10-09 21:10:04 UTC",
    operator: "admin@gacs.internal",
    action: "ROLE_UPDATED",
    target: "User #4 (operator@gacs.internal)",
    outcome: "SUCCESS",
    ipAddress: "192.168.1.42",
    details: {
      previousRole: "ROLE_USER",
      newRole: "ROLE_ADMIN",
      approvedBy: "System Administrator",
    },
  },
  {
    id: "AUD-89206",
    timestamp: "2026-10-09 19:02:18 UTC",
    operator: "operator@gacs.internal",
    action: "PLAN_REJECTED",
    target: "Optimization Plan #381",
    outcome: "WARNING",
    ipAddress: "192.168.1.55",
    details: {
      reason: "Critical ML model training deadline required completion by 08:00 UTC",
      notes: "Client requested urgent completion regardless of price differential",
    },
  },
]

export default function AuditPage() {
  const [logs] = React.useState<AuditLogEvent[]>(initialAuditLogs)
  const [search, setSearch] = React.useState("")
  const [actionFilter, setActionFilter] = React.useState("ALL")
  const [outcomeFilter, setOutcomeFilter] = React.useState("ALL")
  const [selectedEvent, setSelectedEvent] = React.useState<AuditLogEvent | null>(null)

  const filteredLogs = React.useMemo(() => {
    return logs.filter((log) => {
      const matchSearch =
        search === "" ||
        log.id.toLowerCase().includes(search.toLowerCase()) ||
        log.operator.toLowerCase().includes(search.toLowerCase()) ||
        log.target.toLowerCase().includes(search.toLowerCase())
      const matchAction = actionFilter === "ALL" || log.action === actionFilter
      const matchOutcome = outcomeFilter === "ALL" || log.outcome === outcomeFilter
      return matchSearch && matchAction && matchOutcome
    })
  }, [logs, search, actionFilter, outcomeFilter])

  const exportCsv = () => {
    const headers = ["ID", "Timestamp", "Operator", "Action", "Target", "Outcome", "IP Address"]
    const rows = filteredLogs.map((l) => [
      l.id,
      l.timestamp,
      l.operator,
      l.action,
      l.target,
      l.outcome,
      l.ipAddress,
    ])
    const csvContent =
      "data:text/csv;charset=utf-8," +
      [headers.join(","), ...rows.map((e) => e.map((x) => `"${x}"`).join(","))].join("\n")
    const encodedUri = encodeURI(csvContent)
    const link = document.createElement("a")
    link.setAttribute("href", encodedUri)
    link.setAttribute("download", `gacs_audit_log_${new Date().toISOString().slice(0, 10)}.csv`)
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
  }

  return (
    <div id="audit-root" className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Historical Audit Trail
            </h1>
            <DataStatusBadge
              size="sm"
              status="live"
              source="Immutable Security & Dispatch Audit Log"
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Searchable, tamper-evident record of all operator actions, dispatch overrides, solver runs, and RBAC changes for NERC-CIP & SOC2 compliance.
          </p>
        </div>

        <Button onClick={exportCsv} size="sm" variant="outline" className="gap-2">
          <Download className="h-4 w-4" /> Export CSV / Compliance Report
        </Button>
      </div>

      {/* Filter and Table Card */}
      <Card>
        <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
          <div className="flex items-center gap-3 flex-1 max-w-xl">
            <div className="relative flex-1">
              <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
              <Input
                placeholder="Search by ID, operator, or workload..."
                value={search}
                onChange={(e) => setSearch(e.target.value)}
                className="pl-8 h-9 text-xs"
              />
            </div>
            <select
              value={actionFilter}
              onChange={(e) => setActionFilter(e.target.value)}
              className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs text-muted-foreground"
            >
              <option value="ALL">All Actions</option>
              <option value="DISPATCH_EXECUTED">Dispatch Executed</option>
              <option value="WORKLOAD_THROTTLED">Workload Throttled</option>
              <option value="SOLVER_RE_EVAL">Solver Re-Evaluation</option>
              <option value="ROLE_UPDATED">Role Updated</option>
              <option value="PLAN_REJECTED">Plan Rejected</option>
            </select>
            <select
              value={outcomeFilter}
              onChange={(e) => setOutcomeFilter(e.target.value)}
              className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs text-muted-foreground"
            >
              <option value="ALL">All Outcomes</option>
              <option value="SUCCESS">Success</option>
              <option value="WARNING">Warning</option>
              <option value="FAILED">Failed</option>
            </select>
          </div>
          <div className="text-xs text-muted-foreground">
            Showing <span className="font-semibold text-foreground">{filteredLogs.length}</span> records
          </div>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  <th className="pb-3 pl-2">Event ID</th>
                  <th className="pb-3">Timestamp (UTC)</th>
                  <th className="pb-3">Operator</th>
                  <th className="pb-3">Action Type</th>
                  <th className="pb-3">Target Resource</th>
                  <th className="pb-3">Outcome</th>
                  <th className="pb-3 pr-2 text-right">Payload</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {filteredLogs.map((log) => (
                  <tr key={log.id} className="hover:bg-muted/40 transition-colors">
                    <td className="py-3 pl-2 font-mono text-xs font-semibold text-foreground">
                      {log.id}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono">
                      {log.timestamp}
                    </td>
                    <td className="py-3 text-xs text-foreground font-medium">
                      {log.operator}
                    </td>
                    <td className="py-3">
                      <span className="font-mono text-xs px-2 py-0.5 rounded-md bg-muted text-muted-foreground">
                        {log.action}
                      </span>
                    </td>
                    <td className="py-3 text-xs text-muted-foreground max-w-xs truncate">
                      {log.target}
                    </td>
                    <td className="py-3">
                      <span
                        className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold ${
                          log.outcome === "SUCCESS"
                            ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                            : log.outcome === "WARNING"
                            ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                            : "bg-red-500/15 text-red-600 dark:text-red-400 border border-red-500/30"
                        }`}
                      >
                        {log.outcome === "SUCCESS" ? (
                          <CheckCircle2 className="h-3 w-3 text-emerald-500" />
                        ) : (
                          <AlertTriangle className="h-3 w-3 text-amber-500" />
                        )}
                        {log.outcome}
                      </span>
                    </td>
                    <td className="py-3 pr-2 text-right">
                      <Button
                        variant="ghost"
                        size="sm"
                        className="h-8 text-xs gap-1 text-muted-foreground hover:text-foreground"
                        onClick={() => setSelectedEvent(log)}
                      >
                        <Eye className="h-3.5 w-3.5" /> Details
                      </Button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Event Details Inspection Dialog */}
      <Dialog open={!!selectedEvent} onOpenChange={(open) => !open && setSelectedEvent(null)}>
        <DialogContent className="sm:max-w-lg">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <FileText className="h-4 w-4 text-emerald-500" />
              Audit Event Inspection: {selectedEvent?.id}
            </DialogTitle>
            <DialogDescription>
              Forensic metadata record for audit and compliance verification.
            </DialogDescription>
          </DialogHeader>
          {selectedEvent && (
            <div className="space-y-3 py-2 text-xs">
              <div className="grid grid-cols-2 gap-2 p-3 rounded-lg bg-muted/30 border border-border">
                <div>
                  <span className="text-muted-foreground">Operator:</span>{" "}
                  <span className="font-semibold text-foreground">{selectedEvent.operator}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Source IP:</span>{" "}
                  <span className="font-mono text-foreground">{selectedEvent.ipAddress}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Action:</span>{" "}
                  <span className="font-mono text-foreground">{selectedEvent.action}</span>
                </div>
                <div>
                  <span className="text-muted-foreground">Timestamp:</span>{" "}
                  <span className="font-mono text-foreground">{selectedEvent.timestamp}</span>
                </div>
              </div>
              <div className="space-y-1">
                <span className="font-semibold text-foreground">Event Payload JSON:</span>
                <pre className="p-3 rounded-lg bg-muted/60 border border-border text-[11px] font-mono overflow-x-auto text-foreground">
                  {JSON.stringify(selectedEvent.details, null, 2)}
                </pre>
              </div>
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
