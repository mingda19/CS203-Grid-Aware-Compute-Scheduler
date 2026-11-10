"use client"

import * as React from "react"
import Link from "next/link"
import { RefreshCw } from "lucide-react"
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { useScheduleReview, type ReviewTab } from "@/hooks/use-schedule-review"
import { reviewLabels, decisionUnavailable, planUnavailable } from "@/lib/schedule-review-utils"
import { ExplanationSummary } from "@/components/ui/explanation-summary"
import { formatMarketDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import type { ScheduleDecisionAction, SchedulePlanDetail } from "@/lib/api"
import { RejectPlanModal } from "./reject-plan-modal"

export function DecisionPanel({ onDecision }: { onDecision?: () => void }) {
  const review = useScheduleReview(onDecision)
  const [action, setAction] = React.useState<ScheduleDecisionAction | null>(null)
  // The hint shown under the recommendation (line below) reports why THIS action is blocked,
  // including a terminal decision; the "disabled" gate for the picker/refresh/calendar controls
  // only reflects whether the plan itself is usable at all, not which decision it already holds -
  // otherwise an APPROVED plan (which rejects/revises as "already decided") would also disable
  // the surrounding controls.
  const unavailable = decisionUnavailable(review.plan, "reject")
  // Why Approve is off for this plan, shown only while it is still waiting for a decision.
  const approveBlocked = review.plan?.reviewState === "PENDING" ? decisionUnavailable(review.plan, "approve") : null
  const disabled = review.submitting || review.listLoading || review.detailLoading || !!review.listError || !!review.detailError || review.conflict || !!planUnavailable(review.plan)
  return <Card id="approvals">
    <CardHeader>
      <div className="flex items-center justify-between gap-3">
        <CardTitle>Plans</CardTitle>
        <Button type="button" variant="outline" size="sm" disabled={review.submitting || review.listLoading || review.detailLoading || !!action} onClick={review.refresh}>
          <RefreshCw className="mr-2 h-4 w-4" />Refresh plans
        </Button>
      </div>
      <CardDescription>The system plans every new job. Anyone except the person who submitted a plan can approve it. Approving also adds the plan to the Schedule calendar.</CardDescription>
    </CardHeader>
    <CardContent className="space-y-4">
      {review.listLoading && <p role="status">Loading schedule plans…</p>}
      {review.listError && <p role="alert" className="text-sm text-destructive">{review.listError} Use “Refresh plans” to retry.</p>}
      {!review.listLoading && !review.listError && !review.plans.length && <p className="text-sm text-muted-foreground">{review.tab === "PENDING" ? "No plans are waiting for a decision." : "No plans are approved."} <Link className="underline" href="/jobs">Add a job</Link> and the system plans it.</p>}
      <div role="tablist" aria-label="Plans to show" className="flex w-fit rounded-md border">
        {(["PENDING", "APPROVED"] as ReviewTab[]).map(value => (
          <Button key={value} type="button" role="tab" size="sm" aria-selected={review.tab === value} variant={review.tab === value ? "secondary" : "ghost"}
            disabled={review.submitting || !!action} onClick={() => review.changeTab(value)}>{value === "PENDING" ? "Pending" : "Approved"}</Button>
        ))}
      </div>
      {!!review.plans.length && <div className="space-y-2">
        <ul aria-label={review.tab === "PENDING" ? "Pending plans" : "Approved plans"} className="divide-y rounded-md border">
          {review.plans.map(plan => {
            const state = plan.runId === review.plan?.runId ? review.plan.reviewState : plan.reviewState
            return <li key={plan.runId}>
              <button type="button" aria-current={plan.runId === review.selected} disabled={review.submitting || !!action}
                className={`flex w-full flex-wrap items-center justify-between gap-x-4 gap-y-1 px-3 py-2 text-left text-sm hover:bg-muted/50 ${plan.runId === review.selected ? "bg-muted" : ""}`}
                onClick={() => review.select(plan.runId)}>
                <span className="font-medium">{plan.ownerName}</span>
                <span className="text-xs text-muted-foreground">{formatMarketDateTime(plan.createdAt)} · {reviewLabels[state]}{plan.canApprove || state !== "PENDING" ? "" : " · you submitted this"}</span>
              </button>
            </li>
          })}
        </ul>
        <div className="flex items-center gap-3 text-xs">
          <Button type="button" size="sm" variant="outline" disabled={review.page === 0 || review.submitting || !!action} onClick={() => review.changePage(review.page - 1)}>Previous</Button>
          <span>Page {review.page + 1} of {Math.max(1, review.totalPages)}</span>
          <Button type="button" size="sm" variant="outline" disabled={review.page + 1 >= review.totalPages || review.submitting || !!action} onClick={() => review.changePage(review.page + 1)}>Next</Button>
        </div>
      </div>}
      {review.detailLoading && <p role="status">Loading recommendation and checking its validity…</p>}
      {review.detailError && <p role="alert" className="text-sm text-destructive">{review.detailError} Refresh plans to retry.</p>}
      {review.conflict && <div role="alert" className="rounded-md border border-amber-500 p-3 text-sm">This plan was updated, so it could not be decided as shown: another approval, a change to a job or the facility, or time passing replaced it. {review.decisionError} The list has been reloaded. Pick the current plan for these jobs; the system plans them again within a minute if there is none yet.</div>}
      {review.decisionError && !review.conflict && <p role="alert" className="text-sm text-destructive">{review.decisionError}</p>}
      {review.notice && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">{review.notice}</p>}
      {review.plan && <>
        <PlanRecommendation plan={review.plan} />
        {unavailable && <p className="text-sm text-muted-foreground">{unavailable} <Link href="/jobs" className="underline">Open Jobs</Link></p>}
        {!unavailable && approveBlocked && <p role="status" className="text-sm text-muted-foreground">{approveBlocked}</p>}
        <div className="flex flex-wrap gap-2">
          <Button type="button" disabled={disabled || !!decisionUnavailable(review.plan, "approve")} onClick={() => setAction("approve")}>{review.plan.reviewState === "APPROVED" ? "Schedule approved" : "Approve schedule"}</Button>
          <Button type="button" variant="destructive" disabled={disabled || !!decisionUnavailable(review.plan, "reject")} onClick={() => setAction("reject")}>{review.plan.reviewState === "REJECTED" ? "Schedule rejected" : "Reject schedule"}</Button>
          <Button type="button" variant="outline" disabled={disabled || !!decisionUnavailable(review.plan, "revise")} onClick={() => setAction("revise")}>Request revision</Button>
          {/* Only an approved plan can be revoked, so the control appears only once there is an
              approval to withdraw rather than sitting disabled next to the first-decision buttons. */}
          {review.plan.reviewState === "APPROVED" && (
            <Button type="button" variant="destructive" disabled={disabled || !!decisionUnavailable(review.plan, "revoke")}
              onClick={() => setAction("revoke")}>Revoke approval</Button>
          )}
          {review.plan.reviewState === "APPROVED" && <Button type="button" variant="outline" asChild><Link href="/workloads">Open Schedule</Link></Button>}
        </div>
      </>}
      {action && review.plan && <RejectPlanModal key={`${review.plan.runId}:${action}`} open action={action} runId={review.plan.runId}
        onOpenChange={open => { if (!open) setAction(null) }} onSubmit={comment => review.decide(action, comment)}
        submitting={review.submitting} error={review.decisionError} blocked={review.conflict || !!decisionUnavailable(review.plan, action)} />}
    </CardContent>
  </Card>
}

export function PlanRecommendation({ plan }: { plan: SchedulePlanDetail }) {
  const recommendation = plan.recommendation
  return <section aria-label="Recommendation details" className="space-y-3">
    <div className="flex flex-wrap items-center gap-2">
      <span className={`rounded-full border px-3 py-1 text-sm font-semibold ${plan.reviewState === "APPROVED" ? "border-emerald-500 text-emerald-700 dark:text-emerald-400" : plan.reviewState === "STALE" || plan.reviewState === "INVALIDATED" || plan.reviewState === "REJECTED" ? "border-destructive text-destructive" : "border-amber-500 text-amber-700 dark:text-amber-400"}`}>{reviewLabels[plan.reviewState]}</span>
      <span className="text-xs text-muted-foreground">Submitted by {plan.ownerName}</span>
      <span className="break-all text-xs text-muted-foreground">Run {plan.runId}</span>
    </div>
    {plan.invalidationReason && <p role="alert" className="text-sm text-destructive">{plan.invalidationReason}</p>}
    {recommendation && <>
      <ExplanationSummary text={recommendation.plainEnglishExplanation} />
      <dl className="grid gap-3 rounded-md border p-3 text-sm sm:grid-cols-3">
        <div><dt className="text-muted-foreground">Baseline cost (USD)</dt><dd>{money(recommendation.baselineCostUsd)}</dd></div>
        <div><dt className="text-muted-foreground">Proposed cost (USD)</dt><dd>{money(recommendation.optimizedCostUsd)}</dd></div>
        <div><dt className="text-muted-foreground">Projected savings (USD)</dt><dd>{money(recommendation.projectedSavingsUsd)}</dd></div>
      </dl>
      {recommendation.status === "NEEDS_ATTENTION" && <p role="status" className="text-sm text-amber-700 dark:text-amber-400">Some jobs have no feasible placement. Review those jobs before approving.</p>}
      <div className="overflow-x-auto rounded-md border"><table className="w-full text-left text-xs">
        <caption className="sr-only">Original and recommended job placements in market local time</caption>
        <thead><tr>{["Job", `Original start (${MARKET_ZONE_LABEL})`, `Proposed start (${MARKET_ZONE_LABEL})`, `Proposed end (${MARKET_ZONE_LABEL})`, "Outcome", "Savings (USD)"].map(label => <th key={label} className="p-2">{label}</th>)}</tr></thead>
        <tbody>{recommendation.items.map(item => <tr key={item.jobId} className="border-t">
          <td className="p-2">{item.jobName} <span className="text-muted-foreground">(#{item.jobId})</span>
            {/* Deleted after approval. The stored plan is unchanged, so it still lists the job. */}
            {item.scheduledStart && plan.removedJobIds.includes(item.jobId) && <p className="text-destructive">Deleted</p>}</td><td className="p-2">{formatMarketDateTime(item.baselineStart)}</td>
          <td className="p-2">{item.scheduledStart ? formatMarketDateTime(item.scheduledStart) : "Not scheduled"}</td>
          <td className="p-2">{item.scheduledEnd ? formatMarketDateTime(item.scheduledEnd) : "—"}</td>
          <td className="p-2">{item.perJobStatus.replaceAll("_", " ").toLowerCase()}{item.reason && <p>{item.reason}</p>}
            {/* Why it moved: its start had passed, not that a cheaper hour was found. */}
            {item.rescheduledFromElapsedStart && <p className="text-amber-700 dark:text-amber-400">Rescheduled: start time had passed</p>}</td>
          <td className="p-2">{item.savingsUsd == null ? "Unavailable" : money(item.savingsUsd)}</td>
        </tr>)}</tbody>
      </table></div>
    </>}
  </section>
}

function money(value: number) { return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", maximumFractionDigits: 4 }).format(value) }
