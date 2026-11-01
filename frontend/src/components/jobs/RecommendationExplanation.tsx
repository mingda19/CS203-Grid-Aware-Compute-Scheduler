import * as React from "react"
import type { JobExplanation, ScheduleItem } from "@/lib/api"

export function RecommendationExplanation({ item, explanation, loading, error, onRetry }: {
  item: ScheduleItem
  explanation?: JobExplanation
  loading: boolean
  error: string
  onRetry: () => void
}) {
  return (
    <details className="rounded-lg border bg-muted/30 p-3 text-xs">
      <summary className="cursor-pointer font-medium">Why this recommendation? — {item.jobName}</summary>
      <div className="mt-3 space-y-3">
        {loading ? <p role="status">Loading explanation…</p> : error ? (
          <div role="alert">{error} <button type="button" onClick={onRetry} className="underline">Retry</button></div>
        ) : <p className="whitespace-pre-wrap">{explanation?.whyThisPlacement || item.reason || "No explanation is available for this recommendation."}</p>}
        <dl className="grid gap-2 sm:grid-cols-3">
          <div><dt className="text-muted-foreground">Baseline cost (USD)</dt><dd>{money(item.baselineCostUsd)}</dd></div>
          <div><dt className="text-muted-foreground">Proposed cost (USD)</dt><dd>{money(item.optimizedCostUsd)}</dd></div>
          <div><dt className="text-muted-foreground">Cost difference</dt><dd className={item.savingsUsd != null && item.savingsUsd < 0 ? "text-amber-700 dark:text-amber-400" : ""}>
            {item.savingsUsd == null ? "Unavailable — job was not scheduled" : item.savingsUsd > 0 ? `${money(item.savingsUsd)} savings` : item.savingsUsd < 0 ? `${money(-item.savingsUsd)} increase` : "No cost change"}
          </dd></div>
        </dl>
        {!loading && !error && <div>
          <p className="mb-1 font-medium">{item.perJobStatus === "NO_FEASIBLE_SLOT" ? "Scheduling constraints" : "Constraints respected"}</p>
          {explanation?.constraints.length ? <ul className="list-disc space-y-1 pl-4">
            {explanation.constraints.map((constraint, index) => <li key={`${constraint.constraintType}-${index}`}>
              <span className="font-medium">{constraint.constraintType.replaceAll("_", " ").toLowerCase()}: </span>
              {constraint.explanation}
              {constraint.slackHours != null && <span className="text-muted-foreground"> ({constraint.slackHours} hours of slack)</span>}
            </li>)}
          </ul> : <p className="text-muted-foreground">{item.perJobStatus === "NO_FEASIBLE_SLOT" ? "No feasible placement was found; no constraints are reported as satisfied." : item.perJobStatus === "UNCHANGED_EXEMPT" ? "Exempt from rescheduling; original start time retained." : "No constraint details were returned."}</p>}
        </div>}
      </div>
    </details>
  )
}

function money(value: number | null) {
  return value == null ? "Unavailable" : new Intl.NumberFormat("en-US", { style: "currency", currency: "USD", minimumFractionDigits: 2, maximumFractionDigits: 4 }).format(value)
}
