import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { ExplanationSummary } from "@/components/ui/explanation-summary"
import { formatMarketDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { money } from "@/lib/format"
import { jobNamesLabel, reviewLabels } from "@/lib/schedule-review-utils"
import type { SchedulePlanDetail } from "@/lib/api"

const badgeColour = (state: SchedulePlanDetail["reviewState"]) =>
  state === "APPROVED" ? "border-emerald-500 text-emerald-700 dark:text-emerald-400"
    : state === "STALE" || state === "INVALIDATED" || state === "REJECTED" ? "border-destructive text-destructive"
    : "border-amber-500 text-amber-700 dark:text-amber-400"

/** The job names, then who submitted the plan. Jobs deleted after approval are left out of the title. */
export function PlanHeader({ plan }: { plan: SchedulePlanDetail }) {
  const names = (plan.recommendation?.items ?? []).filter(item => !plan.removedJobIds.includes(item.jobId)).map(item => item.jobName)
  return <header className="space-y-1">
    <div className="flex flex-wrap items-start justify-between gap-2">
      <h1 className="min-w-0 break-words text-xl font-semibold">{plan.recommendation ? jobNamesLabel(names, 3) : "Plan"}</h1>
      <span className={`shrink-0 rounded-full border px-3 py-1 text-sm font-semibold ${badgeColour(plan.reviewState)}`}>{reviewLabels[plan.reviewState]}</span>
    </div>
    <p className="text-sm text-muted-foreground">Submitted by {plan.ownerName}</p>
  </header>
}

/** What the plan does: the savings, each job's new time, why. Nothing here changes the plan. */
export function PlanRecommendation({ plan }: { plan: SchedulePlanDetail }) {
  const rec = plan.recommendation
  return <section aria-label="Plan details" className="space-y-4">
    {rec && <>
      <div className="space-y-1">
        <p className="flex flex-wrap items-center gap-2 text-2xl font-semibold">
          {rec.projectedSavingsUsd >= 0 ? `Saves ${money(rec.projectedSavingsUsd)}` : `Costs ${money(-rec.projectedSavingsUsd)} more`}
          <span className="text-base font-normal text-muted-foreground">({rec.projectedSavingsPercent.toFixed(1)}%)</span>
          {rec.usedSeededFallbackPrices && <EstimatedPriceBadge />}
        </p>
        <p className="text-sm text-muted-foreground">{money(rec.baselineCostUsd)} → {money(rec.optimizedCostUsd)} compared with running at the original times</p>
      </div>
      {rec.status === "NEEDS_ATTENTION" && <p role="status" className="text-sm text-amber-700 dark:text-amber-400">Some jobs could not be placed.</p>}
      <div className="space-y-1">
        <div className="overflow-x-auto rounded-md border"><table className="w-full text-left text-sm">
          <caption className="sr-only">Each job in this plan, when it runs and what it saves</caption>
          <thead className="text-xs text-muted-foreground"><tr>{["Job", "Runs", "Was", "Saves"].map(label => <th key={label} className="p-2 font-medium">{label}</th>)}</tr></thead>
          <tbody>{rec.items.map(item => {
            const removed = plan.removedJobIds.includes(item.jobId)
            return <tr key={item.jobId} className="border-t align-top">
              <td className="p-2 font-medium">{item.jobName}
                {removed && <p className="text-xs font-normal text-destructive">Deleted</p>}</td>
              {item.perJobStatus === "NO_FEASIBLE_SLOT" || !item.scheduledStart || !item.scheduledEnd
                ? <td colSpan={2} className="p-2 text-muted-foreground">Not placed{item.reason ? `: ${item.reason}` : ""}</td>
                : <>
                  <td className="p-2">{formatMarketDateTime(item.scheduledStart, false)} – {formatMarketDateTime(item.scheduledEnd, false)}
                    {item.perJobStatus === "UNCHANGED_EXEMPT" && <p className="text-xs text-muted-foreground">Not moved (exempt)</p>}
                    {item.rescheduledFromElapsedStart && <p className="text-xs text-amber-700 dark:text-amber-400">Moved because its start time had passed</p>}</td>
                  <td className="p-2 text-muted-foreground">{formatMarketDateTime(item.baselineStart, false)}</td>
                </>}
              <td className="p-2">{item.savingsUsd == null ? "—" : money(item.savingsUsd)}</td>
            </tr>
          })}</tbody>
        </table></div>
        <p className="text-xs text-muted-foreground">Times are in {MARKET_ZONE_LABEL}.</p>
      </div>
      <details className="rounded-md border p-3">
        <summary className="cursor-pointer text-sm font-medium">Why these times</summary>
        <ExplanationSummary text={rec.plainEnglishExplanation} className="mt-3" />
      </details>
    </>}
    <p className="break-all text-xs text-muted-foreground">Run ID {plan.runId}</p>
  </section>
}
