"use client"

import * as React from "react"
import {
  CheckCircle2,
  Clock,
  ShieldCheck,
  AlertTriangle,
  History,
  XCircle,
  ArrowRight,
  FileText,
  RotateCcw,
  Sparkles,
} from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { DecisionPanel } from "@/components/ui/efferd-dashboard-2-utils/decision-panel"
import { initialWorkloads, type Workload } from "@/lib/workload-data"
import type { OptimizationWorkloadItem } from "@/lib/decision-utils"

interface DecisionAuditEntry {
  id: string
  timestamp: string
  action: "DISPATCH_APPROVED" | "PLAN_REJECTED" | "MANUAL_OVERRIDE"
  operator: string
  details: string
  savings: string
  status: "Completed" | "Logged"
}

const mockDecisionHistory: DecisionAuditEntry[] = [
  {
    id: "DEC-2026-10-09-01",
    timestamp: "2026-10-09 18:30 UTC",
    action: "DISPATCH_APPROVED",
    operator: "admin@gacs.internal",
    details: "Dispatched 2 workloads (WL-409, WL-108) shifted to 01:30 UTC cheap wind interval.",
    savings: "$3,290 saved",
    status: "Completed",
  },
  {
    id: "DEC-2026-10-09-02",
    timestamp: "2026-10-09 12:15 UTC",
    action: "PLAN_REJECTED",
    operator: "operator@gacs.internal",
    details: "Rejected plan: Priority deadline requested for Monte Carlo risk analysis batch.",
    savings: "$0 (Plan Aborted)",
    status: "Logged",
  },
  {
    id: "DEC-2026-10-08-04",
    timestamp: "2026-10-08 22:00 UTC",
    action: "DISPATCH_APPROVED",
    operator: "admin@gacs.internal",
    details: "Automated overnight dispatch of Slurm HPC batch and chiller pre-cooling.",
    savings: "$830 saved",
    status: "Completed",
  },
]

export default function ApprovalsPage() {
  const [workloads, setWorkloads] = React.useState<Workload[]>(initialWorkloads)
  const [planApproved, setPlanApproved] = React.useState(false)
  const [history, setHistory] = React.useState<DecisionAuditEntry[]>(mockDecisionHistory)

  const handleApprovePlan = (dispatchedItems: OptimizationWorkloadItem[]) => {
    setPlanApproved(true)
    const newEntry: DecisionAuditEntry = {
      id: `DEC-${Date.now().toString().slice(-6)}`,
      timestamp: new Date().toUTCString().replace("GMT", "UTC"),
      action: "DISPATCH_APPROVED",
      operator: "Current Operator",
      details: `Dispatched ${dispatchedItems.length} workloads shifted to cheap power intervals.`,
      savings: "$3,810 saved",
      status: "Completed",
    }
    setHistory((prev) => [newEntry, ...prev])
  }

  const handleRejectPlan = (reason: string, notes: string) => {
    setPlanApproved(false)
    const newEntry: DecisionAuditEntry = {
      id: `DEC-${Date.now().toString().slice(-6)}`,
      timestamp: new Date().toUTCString().replace("GMT", "UTC"),
      action: "PLAN_REJECTED",
      operator: "Current Operator",
      details: `Rejected: ${reason}. Notes: ${notes || "None"}`,
      savings: "$0",
      status: "Logged",
    }
    setHistory((prev) => [newEntry, ...prev])
  }

  const handleResetPlan = () => {
    setPlanApproved(false)
  }

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
        onApprove={handleApprovePlan}
        onReject={handleRejectPlan}
        onReset={handleResetPlan}
      />

      {/* Historical Approvals Log */}
      <Card>
        <CardHeader>
          <CardTitle className="text-base font-semibold flex items-center gap-2">
            <History className="h-4 w-4 text-emerald-500" />
            Recent Operator Decision History
          </CardTitle>
          <CardDescription className="text-xs">
            Audit record of approved and rejected solver schedules with operator credentials.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  <th className="pb-3 pl-2">Decision ID</th>
                  <th className="pb-3">Timestamp (UTC)</th>
                  <th className="pb-3">Action</th>
                  <th className="pb-3">Operator</th>
                  <th className="pb-3">Dispatch Details</th>
                  <th className="pb-3">Financial Impact</th>
                  <th className="pb-3 pr-2 text-right">Status</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border/60">
                {history.map((item) => (
                  <tr key={item.id} className="hover:bg-muted/40 transition-colors">
                    <td className="py-3 pl-2 font-mono text-xs font-semibold text-foreground">
                      {item.id}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono">
                      {item.timestamp}
                    </td>
                    <td className="py-3">
                      <span
                        className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-md text-[11px] font-semibold ${
                          item.action === "DISPATCH_APPROVED"
                            ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                            : item.action === "PLAN_REJECTED"
                            ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                            : "bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30"
                        }`}
                      >
                        {item.action === "DISPATCH_APPROVED" ? (
                          <CheckCircle2 className="h-3 w-3 text-emerald-500" />
                        ) : (
                          <XCircle className="h-3 w-3 text-amber-500" />
                        )}
                        {item.action.replace("_", " ")}
                      </span>
                    </td>
                    <td className="py-3 text-xs text-muted-foreground">
                      {item.operator}
                    </td>
                    <td className="py-3 text-xs text-foreground max-w-xs truncate">
                      {item.details}
                    </td>
                    <td className="py-3 font-mono font-semibold text-xs text-emerald-600 dark:text-emerald-400">
                      {item.savings}
                    </td>
                    <td className="py-3 pr-2 text-right">
                      <span className="text-[11px] text-muted-foreground font-medium">
                        {item.status}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>
    </div>
  )
}
