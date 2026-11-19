"use client"

import * as React from "react"
import { Dialog, DialogContent, DialogHeader, DialogTitle, DialogDescription, DialogFooter } from "@/components/ui/dialog"
import { Button } from "@/components/ui/button"
import type { ScheduleDecisionAction } from "@/lib/api"
import { rationaleError } from "@/lib/schedule-review-utils"

const labels = { approve: "Approve plan", reject: "Reject plan", revise: "Request revision",
  revoke: "Revoke approval" }
const descriptions = { approve: "It goes on the Schedule calendar.", revise: "Say what needs to change. The owner sees your note in Audit.",
  reject: "The jobs will not run on this plan.", revoke: "Jobs from this plan that have not started will not start." }

/** The reason dialog for a decision on a plan. */
export function RejectPlanModal({ open, action, onOpenChange, onSubmit, submitting, error }: {
  open: boolean
  action: ScheduleDecisionAction
  onOpenChange: (open: boolean) => void
  onSubmit: (comment: string) => Promise<boolean>
  submitting: boolean
  error: string | null
}) {
  const [comment, setComment] = React.useState("")
  const invalid = rationaleError(comment)
  return <Dialog open={open} onOpenChange={value => { if (!submitting) onOpenChange(value) }}>
    <DialogContent className="sm:max-w-lg" onEscapeKeyDown={event => { if (submitting) event.preventDefault() }}
      onPointerDownOutside={event => { if (submitting) event.preventDefault() }}>
      <DialogHeader>
        <DialogTitle>{labels[action]}</DialogTitle>
        <DialogDescription>{descriptions[action]}</DialogDescription>
      </DialogHeader>
      <label htmlFor="schedule-decision-comment" className="text-sm font-medium">Reason</label>
      <textarea id="schedule-decision-comment" rows={4} maxLength={2000} value={comment} disabled={submitting}
        aria-describedby="schedule-comment-help" className="w-full rounded-md border bg-background p-3 text-sm"
        onChange={event => setComment(event.target.value)} placeholder={action === "revise" ? "Describe the changes needed before approval…"
          : action === "revoke" ? "Explain why this approved schedule must be halted…"
          : "Explain why you are making this decision…"} />
      <p id="schedule-comment-help" className="text-xs text-muted-foreground">{comment.length > 1800 && `${comment.length}/2,000 characters`}</p>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
      <DialogFooter>
        <Button type="button" variant="outline" disabled={submitting} onClick={() => onOpenChange(false)}>Cancel</Button>
        <Button type="button" variant={action === "reject" || action === "revoke" ? "destructive" : "default"} disabled={submitting || !!invalid}
          onClick={async () => { if (await onSubmit(comment)) onOpenChange(false) }}>{submitting ? "Recording decision…" : labels[action]}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
}
