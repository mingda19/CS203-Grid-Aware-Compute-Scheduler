"use client"

import * as React from "react"
import Link from "next/link"
import { ArrowLeft } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { usePlan } from "@/hooks/use-plan"
import { decisionUnavailable } from "@/lib/schedule-review-utils"
import type { ScheduleDecisionAction } from "@/lib/api"
import { RejectPlanModal } from "@/components/ui/efferd-dashboard-2-utils/reject-plan-modal"
import { PlanHeader, PlanRecommendation } from "./PlanRecommendation"

export function PlanPage({ runId }: { runId: string }) {
  const { plan, loading, error, decisionError, conflict, submitting, decide, retry } = usePlan(runId)
  const [action, setAction] = React.useState<ScheduleDecisionAction | null>(null)
  const back = <Link href={plan?.reviewState === "APPROVED" ? "/approvals?tab=APPROVED" : "/approvals"}
    className="inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground"><ArrowLeft className="h-4 w-4" />Approvals</Link>

  if (loading) return <div id="approvals-root" className="space-y-4" role="status" aria-label="Loading plan">
    {back}<Skeleton className="h-8 w-2/3" /><Skeleton className="h-10 w-1/2" /><Skeleton className="h-40 w-full" />
  </div>
  if (error || !plan) {
    const hidden = error?.status === 403 || error?.status === 404
    return <div id="approvals-root" className="space-y-3">
      {back}
      <p role="alert" className="text-sm text-destructive">{hidden ? "This plan does not exist or you cannot see it." : error?.message}</p>
      {!hidden && <Button variant="outline" size="sm" onClick={retry}>Retry</Button>}
    </div>
  }

  const state = plan.reviewState
  const approveBlocked = decisionUnavailable(plan, "approve")
  const canRevoke = !decisionUnavailable(plan, "revoke")
  return <div id="approvals-root" className="space-y-5">
    {back}
    <PlanHeader plan={plan} />
    {conflict && <p role="alert" className="rounded-md border border-amber-500 p-3 text-sm">This plan changed while you were looking at it, so nothing was recorded.</p>}
    <div className="space-y-2 rounded-md border p-3">
      {state === "PENDING" && plan.canApprove && <>
        <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={!!approveBlocked || submitting} onClick={() => setAction("approve")}>Approve</Button>
          <Button type="button" variant="outline" disabled={!!decisionUnavailable(plan, "revise") || submitting} onClick={() => setAction("revise")}>Request revision</Button>
          <Button type="button" variant="outline" className="text-destructive" disabled={!!decisionUnavailable(plan, "reject") || submitting} onClick={() => setAction("reject")}>Reject</Button>
        </div>
        {approveBlocked && <p className="text-sm text-muted-foreground">{approveBlocked}</p>}
      </>}
      {state === "PENDING" && !plan.canApprove && <p className="text-sm">{approveBlocked ?? "Nobody can approve this plan right now."}</p>}
      {state === "APPROVED" && <div className="flex flex-wrap items-center gap-2">
        <p className="text-sm">Approved. It is on the Schedule calendar.</p>
        <Button asChild variant="outline" size="sm"><Link href="/workloads">Open Schedule</Link></Button>
        {canRevoke && <Button type="button" variant="outline" size="sm" className="text-destructive" disabled={submitting} onClick={() => setAction("revoke")}>Revoke approval</Button>}
      </div>}
      {state === "REJECTED" && <p className="text-sm">Rejected. The jobs will not run on this plan.</p>}
      {state === "REVISION_REQUESTED" && <p className="text-sm">Revision requested. The owner sees your note in Audit.</p>}
      {(state === "STALE" || state === "INVALIDATED") && <div className="space-y-1 text-sm">
        <p>This plan is out of date. The system makes a new one within a minute.</p>
        {plan.invalidationReason && <p role="alert" className="text-destructive">{plan.invalidationReason}</p>}
        <Link href="/approvals" className="underline">Back to the list</Link>
      </div>}
    </div>
    <PlanRecommendation plan={plan} />
    {action && <RejectPlanModal key={action} open action={action} onOpenChange={open => { if (!open) setAction(null) }}
      onSubmit={async comment => { const result = await decide(action, comment); if (result === "conflict") setAction(null); return result === "ok" }}
      submitting={submitting} error={decisionError} />}
  </div>
}
