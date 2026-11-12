"use client"

import * as React from "react"
import Link from "next/link"
import { ArrowLeft, TriangleAlert } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
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

  if (loading) return <div id="approvals-root" className="space-y-6" role="status" aria-label="Loading plan">
    {back}<Skeleton className="h-7 w-2/3" /><Skeleton className="h-16 w-full" /><Skeleton className="h-40 w-full" />
  </div>
  if (error || !plan) {
    const hidden = error?.status === 403 || error?.status === 404
    return <div id="approvals-root" className="space-y-6">
      {back}
      <Alert variant="destructive" className="flex items-center justify-between gap-3">
        <AlertDescription className="min-w-0 break-words">{hidden ? "This plan does not exist or you cannot see it." : error?.message}</AlertDescription>
        {!hidden && <Button variant="outline" size="sm" className="shrink-0" onClick={retry}>Retry</Button>}
      </Alert>
    </div>
  }

  const state = plan.reviewState
  const approveBlocked = decisionUnavailable(plan, "approve")
  const canRevoke = !decisionUnavailable(plan, "revoke")

  // The decision bar: what the state means on the left, what can be done on the right.
  let message: React.ReactNode = null
  let actions: React.ReactNode = null
  if (state === "PENDING" && plan.canApprove) {
    message = <p>{approveBlocked ?? "This plan is waiting for your decision."}</p>
    actions = <>
      <Button type="button" size="sm" disabled={!!approveBlocked || submitting} onClick={() => setAction("approve")}>Approve</Button>
      <Button type="button" size="sm" variant="outline" disabled={!!decisionUnavailable(plan, "revise") || submitting} onClick={() => setAction("revise")}>Request revision</Button>
      <Button type="button" size="sm" variant="outline" className="text-destructive-foreground" disabled={!!decisionUnavailable(plan, "reject") || submitting} onClick={() => setAction("reject")}>Reject</Button>
    </>
  } else if (state === "PENDING") {
    message = <p>{approveBlocked ?? "Nobody can approve this plan right now."}</p>
  } else if (state === "APPROVED") {
    message = <p>Approved. It is on the Schedule calendar.</p>
    actions = <>
      <Button asChild variant="outline" size="sm"><Link href="/workloads">Open Schedule</Link></Button>
      {canRevoke && <Button type="button" variant="outline" size="sm" className="text-destructive-foreground" disabled={submitting} onClick={() => setAction("revoke")}>Revoke approval</Button>}
    </>
  } else if (state === "REJECTED") {
    message = <p>Rejected. The jobs will not run on this plan.</p>
  } else if (state === "REVISION_REQUESTED") {
    message = <p>Revision requested. The owner sees your note in Audit.</p>
  } else {
    message = <>
      <p>This plan is out of date. The system makes a new one within a minute.</p>
      {plan.invalidationReason && <p role="alert" className="mt-1 text-destructive-foreground">{plan.invalidationReason}</p>}
    </>
    actions = <Button asChild variant="outline" size="sm"><Link href="/approvals">Back to the list</Link></Button>
  }

  return <div id="approvals-root" className="space-y-6">
    {back}
    <PlanHeader plan={plan} />
    {conflict && <Alert variant="warning" role="alert">
      <TriangleAlert className="size-4" aria-hidden="true" /><AlertDescription>This plan changed while you were looking at it, so nothing was recorded.</AlertDescription>
    </Alert>}
    <Card>
      <CardContent className="flex flex-col gap-3 p-4 pt-4 sm:flex-row sm:items-center sm:justify-between sm:gap-4 sm:p-5 sm:pt-5">
        <div className="min-w-0 text-sm">{message}</div>
        {actions && <div className="flex shrink-0 flex-wrap gap-2">{actions}</div>}
      </CardContent>
    </Card>
    <PlanRecommendation plan={plan} />
    {action && <RejectPlanModal key={action} open action={action} onOpenChange={open => { if (!open) setAction(null) }}
      onSubmit={async comment => { const result = await decide(action, comment); if (result === "conflict") setAction(null); return result === "ok" }}
      submitting={submitting} error={decisionError} />}
  </div>
}
