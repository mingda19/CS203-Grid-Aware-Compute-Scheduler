"use client"

import * as React from "react"
import Link from "next/link"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { RefreshCw } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Skeleton } from "@/components/ui/skeleton"
import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { usePlanList, type ReviewTab } from "@/hooks/use-plan-list"
import { formatMarketDateTime, formatRelativeAgo } from "@/lib/date-utils"
import { money } from "@/lib/format"
import { jobNamesLabel } from "@/lib/schedule-review-utils"
import type { SchedulePlanSummary } from "@/lib/api"

const doneNotices: Record<string, string> = { approve: "Plan approved.", revise: "Revision requested.", reject: "Plan rejected.", revoke: "Approval revoked." }

function rowAction(plan: SchedulePlanSummary) {
  if (plan.reviewState !== "PENDING") return "View →"
  if (plan.canApprove) return "Review →"
  return "View →"
}

function PlanRow({ plan }: { plan: SchedulePlanSummary }) {
  const savings = plan.projectedSavingsUsd == null ? null
    : plan.projectedSavingsUsd >= 0 ? `Saves ${money(plan.projectedSavingsUsd)}` : `Costs ${money(-plan.projectedSavingsUsd)} more`
  const who = plan.mine ? "You" : plan.ownerName
  const starts = plan.firstStart ? `, starting ${formatMarketDateTime(plan.firstStart, false)}` : ""
  return <li>
    <Link href={`/approvals/${encodeURIComponent(plan.runId)}`} className="flex items-center justify-between gap-4 px-3 py-3 hover:bg-muted/50">
      <span className="min-w-0 space-y-0.5">
        <span className="block truncate font-medium">{jobNamesLabel(plan.jobNames)}</span>
        <span className="block truncate text-xs text-muted-foreground" title={formatMarketDateTime(plan.createdAt)}>{who} submitted {formatRelativeAgo(plan.createdAt)}{starts}</span>
      </span>
      <span className="shrink-0 space-y-0.5 text-right">
        <span className="flex items-center justify-end gap-1.5 text-sm font-medium">{savings}{plan.usedSeededFallbackPrices && <EstimatedPriceBadge compact />}</span>
        <span className={`block text-xs ${plan.reviewState === "PENDING" && plan.canApprove ? "font-medium text-primary" : "text-muted-foreground"}`}>{rowAction(plan)}</span>
      </span>
    </Link>
  </li>
}

export function PlanList() {
  const router = useRouter()
  const pathname = usePathname()
  const params = useSearchParams()
  const tab: ReviewTab = params.get("tab") === "APPROVED" ? "APPROVED" : "PENDING"
  const page = Math.max(0, Number.parseInt(params.get("page") ?? "0", 10) || 0)
  const list = usePlanList(tab, page)
  // Read once when the page opens after a decision; the address is then cleaned so a reload does not repeat it.
  const [notice, setNotice] = React.useState<string | null>(() => doneNotices[params.get("done") ?? ""] ?? null)
  React.useEffect(() => {
    if (!params.get("done")) return
    const next = new URLSearchParams(params)
    next.delete("done")
    router.replace(next.size ? `${pathname}?${next}` : pathname)
  }, [params, pathname, router])

  const go = (nextTab: ReviewTab, nextPage: number) => {
    setNotice(null)
    router.replace(`${pathname}?tab=${nextTab}${nextPage ? `&page=${nextPage}` : ""}`)
  }

  return <div className="space-y-4">
    <div className="flex items-center justify-between gap-3">
      <h1 className="text-lg font-semibold">Approvals</h1>
      <Button type="button" variant="outline" size="icon" aria-label="Refresh" disabled={list.loading} onClick={list.reload}><RefreshCw className="h-4 w-4" /></Button>
    </div>
    <div role="tablist" aria-label="Plans to show" className="flex w-fit rounded-md border">
      {(["PENDING", "APPROVED"] as ReviewTab[]).map(value => (
        <Button key={value} type="button" role="tab" size="sm" aria-selected={tab === value} variant={tab === value ? "secondary" : "ghost"} onClick={() => go(value, 0)}>
          {value === "PENDING" ? `Pending${tab === "PENDING" && !list.loading && !list.error ? ` (${list.total})` : ""}` : "Approved"}
        </Button>
      ))}
    </div>
    {notice && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">{notice}</p>}
    {list.loading && <div className="space-y-2" role="status" aria-label="Loading plans"><Skeleton className="h-14 w-full" /><Skeleton className="h-14 w-full" /><Skeleton className="h-14 w-full" /></div>}
    {list.error && <div className="flex items-center gap-3"><p role="alert" className="text-sm text-destructive">{list.error}</p><Button variant="outline" size="sm" onClick={list.reload}>Retry</Button></div>}
    {!list.loading && !list.error && !list.plans.length && <p className="text-sm text-muted-foreground">
      {tab === "PENDING" ? <>Nothing is waiting for approval. <Link className="underline" href="/jobs">Add a job</Link></> : "No approved plans yet."}</p>}
    {!!list.plans.length && <ul aria-label={tab === "PENDING" ? "Pending plans" : "Approved plans"} className="divide-y rounded-md border">
      {list.plans.map(plan => <PlanRow key={plan.runId} plan={plan} />)}
    </ul>}
    {list.totalPages > 1 && <div className="flex items-center gap-3 text-xs">
      <Button type="button" size="sm" variant="outline" disabled={page === 0} onClick={() => go(tab, page - 1)}>Previous</Button>
      <span>Page {page + 1} of {list.totalPages}</span>
      <Button type="button" size="sm" variant="outline" disabled={page + 1 >= list.totalPages} onClick={() => go(tab, page + 1)}>Next</Button>
    </div>}
  </div>
}
