"use client"

import * as React from "react"
import Link from "next/link"
import { usePathname, useRouter, useSearchParams } from "next/navigation"
import { CircleCheck, Clock, RefreshCw } from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Button } from "@/components/ui/button"
import { Card } from "@/components/ui/card"
import { Segmented } from "@/components/ui/segmented"
import { Skeleton } from "@/components/ui/skeleton"
import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { StatusBadge, type StatusInfo } from "@/components/ui/status-badge"
import { usePlanList, type ReviewTab } from "@/hooks/use-plan-list"
import { formatMarketDateTime, formatRelativeAgo, formatShortDateTime } from "@/lib/date-utils"
import { planStatus } from "@/lib/job-status"
import { money } from "@/lib/format"
import { jobNamesLabel } from "@/lib/schedule-review-utils"
import type { SchedulePlanSummary } from "@/lib/api"

const doneNotices: Record<string, string> = { approve: "Plan approved.", revise: "Revision requested.", reject: "Plan rejected.", revoke: "Approval revoked." }

/** A pending plan you can decide on asks for you. Every other plan shows its state. */
function rowStatus(plan: SchedulePlanSummary): StatusInfo {
  if (plan.reviewState === "PENDING" && plan.canApprove) return { label: "Needs your review", icon: Clock, tone: "primary" }
  return planStatus(plan.reviewState)
}

function PlanRow({ plan }: { plan: SchedulePlanSummary }) {
  const savings = plan.projectedSavingsUsd == null ? null
    : plan.projectedSavingsUsd >= 0 ? `Saves ${money(plan.projectedSavingsUsd)}` : `Costs ${money(-plan.projectedSavingsUsd)} more`
  const who = plan.mine ? "You" : plan.ownerName
  const detail = [`${who} submitted ${formatRelativeAgo(plan.createdAt)}`, plan.firstStart && `Starts ${formatShortDateTime(plan.firstStart)}`].filter(Boolean).join(" · ")
  return <li>
    <Link href={`/approvals/${encodeURIComponent(plan.runId)}`} className="flex items-center justify-between gap-4 px-4 py-3 hover:bg-muted/50 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring">
      <span className="min-w-0 space-y-0.5">
        <span className="block truncate text-sm font-medium">{jobNamesLabel(plan.jobNames)}</span>
        <span className="block truncate text-xs text-muted-foreground" title={formatMarketDateTime(plan.createdAt)}>{detail}</span>
      </span>
      <span className="flex shrink-0 flex-col items-end gap-1">
        {savings && <span className="flex items-center gap-1.5 text-sm font-medium tabular-nums">{savings}{plan.usedSeededFallbackPrices && <EstimatedPriceBadge compact />}</span>}
        <StatusBadge {...rowStatus(plan)} />
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

  return <div data-tour="plan-list" className="space-y-6">
    {/* No visible title: the sidebar already says where you are. The heading stays for screen readers. */}
    <h1 className="sr-only">Approvals</h1>
    <div className="flex items-center justify-between gap-3">
      <Segmented<ReviewTab>
        label="Plans to show"
        value={tab}
        onChange={value => go(value, 0)}
        options={[
          { value: "PENDING", label: `Pending${tab === "PENDING" && !list.loading && !list.error ? ` (${list.total})` : ""}` },
          { value: "APPROVED", label: "Approved" },
        ]}
      />
      <Button type="button" variant="ghost" size="icon" className="size-9" aria-label="Refresh" title="Refresh" disabled={list.loading} onClick={list.reload}>
        <RefreshCw className={`size-4 ${list.loading ? "animate-spin" : ""}`} />
      </Button>
    </div>
    {notice && <Alert variant="success" role="status"><CircleCheck className="size-4" aria-hidden="true" /><AlertDescription>{notice}</AlertDescription></Alert>}
    {list.error && <Alert variant="destructive" className="flex items-center justify-between gap-3">
      <AlertDescription className="min-w-0 break-words">{list.error}</AlertDescription>
      <Button variant="outline" size="sm" className="shrink-0" onClick={list.reload}>Retry</Button>
    </Alert>}
    {list.loading && <Card className="space-y-2 p-4 sm:p-5" role="status" aria-label="Loading plans"><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /><Skeleton className="h-10 w-full" /></Card>}
    {!list.loading && !list.error && !list.plans.length && <Card className="flex flex-col items-start gap-3 p-4 sm:p-5">
      <p className="text-sm text-muted-foreground">{tab === "PENDING" ? "Nothing is waiting for approval." : "No approved plans yet."}</p>
      {tab === "PENDING" && <Button asChild size="sm"><Link href="/jobs?new=1">New job</Link></Button>}
    </Card>}
    {!!list.plans.length && <Card>
      <ul aria-label={tab === "PENDING" ? "Pending plans" : "Approved plans"} className="divide-y">
        {list.plans.map(plan => <PlanRow key={plan.runId} plan={plan} />)}
      </ul>
    </Card>}
    {list.totalPages > 1 && <div className="flex items-center gap-3 text-xs text-muted-foreground">
      <Button type="button" size="sm" variant="outline" disabled={page === 0} onClick={() => go(tab, page - 1)}>Previous</Button>
      <span>Page {page + 1} of {list.totalPages}</span>
      <Button type="button" size="sm" variant="outline" disabled={page + 1 >= list.totalPages} onClick={() => go(tab, page + 1)}>Next</Button>
    </div>}
  </div>
}
