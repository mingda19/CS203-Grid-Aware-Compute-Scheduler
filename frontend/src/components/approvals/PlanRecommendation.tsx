import { ChevronDown, TriangleAlert } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Card } from "@/components/ui/card"
import { Collapsible, CollapsibleContent, CollapsibleTrigger } from "@/components/ui/collapsible"
import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { ExplanationSummary } from "@/components/ui/explanation-summary"
import { StatusBadge } from "@/components/ui/status-badge"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { formatShortDateTime, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { planStatus } from "@/lib/job-status"
import { money } from "@/lib/format"
import { jobNamesLabel } from "@/lib/schedule-review-utils"
import type { SchedulePlanDetail } from "@/lib/api"

/** The job names, then who submitted the plan. Jobs deleted after approval are left out of the title. */
export function PlanHeader({ plan }: { plan: SchedulePlanDetail }) {
  const names = (plan.recommendation?.items ?? []).filter(item => !plan.removedJobIds.includes(item.jobId)).map(item => item.jobName)
  return <header className="space-y-1">
    <div className="flex flex-wrap items-start justify-between gap-2">
      <h1 className="min-w-0 break-words text-lg font-semibold leading-7">{plan.recommendation ? jobNamesLabel(names, 3) : "Plan"}</h1>
      <StatusBadge {...planStatus(plan.reviewState)} />
    </div>
    <p className="text-xs text-muted-foreground">Submitted by {plan.ownerName}</p>
  </header>
}

/** What the plan does: the savings, each job's new time, why. Nothing here changes the plan. */
export function PlanRecommendation({ plan }: { plan: SchedulePlanDetail }) {
  const rec = plan.recommendation
  return <section aria-label="Plan details" className="space-y-6">
    {rec && <>
      <div className="space-y-1">
        <p className="flex flex-wrap items-center gap-2 text-2xl font-semibold leading-8 tabular-nums sm:text-3xl sm:leading-9">
          {rec.projectedSavingsUsd >= 0 ? `Saves ${money(rec.projectedSavingsUsd)}` : `Costs ${money(-rec.projectedSavingsUsd)} more`}
          <span className="text-base font-normal text-muted-foreground">({rec.projectedSavingsPercent.toFixed(1)}%)</span>
          {rec.usedSeededFallbackPrices && <EstimatedPriceBadge />}
        </p>
        <p className="text-xs leading-4 text-muted-foreground">{money(rec.baselineCostUsd)} → {money(rec.optimizedCostUsd)} compared with running at the original times</p>
      </div>
      {rec.status === "NEEDS_ATTENTION" && <Alert variant="warning" role="status">
        <TriangleAlert className="size-4" aria-hidden="true" /><AlertDescription>Some jobs could not be placed.</AlertDescription>
      </Alert>}
      <Card>
        <Table>
          <caption className="sr-only">Each job in this plan, when it runs and what it saves</caption>
          <TableHeader>
            <TableRow className="hover:bg-transparent">
              <TableHead className="h-10 px-4 text-xs font-medium">Job</TableHead>
              <TableHead className="h-10 px-4 text-xs font-medium">Runs ({MARKET_ZONE_LABEL})</TableHead>
              <TableHead className="h-10 px-4 text-xs font-medium">Was ({MARKET_ZONE_LABEL})</TableHead>
              <TableHead className="h-10 px-4 text-right text-xs font-medium">Saves</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>{rec.items.map(item => {
            const removed = plan.removedJobIds.includes(item.jobId)
            return <TableRow key={item.jobId} className="align-top">
              <TableCell className="px-4 py-3 font-medium">{item.jobName}
                {removed && <p className="text-xs font-normal text-destructive-foreground">Deleted</p>}</TableCell>
              {item.perJobStatus === "NO_FEASIBLE_SLOT" || !item.scheduledStart || !item.scheduledEnd
                ? <TableCell colSpan={2} className="px-4 py-3 text-muted-foreground">Not placed{item.reason ? `: ${item.reason}` : ""}</TableCell>
                : <>
                  <TableCell className="px-4 py-3 tabular-nums">{formatShortDateTime(item.scheduledStart)} → {formatShortDateTime(item.scheduledEnd)}
                    {item.perJobStatus === "UNCHANGED_EXEMPT" && <p className="text-xs text-muted-foreground">Not moved (exempt)</p>}
                    {item.rescheduledFromElapsedStart && <p className="text-xs text-warning-foreground">Moved because its start time had passed</p>}</TableCell>
                  <TableCell className="px-4 py-3 tabular-nums text-muted-foreground">{formatShortDateTime(item.baselineStart)}</TableCell>
                </>}
              <TableCell className="px-4 py-3 text-right tabular-nums">{item.savingsUsd == null ? "—" : money(item.savingsUsd)}</TableCell>
            </TableRow>
          })}</TableBody>
        </Table>
        <Collapsible className="border-t">
          <CollapsibleTrigger className="group flex w-full items-center justify-between gap-3 px-4 py-3 text-left text-sm font-medium focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring">
            Why these times
            <ChevronDown className="size-4 shrink-0 text-muted-foreground transition-transform group-data-[state=open]:rotate-180" aria-hidden="true" />
          </CollapsibleTrigger>
          {/* Mounted while closed so the text stays in the page; the closed state hides it. */}
          <CollapsibleContent forceMount className="px-4 pb-4 data-[state=closed]:hidden">
            <ExplanationSummary text={rec.plainEnglishExplanation} />
          </CollapsibleContent>
        </Collapsible>
      </Card>
    </>}
    <p className="break-all text-xs text-muted-foreground">Run ID {plan.runId}</p>
  </section>
}
