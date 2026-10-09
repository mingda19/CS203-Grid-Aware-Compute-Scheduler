"use client"

import * as React from "react"
import {
  AlertTriangle,
  Zap,
  TrendingDown,
  TrendingUp,
  ShieldCheck,
  ShieldAlert,
  ArrowRight,
} from "lucide-react"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import type { WorkloadActionImpact } from "@/lib/workload-actions"

interface WorkloadActionDialogProps {
  impact: WorkloadActionImpact | null
  open: boolean
  onOpenChange: (open: boolean) => void
  onConfirm: () => void
}

export function WorkloadActionDialog({
  impact,
  open,
  onOpenChange,
  onConfirm,
}: WorkloadActionDialogProps) {
  if (!impact) return null

  const isDestructive = impact.confirmButtonVariant === "destructive"
  const isPowerDrop = impact.powerDeltaKw < 0

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md border-border bg-card">
        <DialogHeader>
          <div className="flex items-center gap-2">
            <div
              className={`p-2 rounded-lg ${
                isDestructive
                  ? "bg-rose-500/15 text-rose-500"
                  : impact.action === "throttle"
                  ? "bg-amber-500/15 text-amber-500"
                  : "bg-emerald-500/15 text-emerald-500"
              }`}
            >
              {isDestructive ? (
                <AlertTriangle className="h-5 w-5" />
              ) : (
                <Zap className="h-5 w-5" />
              )}
            </div>
            <div>
              <DialogTitle className="text-base font-semibold text-foreground">
                {impact.title}
              </DialogTitle>
              <DialogDescription className="text-xs text-muted-foreground mt-0.5">
                {impact.workloadName}
              </DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="space-y-3 py-2 text-xs">
          {/* Power Impact Card */}
          <div className="rounded-xl border border-border/80 bg-muted/30 p-3.5 space-y-2">
            <div className="flex items-center justify-between text-muted-foreground">
              <span className="font-medium">Electrical Draw Impact</span>
              <span className="font-mono text-[11px] font-semibold text-foreground">
                {impact.currentPowerKw} kW{" "}
                <ArrowRight className="inline h-3 w-3 mx-1 text-muted-foreground" />{" "}
                {impact.nextPowerKw} kW
              </span>
            </div>
            <div className="flex items-center justify-between pt-1 border-t border-border/40">
              <span className="text-[11px] text-muted-foreground">Net Facility Delta</span>
              <span
                className={`font-mono font-bold text-xs flex items-center gap-1 ${
                  isPowerDrop ? "text-emerald-500" : "text-amber-500"
                }`}
              >
                {isPowerDrop ? (
                  <TrendingDown className="h-3.5 w-3.5" />
                ) : (
                  <TrendingUp className="h-3.5 w-3.5" />
                )}
                {impact.powerDeltaKw > 0 ? `+${impact.powerDeltaKw}` : impact.powerDeltaKw} kW (
                {impact.powerDeltaPercent > 0 ? `+${impact.powerDeltaPercent}` : impact.powerDeltaPercent}%)
              </span>
            </div>
          </div>

          {/* SLA & Risk Assessment */}
          <div
            className={`rounded-xl border p-3.5 space-y-1.5 ${
              impact.slaRisk === "High"
                ? "border-rose-500/30 bg-rose-500/10 text-rose-700 dark:text-rose-300"
                : impact.slaRisk === "Moderate"
                ? "border-amber-500/30 bg-amber-500/10 text-amber-700 dark:text-amber-300"
                : "border-emerald-500/30 bg-emerald-500/10 text-emerald-700 dark:text-emerald-300"
            }`}
          >
            <div className="flex items-center justify-between font-semibold">
              <span className="flex items-center gap-1.5 text-xs">
                {impact.slaRisk === "High" || impact.slaRisk === "Moderate" ? (
                  <ShieldAlert className="h-4 w-4" />
                ) : (
                  <ShieldCheck className="h-4 w-4" />
                )}
                SLA Impact: {impact.slaRisk} Risk
              </span>
            </div>
            <p className="text-[11px] leading-relaxed opacity-90">{impact.slaNotice}</p>
          </div>

          {/* Operational Cost Notice */}
          <div className="rounded-xl border border-border/60 bg-muted/20 p-3 text-muted-foreground text-[11px] leading-relaxed">
            <span className="font-semibold text-foreground">Market Dispatch Effect: </span>
            {impact.costImpactNotice}
          </div>
        </div>

        <DialogFooter className="gap-2 sm:gap-0">
          <Button
            type="button"
            variant="outline"
            size="sm"
            onClick={() => onOpenChange(false)}
            className="text-xs"
          >
            Cancel
          </Button>
          <Button
            type="button"
            variant={isDestructive ? "destructive" : "default"}
            size="sm"
            onClick={() => {
              onConfirm()
              onOpenChange(false)
            }}
            className={`text-xs gap-1.5 ${
              !isDestructive && impact.action === "throttle"
                ? "bg-amber-600 hover:bg-amber-700 text-white"
                : !isDestructive
                ? "bg-emerald-600 hover:bg-emerald-700 text-white"
                : ""
            }`}
          >
            {impact.confirmButtonLabel}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
