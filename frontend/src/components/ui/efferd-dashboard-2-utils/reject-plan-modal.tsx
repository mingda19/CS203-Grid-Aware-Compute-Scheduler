"use client"

import * as React from "react"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import type { ScheduleDecisionAction } from "@/lib/api"
import { rationaleError } from "@/lib/schedule-review-utils"

const labels = { approve: "Approve schedule", reject: "Reject schedule", revise: "Request revision",
  revoke: "Revoke approval" }

/** Shared justification dialog for the three operator decisions. */
export function RejectPlanModal({ open, action, runId, onOpenChange, onSubmit, submitting, error, blocked }: {
  open: boolean
  action: ScheduleDecisionAction
  runId: string
  onOpenChange: (open: boolean) => void
  onSubmit: (comment: string) => Promise<boolean>
  submitting: boolean
  error: string | null
  blocked: boolean
}) {
  const [comment, setComment] = React.useState("")
  const invalid = rationaleError(comment)
  return <Dialog open={open} onOpenChange={value => { if (!submitting) onOpenChange(value) }}>
    <DialogContent className="sm:max-w-lg" onEscapeKeyDown={event => { if (submitting) event.preventDefault() }}
      onPointerDownOutside={event => { if (submitting) event.preventDefault() }}>
      <DialogHeader>
        <DialogTitle>{labels[action]}</DialogTitle>
        <DialogDescription className="break-all">Run {runId}. This records your decision and justification. {action === "revoke" ? "Workloads already dispatched from this schedule will be blocked from starting or resuming." : action === "approve" ? "Approving also adds the plan to the Schedule calendar." : "Nothing is added to the calendar by this action."}</DialogDescription>
      </DialogHeader>
      <label htmlFor="schedule-decision-comment" className="text-sm font-medium">Justification (required)</label>
      <textarea id="schedule-decision-comment" rows={4} maxLength={2000} value={comment} disabled={submitting}
        aria-describedby="schedule-comment-help" className="w-full rounded-md border bg-background p-3 text-sm"
        onChange={event => setComment(event.target.value)} placeholder={action === "revise" ? "Describe the changes needed before approval…"
          : action === "revoke" ? "Explain why this approved schedule must be halted…"
          : "Explain why you are making this decision…"} />
      <p id="schedule-comment-help" className="text-xs text-muted-foreground">{comment.length}/2,000 characters. {action === "revise" && "Requesting a revision records feedback; generate a new recommendation after updating the job constraints."}</p>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      {blocked && <p role="alert" className="text-sm text-destructive">This plan cannot be decided on. Close this dialog and refresh the plans.</p>}
      <DialogFooter>
        <Button type="button" variant="outline" disabled={submitting} onClick={() => onOpenChange(false)}>Cancel</Button>
        <Button type="button" variant={action === "reject" || action === "revoke" ? "destructive" : "default"} disabled={submitting || blocked || !!invalid}
          onClick={async () => { if (await onSubmit(comment)) onOpenChange(false) }}>{submitting ? "Recording decision…" : labels[action]}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
}
