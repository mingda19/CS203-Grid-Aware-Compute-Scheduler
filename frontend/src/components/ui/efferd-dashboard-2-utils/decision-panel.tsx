"use client"

import * as React from "react"
import {
  ShieldAlert,
  CheckCircle2,
  AlertTriangle,
  ArrowRight,
  TrendingDown,
  TrendingUp,
  Clock,
  Zap,
  Cpu,
  ChevronDown,
  ChevronUp,
  SlidersHorizontal,
  XCircle,
  RotateCcw,
  Sparkles,
  Info,
  Check,
  Server,
  Layers,
} from "lucide-react"

import { Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import {
  calculateDecisionImpact,
  formatCurrency,
  formatMw,
  type OptimizationWorkloadItem,
} from "@/lib/decision-utils"
import { DispatchConfirmationModal } from "./dispatch-confirmation-modal"
import { RejectPlanModal } from "./reject-plan-modal"
import { getStoredUser } from "@/lib/api"

export interface DecisionPanelProps {
  planApproved: boolean
  onApprove: (dispatchedWorkloads: OptimizationWorkloadItem[]) => void
  onReject: (reason: string, notes: string) => void
  onReset: () => void
}

const initialOptimizationWorkloads: OptimizationWorkloadItem[] = [
  {
    id: "WL-409",
    name: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    cluster: "64x NVIDIA H100 SXM5",
    powerKw: 1200,
    originalWindow: "01:00 – 05:00 SGT",
    proposedWindow: "09:30 – 13:30 SGT",
    deadline: "18:00 SGT (Today)",
    baselineCostUsd: 5640,
    optimizedCostUsd: 3800,
    savingsUsd: 1840,
    slackHours: 4.5,
    rationale: "Shifted forward to take advantage of sustained West Texas wind valley ($14.80/MWh).",
    included: true,
  },
  {
    id: "WL-108",
    name: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    cluster: "Antminer S19 Pro+ Pod 2",
    powerKw: 1800,
    originalWindow: "01:00 – 04:00 SGT (Full)",
    proposedWindow: "01:00 – 04:00 SGT (Throttled)",
    deadline: "Flexible Throughput",
    baselineCostUsd: 4680,
    optimizedCostUsd: 3230,
    savingsUsd: 1450,
    slackHours: 12.0,
    rationale: "Throttled to 200 kW baseline to shed load during ERCOT thermal spike ($142.50/MWh).",
    included: true,
  },
  {
    id: "WL-812",
    name: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    cluster: "Slurm HPC Cluster (96 Nodes)",
    powerKw: 450,
    originalWindow: "14:00 – 16:30 SGT",
    proposedWindow: "10:00 – 12:30 SGT",
    deadline: "16:00 SGT (Today)",
    baselineCostUsd: 1390,
    optimizedCostUsd: 870,
    savingsUsd: 520,
    slackHours: 3.5,
    rationale: "Moved into morning low-cost LMP block before peak industrial ramp.",
    included: true,
  },
  {
    id: "WL-022",
    name: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    cluster: "Trane Centrifugal Chiller Bank",
    powerKw: 320,
    originalWindow: "01:00 – 03:30 SGT",
    proposedWindow: "21:30 – 00:00 SGT",
    deadline: "Peak Window (01:00 SGT)",
    baselineCostUsd: 1280,
    optimizedCostUsd: 970,
    savingsUsd: 310,
    slackHours: 2.5,
    rationale: "Pre-chill thermal storage tanks so mechanical chillers can idle through peak LMP window.",
    included: true,
  },
]

export function DecisionPanel({
  planApproved,
  onApprove,
  onReject,
  onReset,
}: DecisionPanelProps) {
  const [workloads, setWorkloads] = React.useState<OptimizationWorkloadItem[]>(
    initialOptimizationWorkloads
  )
  const [expandedWorkloadId, setExpandedWorkloadId] = React.useState<string | null>(null)
  const [isConfirmOpen, setIsConfirmOpen] = React.useState(false)
  const [isRejectOpen, setIsRejectOpen] = React.useState(false)
  const [planRejected, setPlanRejected] = React.useState(false)
  const [rejectionReason, setRejectionReason] = React.useState<string | null>(null)
  const [dispatchedAt, setDispatchedAt] = React.useState<string | null>(null)

  const currentUser = getStoredUser()
  const impact = React.useMemo(() => calculateDecisionImpact(workloads), [workloads])

  const toggleWorkloadInclusion = (id: string) => {
    if (planApproved || planRejected) return
    setWorkloads((prev) =>
      prev.map((item) => (item.id === id ? { ...item, included: !item.included } : item))
    )
  }

  const handleApproveConfirm = () => {
    setDispatchedAt(new Date().toUTCString())
    onApprove(workloads.filter((w) => w.included))
  }

  const handleRejectSubmit = (reason: string, notes: string) => {
    setPlanRejected(true)
    setRejectionReason(reason)
    onReject(reason, notes)
  }

  const handleResetToEvaluation = () => {
    setPlanRejected(false)
    setRejectionReason(null)
    setDispatchedAt(null)
    onReset()
  }

  return (
    <div id="approvals" className="scroll-mt-20">
      <Card
        className={`border-2 transition-all shadow-md ${
          planApproved
            ? "border-emerald-500/50 bg-emerald-500/5 shadow-emerald-500/5"
            : planRejected
            ? "border-destructive/40 bg-destructive/5"
            : "border-amber-500/60 bg-gradient-to-br from-card via-card to-amber-500/5 shadow-amber-500/10"
        }`}
      >
        {/* Panel Header */}
        <CardHeader className="pb-4">
          <div className="flex flex-col lg:flex-row lg:items-center lg:justify-between gap-4">
            <div className="flex items-start sm:items-center gap-3">
              <div
                className={`p-2.5 rounded-xl shadow-xs shrink-0 ${
                  planApproved
                    ? "bg-emerald-500 text-white"
                    : planRejected
                    ? "bg-destructive text-white"
                    : "bg-amber-500 text-white animate-pulse"
                }`}
              >
                {planApproved ? (
                  <CheckCircle2 className="h-6 w-6" />
                ) : planRejected ? (
                  <XCircle className="h-6 w-6" />
                ) : (
                  <ShieldAlert className="h-6 w-6" />
                )}
              </div>
              <div>
                <div className="flex items-center gap-2.5 flex-wrap">
                  <CardTitle className="text-lg font-bold text-foreground">
                    {planApproved
                      ? "Optimization Plan #2026-0920-04 Dispatched & Active"
                      : planRejected
                      ? "Optimization Plan #2026-0920-04 Dismissed (Fixed Baseline)"
                      : "Human-in-the-Loop Decision: Schedule Recommendation #2026-0920-04"}
                  </CardTitle>
                  <DataStatusBadge
                    size="sm"
                    status="demo"
                    source="Optimization Engine Solver (XGBoost-LSTM v2.4, Ingestion Latency: 1.2m)"
                  />
                </div>
                <CardDescription className="text-xs mt-1 text-muted-foreground flex items-center gap-2 flex-wrap">
                  {planApproved ? (
                    <span className="flex items-center gap-1.5 text-emerald-600 dark:text-emerald-400 font-medium">
                      <Check className="h-3.5 w-3.5" />
                      Dispatch commands acknowledged by Slurm, Kubernetes, and Facility BMS adapters at {dispatchedAt ?? "recently"}.
                    </span>
                  ) : planRejected ? (
                    <span className="flex items-center gap-1.5 text-destructive font-medium">
                      <XCircle className="h-3.5 w-3.5" />
                      Dismissed: {rejectionReason ?? "Standard fixed baseline maintained"}.
                    </span>
                  ) : (
                    <span className="flex items-center gap-1.5 text-amber-600 dark:text-amber-400 font-semibold">
                      <Clock className="h-3.5 w-3.5" />
                      Operator action required · Decision needed before 09:15 SGT (in 24 mins) before interval lock
                    </span>
                  )}
                </CardDescription>
              </div>
            </div>

            {/* Status Pills */}
            <div className="flex items-center gap-2 self-start lg:self-center">
              <span
                className={`text-xs font-semibold px-3 py-1.5 rounded-full flex items-center gap-1.5 ${
                  planApproved
                    ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                    : planRejected
                    ? "bg-destructive/15 text-destructive border border-destructive/30"
                    : "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                }`}
              >
                <span
                  className={`h-2 w-2 rounded-full ${
                    planApproved
                      ? "bg-emerald-500"
                      : planRejected
                      ? "bg-destructive"
                      : "bg-amber-500 animate-ping"
                  }`}
                />
                {planApproved
                  ? "STATUS: DISPATCHED & EXECUTING"
                  : planRejected
                  ? "STATUS: FIXED BASELINE RETAINED"
                  : "PENDING OPERATOR SIGN-OFF"}
              </span>
            </div>
          </div>
        </CardHeader>

        <CardContent className="space-y-4">
          {/* Plain-English Executive Summary */}
          <div className="p-4 rounded-xl bg-background/90 border border-border/80 text-xs sm:text-sm text-foreground space-y-2 shadow-xs">
            <div className="flex items-center justify-between">
              <div className="flex items-center gap-2 font-semibold text-emerald-600 dark:text-emerald-400 text-xs uppercase tracking-wider">
                <Sparkles className="h-4 w-4" /> Plain-English Solver Rationale
              </div>
              <span className="text-[11px] font-mono text-muted-foreground">
                Confidence: <strong className="text-emerald-600 font-bold">94.8%</strong> (P90 Risk Bound)
              </span>
            </div>
            <p className="leading-relaxed text-muted-foreground">
              By shifting <strong>Llama-3 Fine-Tuning Run #4</strong> and <strong>Monte Carlo Batch #812</strong> forward into the <strong>09:30 – 13:30 SGT</strong> window, this plan captures curtailed West Texas wind power pricing at <strong>$14.80/MWh</strong>.
              During the impending <strong>01:00 – 04:00 SGT</strong> ERCOT thermal price spike (forecasted at <strong>$142.50/MWh</strong> due to evening solar ramp-down), flexible crypto mining is throttled and cooling switches to pre-chill thermal reserves, shedding <strong>{formatMw(impact.peakReductionMw)}</strong> of peak facility demand.
            </p>
          </div>

          {/* Side-by-Side Impact Matrix (Baseline vs Proposed) */}
          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="font-semibold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
                <Layers className="h-3.5 w-3.5 text-primary" />
                Schedule Impact Matrix: Fixed Baseline vs. Optimized Dispatch
              </span>
              <span className="text-[11px] text-muted-foreground font-mono">
                {impact.includedCount} of {impact.totalCount} Workloads Selected
              </span>
            </div>

            <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-4 gap-3">
              {/* Metric 1: Cost */}
              <div className="rounded-xl border border-border bg-card p-3.5 space-y-1.5">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>24h Energy Cost</span>
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold flex items-center gap-0.5">
                    <TrendingDown className="h-3 w-3" />
                    -{impact.savingsPercent.toFixed(1)}%
                  </span>
                </div>
                <div className="flex items-baseline justify-between pt-0.5">
                  <span className="text-xs text-muted-foreground line-through font-mono">
                    {formatCurrency(impact.baselineCost)}
                  </span>
                  <span className="text-xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
                    {formatCurrency(impact.optimizedCost)}
                  </span>
                </div>
                <div className="text-[11px] text-emerald-600 dark:text-emerald-400 font-semibold flex items-center justify-between border-t border-border/50 pt-1.5">
                  <span>Net Savings</span>
                  <span className="font-mono font-bold">+{formatCurrency(impact.netSavings)}</span>
                </div>
              </div>

              {/* Metric 2: Peak MW Shed */}
              <div className="rounded-xl border border-border bg-card p-3.5 space-y-1.5">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>Peak Facility Load</span>
                  <span className="text-blue-600 dark:text-blue-400 font-semibold flex items-center gap-0.5">
                    <Zap className="h-3 w-3" />
                    -{formatMw(impact.peakReductionMw)}
                  </span>
                </div>
                <div className="flex items-baseline justify-between pt-0.5">
                  <span className="text-xs text-muted-foreground line-through font-mono">
                    {formatMw(impact.baselinePeakMw)}
                  </span>
                  <span className="text-xl font-bold font-mono text-blue-600 dark:text-blue-400">
                    {formatMw(impact.optimizedPeakMw)}
                  </span>
                </div>
                <div className="text-[11px] text-muted-foreground flex items-center justify-between border-t border-border/50 pt-1.5">
                  <span>Peak Window Shed</span>
                  <span className="font-mono font-semibold text-foreground">01:00–04:00 SGT</span>
                </div>
              </div>

              {/* Metric 3: Clean Energy Mix */}
              <div className="rounded-xl border border-border bg-card p-3.5 space-y-1.5">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>Clean Energy Mix</span>
                  <span className="text-teal-600 dark:text-teal-400 font-semibold flex items-center gap-0.5">
                    <TrendingUp className="h-3 w-3" />
                    +{(impact.cleanEnergyPercent - impact.cleanEnergyBaselinePercent).toFixed(1)}%
                  </span>
                </div>
                <div className="flex items-baseline justify-between pt-0.5">
                  <span className="text-xs text-muted-foreground line-through font-mono">
                    {impact.cleanEnergyBaselinePercent}%
                  </span>
                  <span className="text-xl font-bold font-mono text-teal-600 dark:text-teal-400">
                    {impact.cleanEnergyPercent}%
                  </span>
                </div>
                <div className="text-[11px] text-muted-foreground flex items-center justify-between border-t border-border/50 pt-1.5">
                  <span>Carbon Intensity</span>
                  <span className="font-mono font-semibold text-foreground">214 gCO2/kWh</span>
                </div>
              </div>

              {/* Metric 4: SLA Adherence */}
              <div className="rounded-xl border border-border bg-card p-3.5 space-y-1.5">
                <div className="flex items-center justify-between text-xs text-muted-foreground">
                  <span>SLA Adherence</span>
                  <span className="text-emerald-600 dark:text-emerald-400 font-semibold flex items-center gap-0.5">
                    <CheckCircle2 className="h-3 w-3" />
                    0 Risk
                  </span>
                </div>
                <div className="flex items-baseline justify-between pt-0.5">
                  <span className="text-xs text-muted-foreground font-mono">
                    Min Slack: {impact.minSlackHours}h
                  </span>
                  <span className="text-xl font-bold font-mono text-foreground">
                    100%
                  </span>
                </div>
                <div className="text-[11px] text-muted-foreground flex items-center justify-between border-t border-border/50 pt-1.5">
                  <span>All Deadlines Honored</span>
                  <span className="font-semibold text-emerald-600 dark:text-emerald-400">Safe</span>
                </div>
              </div>
            </div>
          </div>

          {/* Interactive Affected Workloads Table with Exclusion Toggles */}
          <div className="space-y-2">
            <div className="flex items-center justify-between text-xs">
              <span className="font-semibold text-muted-foreground uppercase tracking-wider flex items-center gap-1.5">
                <Cpu className="h-3.5 w-3.5 text-primary" />
                Affected Workload Schedules & Discretionary Exclusion
              </span>
              {!planApproved && !planRejected && (
                <span className="text-[11px] text-muted-foreground italic">
                  Toggle checkbox to include/exclude workload from this dispatch
                </span>
              )}
            </div>

            <div className="divide-y divide-border/60 rounded-xl border border-border bg-card overflow-hidden">
              {workloads.map((item) => {
                const isExpanded = expandedWorkloadId === item.id
                return (
                  <div
                    key={item.id}
                    className={`transition-colors ${
                      !item.included
                        ? "opacity-60 bg-muted/20"
                        : "hover:bg-muted/40"
                    }`}
                  >
                    <div className="p-3.5 flex flex-col md:flex-row md:items-center justify-between gap-3">
                      {/* Checkbox & Workload Identity */}
                      <div className="flex items-start gap-3">
                        <input
                          type="checkbox"
                          id={`toggle-${item.id}`}
                          checked={item.included}
                          disabled={planApproved || planRejected}
                          onChange={() => toggleWorkloadInclusion(item.id)}
                          className="mt-1 h-4 w-4 rounded border-border text-emerald-600 focus:ring-emerald-500 cursor-pointer disabled:cursor-not-allowed"
                        />
                        <div>
                          <div className="flex items-center gap-2 flex-wrap">
                            <label
                              htmlFor={`toggle-${item.id}`}
                              className="font-semibold text-sm text-foreground cursor-pointer"
                            >
                              {item.name}
                            </label>
                            <span className="rounded-md bg-muted px-2 py-0.5 text-[10px] font-mono text-muted-foreground">
                              {item.id}
                            </span>
                            <span className="rounded-md bg-primary/10 px-2 py-0.5 text-[10px] font-semibold text-primary">
                              {item.type}
                            </span>
                            <span className="font-mono text-xs font-semibold text-muted-foreground">
                              {item.powerKw} kW
                            </span>
                          </div>
                          <p className="text-xs text-muted-foreground font-mono mt-0.5">
                            {item.cluster}
                          </p>
                        </div>
                      </div>

                      {/* Schedule Window Comparison: Baseline -> Proposed */}
                      <div className="flex items-center gap-3 sm:gap-6 self-stretch md:self-auto justify-between md:justify-end">
                        <div className="flex items-center gap-2 text-xs font-mono">
                          <span className="text-muted-foreground line-through bg-muted/50 px-2 py-1 rounded">
                            {item.originalWindow}
                          </span>
                          <ArrowRight className="h-3.5 w-3.5 text-primary shrink-0" />
                          <span className="font-bold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 border border-emerald-500/20 px-2 py-1 rounded">
                            {item.proposedWindow}
                          </span>
                        </div>

                        <div className="text-right">
                          <p className="font-mono font-bold text-xs text-emerald-600 dark:text-emerald-400">
                            +{formatCurrency(item.savingsUsd)}
                          </p>
                          <p className="text-[10px] text-muted-foreground">
                            Slack: {item.slackHours}h
                          </p>
                        </div>

                        {/* Expand / Details Toggle */}
                        <Button
                          variant="ghost"
                          size="sm"
                          onClick={() => setExpandedWorkloadId(isExpanded ? null : item.id)}
                          className="h-7 w-7 p-0 text-muted-foreground hover:text-foreground"
                          aria-label={isExpanded ? "Collapse workload details" : "Expand workload details"}
                        >
                          {isExpanded ? (
                            <ChevronUp className="h-4 w-4" />
                          ) : (
                            <ChevronDown className="h-4 w-4" />
                          )}
                        </Button>
                      </div>
                    </div>

                    {/* Expandable Details Drawer */}
                    {isExpanded && (
                      <div className="px-10 pb-3 pt-1 text-xs border-t border-border/40 bg-muted/15 space-y-2">
                        <div className="grid grid-cols-1 sm:grid-cols-3 gap-2 py-1">
                          <div>
                            <span className="text-muted-foreground">Shift Rationale:</span>
                            <p className="font-medium text-foreground mt-0.5">{item.rationale}</p>
                          </div>
                          <div>
                            <span className="text-muted-foreground">Hard SLA Deadline:</span>
                            <p className="font-mono font-medium text-foreground mt-0.5">{item.deadline}</p>
                          </div>
                          <div>
                            <span className="text-muted-foreground">Baseline vs Optimized Cost:</span>
                            <p className="font-mono font-medium text-foreground mt-0.5">
                              {formatCurrency(item.baselineCostUsd)} ➔ {formatCurrency(item.optimizedCostUsd)}
                            </p>
                          </div>
                        </div>
                      </div>
                    )}
                  </div>
                )
              })}
            </div>
          </div>
        </CardContent>

        {/* Action Footer */}
        <CardFooter className="flex flex-col sm:flex-row sm:items-center justify-between gap-3 pt-2 border-t border-border/60">
          <div className="flex items-center gap-2 text-xs text-muted-foreground">
            <span className="font-mono">
              Model: XGBoost-LSTM (v2.4) • Dispatch Horizon: 24h • Settlement: ERCOT North
            </span>
          </div>

          <div className="flex items-center gap-2 self-end sm:self-auto">
            {!planApproved && !planRejected ? (
              <>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setIsRejectOpen(true)}
                  className="text-xs text-destructive hover:bg-destructive/10 hover:border-destructive/30"
                >
                  <XCircle className="h-3.5 w-3.5 mr-1.5" />
                  Reject Plan
                </Button>
                <Button
                  size="sm"
                  disabled={impact.includedCount === 0}
                  onClick={() => setIsConfirmOpen(true)}
                  className="text-xs bg-emerald-600 hover:bg-emerald-700 text-white font-semibold gap-1.5 shadow-sm"
                >
                  <CheckCircle2 className="h-4 w-4" />
                  Approve & Dispatch ({impact.includedCount} Workloads)
                </Button>
              </>
            ) : (
              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={handleResetToEvaluation}
                  className="text-xs gap-1.5"
                >
                  <RotateCcw className="h-3.5 w-3.5" />
                  {planApproved ? "Rollback & Modify Schedule" : "Re-evaluate Optimization Plan"}
                </Button>
                {planApproved && (
                  <span className="text-xs font-semibold text-emerald-600 dark:text-emerald-400 bg-emerald-500/10 px-2.5 py-1 rounded-md border border-emerald-500/20">
                    Live Dispatch Confirmed
                  </span>
                )}
              </div>
            )}
          </div>
        </CardFooter>
      </Card>

      {/* Confirmation Modal */}
      <DispatchConfirmationModal
        open={isConfirmOpen}
        onOpenChange={setIsConfirmOpen}
        impact={impact}
        workloads={workloads}
        onConfirm={handleApproveConfirm}
      />

      {/* Rejection / Request Changes Modal */}
      <RejectPlanModal
        open={isRejectOpen}
        onOpenChange={setIsRejectOpen}
        onReject={handleRejectSubmit}
      />
    </div>
  )
}
