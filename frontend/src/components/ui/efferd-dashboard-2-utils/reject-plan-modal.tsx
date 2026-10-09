"use client"

import * as React from "react"
import {
  XCircle,
  AlertCircle,
  RotateCcw,
  MessageSquare,
  Check,
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

interface RejectPlanModalProps {
  open: boolean
  onOpenChange: (open: boolean) => void
  onReject: (reason: string, notes: string) => void
}

const REJECTION_REASONS = [
  {
    id: "priority_override",
    label: "Priority Workload SLA / Deadline Override",
    description: "An offline requirement necessitates running high-priority jobs immediately.",
  },
  {
    id: "maintenance",
    label: "Facility / Hardware Maintenance Window",
    description: "Unplanned server or chiller maintenance limits execution capacity.",
  },
  {
    id: "price_uncertainty",
    label: "Uncertainty in ERCOT Spot Price Volatility",
    description: "Extreme weather or transmission curtailment makes forecast risky.",
  },
  {
    id: "manual_dispatch",
    label: "Manual Operational Dispatch Required",
    description: "Operator requires direct hands-on control over job queues.",
  },
]

export function RejectPlanModal({
  open,
  onOpenChange,
  onReject,
}: RejectPlanModalProps) {
  const [selectedReason, setSelectedReason] = React.useState(REJECTION_REASONS[0].label)
  const [notes, setNotes] = React.useState("")

  const handleSubmit = () => {
    onReject(selectedReason, notes)
    onOpenChange(false)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="max-w-md">
        <DialogHeader>
          <div className="flex items-center gap-2 text-destructive font-semibold text-xs tracking-wider uppercase mb-1">
            <XCircle className="h-4 w-4" /> Optimization Rejection
          </div>
          <DialogTitle className="text-lg font-bold">
            Reject Schedule Optimization
          </DialogTitle>
          <DialogDescription className="text-xs text-muted-foreground">
            Dismissing this recommendation will keep all workloads on their standard fixed schedule baseline.
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-4 my-2 text-sm">
          <div className="space-y-2">
            <label className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
              Select Rationale
            </label>
            <div className="space-y-2">
              {REJECTION_REASONS.map((reason) => {
                const isSelected = selectedReason === reason.label
                return (
                  <button
                    key={reason.id}
                    type="button"
                    onClick={() => setSelectedReason(reason.label)}
                    className={`w-full text-left p-3 rounded-xl border text-xs transition-all flex items-start justify-between gap-2 ${
                      isSelected
                        ? "border-destructive bg-destructive/5 text-foreground ring-1 ring-destructive/40"
                        : "border-border bg-card text-muted-foreground hover:border-muted-foreground/40"
                    }`}
                  >
                    <div>
                      <p className={`font-semibold ${isSelected ? "text-foreground" : ""}`}>{reason.label}</p>
                      <p className="text-[11px] text-muted-foreground mt-0.5">{reason.description}</p>
                    </div>
                    {isSelected && (
                      <span className="h-4 w-4 rounded-full bg-destructive text-white flex items-center justify-center shrink-0 mt-0.5">
                        <Check className="h-2.5 w-2.5 stroke-[3]" />
                      </span>
                    )}
                  </button>
                )
              })}
            </div>
          </div>

          <div className="space-y-1.5">
            <label className="text-xs font-semibold text-muted-foreground flex items-center gap-1.5">
              <MessageSquare className="h-3.5 w-3.5" />
              Additional Solver Feedback / Notes (Optional)
            </label>
            <textarea
              value={notes}
              onChange={(e) => setNotes(e.target.value)}
              placeholder="Provide context for the optimization engine log..."
              rows={3}
              className="w-full rounded-lg border border-border bg-background p-2.5 text-xs text-foreground placeholder:text-muted-foreground focus:outline-none focus:ring-1 focus:ring-ring resize-none"
            />
          </div>

          <div className="p-3 rounded-lg border border-border bg-muted/30 text-[11px] text-muted-foreground flex items-start gap-2">
            <AlertCircle className="h-3.5 w-3.5 text-muted-foreground shrink-0 mt-0.5" />
            <p>
              Workloads will remain in <strong>Fixed Baseline</strong> mode. You can re-run the solver at any time from the Cockpit.
            </p>
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
            variant="destructive"
            size="sm"
            onClick={handleSubmit}
            className="text-xs gap-1.5"
          >
            <XCircle className="h-3.5 w-3.5" />
            Dismiss & Keep Fixed Schedule
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
