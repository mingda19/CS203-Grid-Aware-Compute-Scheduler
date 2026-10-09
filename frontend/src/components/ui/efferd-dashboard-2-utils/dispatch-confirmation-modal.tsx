"use client"

import * as React from "react"
import {
  ShieldCheck,
  Zap,
  Cpu,
  ArrowRight,
  AlertTriangle,
  Server,
  CheckCircle2,
  Loader2,
} from "lucide-react"

import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
  DialogDescription,
  DialogFooter,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import { Separator } from "@/components/ui/separator"
import {
  type DecisionImpactMetrics,
  type OptimizationWorkloadItem,
  formatCurrency,
  formatMw,
} from "@/lib/decision-utils"
import { getStoredUser } from "@/lib/api"

interface DispatchConfirmationModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  impact: DecisionImpactMetrics
  workloads: OptimizationWorkloadItem[]
  onConfirm: () => Promise<void> | void
}

export function DispatchConfirmationModal({
  open,
  onOpenChange,
  impact,
  workloads,
  onConfirm,
}: DispatchConfirmationModalProps) {
  const [isSubmitting, setIsSubmitting] = React.useState(false)
  const currentUser = getStoredUser()
  const includedWorkloads = workloads.filter((w) => w.included)

  const handleConfirm = async () => {
    setIsSubmitting(true)
    try {
      await onConfirm()
      onOpenChange(false)
    } finally {
      setIsSubmitting(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={(val) => !isSubmitting && onOpenChange(val)}>
      <DialogContent className="max-w-xl">
        <DialogHeader>
          <div className="flex items-center gap-2 text-emerald-600 dark:text-emerald-400 font-semibold text-xs tracking-wider uppercase mb-1">
            <ShieldCheck className="h-4 w-4" /> Final Dispatch Verification
          </div>
          <DialogTitle className="text-xl font-bold">
            Confirm Workload Schedule Dispatch
          </DialogTitle>
          <DialogDescription className="text-xs text-muted-foreground">
            This action will issue automated dispatch instructions to active cluster adapters and facility BMS systems.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 my-2 text-sm">
          {/* Key Impact Summary Card */}
          <div className="rounded-xl border border-emerald-500/30 bg-emerald-500/5 p-4 space-y-3">
            <div className="flex items-center justify-between">
              <span className="text-xs font-semibold text-muted-foreground">Optimization Batch</span>
              <span className="font-mono text-xs font-bold text-foreground">#2026-0920-04</span>
            </div>
            <div className="grid grid-cols-3 gap-2 text-center pt-1 border-t border-emerald-500/20">
              <div>
                <p className="text-[11px] text-muted-foreground">Net Cost Savings</p>
                <p className="text-base font-bold font-mono text-emerald-600 dark:text-emerald-400">
                  {formatCurrency(impact.netSavings)}
                </p>
                <p className="text-[10px] text-emerald-600 font-semibold">+{impact.savingsPercent.toFixed(1)}%</p>
              </div>
              <div className="border-x border-border/60">
                <p className="text-[11px] text-muted-foreground">Peak Load Shed</p>
                <p className="text-base font-bold font-mono text-blue-600 dark:text-blue-400">
                  {formatMw(impact.peakReductionMw)}
                </p>
                <p className="text-[10px] text-muted-foreground">Peak: {formatMw(impact.optimizedPeakMw)}</p>
              </div>
              <div>
                <p className="text-[11px] text-muted-foreground">Clean Energy Mix</p>
                <p className="text-base font-bold font-mono text-teal-600 dark:text-teal-400">
                  {impact.cleanEnergyPercent}%
                </p>
                <p className="text-[10px] text-teal-600 font-semibold">West Texas Wind</p>
              </div>
            </div>
          </div>

          {/* Execution Adapters Affected */}
          <div className="space-y-2">
            <p className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              Target Execution Adapters ({includedWorkloads.length} Workloads)
            </p>
            <div className="space-y-2 max-h-40 overflow-y-auto pr-1">
              {includedWorkloads.map((item) => (
                <div
                  key={item.id}
                  className="flex items-center justify-between text-xs p-2.5 rounded-lg border border-border bg-card hover:bg-muted/30 transition-colors"
                >
                  <div className="flex items-center gap-2">
                    <Server className="h-3.5 w-3.5 text-emerald-500 shrink-0" />
                    <div>
                      <p className="font-semibold text-foreground">{item.name}</p>
                      <p className="text-[11px] text-muted-foreground font-mono">{item.cluster} • {item.powerKw} kW</p>
                    </div>
                  </div>
                  <div className="text-right">
                    <span className="font-mono text-emerald-600 dark:text-emerald-400 font-bold">
                      +{formatCurrency(item.savingsUsd)}
                    </span>
                    <p className="text-[10px] text-muted-foreground">{item.proposedWindow}</p>
                  </div>
                </div>
              ))}
            </div>
          </div>

          {/* Operational Safety Notice */}
          <div className="flex items-start gap-2.5 p-3 rounded-lg border border-amber-500/30 bg-amber-500/10 text-xs text-muted-foreground">
            <AlertTriangle className="h-4 w-4 text-amber-500 shrink-0 mt-0.5" />
            <div className="space-y-0.5">
              <p className="font-semibold text-amber-600 dark:text-amber-400">
                Automated Volatility Circuit Breaker
              </p>
              <p className="leading-relaxed">
                If real-time ERCOT LMP diverges more than <strong>35%</strong> from the solver forecast window, jobs will safely fail over to baseline throttle profiles.
              </p>
            </div>
          </div>

          <Separator />

          {/* Operator Sign-off Attribution */}
          <div className="flex items-center justify-between text-xs text-muted-foreground px-1">
            <span>Operator Sign-off:</span>
            <span className="font-mono font-medium text-foreground">
              {currentUser?.fullName || currentUser?.email || "System Administrator"} (
              {currentUser?.role === "ROLE_ADMIN" ? "Admin" : "Operator"})
            </span>
          </div>
        </div>

        <DialogFooter className="gap-2 sm:gap-0">
          <Button
            type="button"
            variant="outline"
            disabled={isSubmitting}
            onClick={() => onOpenChange(false)}
            className="text-xs"
          >
            Cancel Review
          </Button>
          <Button
            type="button"
            disabled={isSubmitting || includedWorkloads.length === 0}
            onClick={handleConfirm}
            className="text-xs bg-emerald-600 hover:bg-emerald-700 text-white font-semibold gap-1.5 shadow-sm"
          >
            {isSubmitting ? (
              <>
                <Loader2 className="h-3.5 w-3.5 animate-spin" />
                Dispatching to Clusters...
              </>
            ) : (
              <>
                <CheckCircle2 className="h-3.5 w-3.5" />
                Confirm Dispatch ({includedWorkloads.length} Jobs)
              </>
            )}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
