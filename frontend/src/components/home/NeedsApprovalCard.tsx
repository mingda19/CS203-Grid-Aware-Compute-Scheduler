"use client"

import * as React from "react"
import Link from "next/link"
import { Button } from "@/components/ui/button"
import { Card, CardContent } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { EstimatedPriceBadge } from "@/components/ui/estimated-price-badge"
import { scheduleReviewApi, type SchedulePlanDetail } from "@/lib/api"
import { money } from "@/lib/format"
import { unwrap, useLoad } from "./use-load"

interface Pending {
  count: number
  /** The newest pending plan in the list. */
  runId: string | null
  /** Its detail. Null if the detail could not be read: the plan still counts, with no savings line. */
  newest: SchedulePlanDetail | null
}

/** The pending count comes from stored review states. Reading a plan's detail runs its validity check. */
async function loadPending(): Promise<Pending> {
  const read = async (): Promise<Pending> => {
    const page = await unwrap(scheduleReviewApi.list(0, "PENDING", true), "Could not load plans")
    const runId = page.content[0]?.runId ?? null
    const newest = runId
      ? await unwrap(scheduleReviewApi.detail(runId), "Could not load plan").catch(() => null)
      : null
    return { count: page.totalElements, runId, newest }
  }
  const first = await read()
  // The check can change the newest plan's state. Then the list is out of date, so read it once more.
  return first.newest && first.newest.reviewState !== "PENDING" ? read() : first
}

export function NeedsApprovalCard() {
  const pending = useLoad("pending", loadPending)
  const { data } = pending
  const rec = data?.newest?.recommendation

  return (
    <Card className="min-w-0">
      <CardContent className="flex min-w-0 flex-col gap-3 p-4 pt-4 sm:flex-row sm:items-center sm:justify-between sm:gap-4 sm:p-5 sm:pt-5">
        <div className="min-w-0 space-y-1">
          <p className="text-xs leading-4 text-muted-foreground">Needs approval</p>
          {pending.loading ? (
            <div className="space-y-2" role="status" aria-label="Loading">
              <Skeleton className="h-8 w-56 max-w-full" />
              <Skeleton className="h-5 w-48 max-w-full" />
            </div>
          ) : pending.error ? (
            <div className="flex min-w-0 items-center gap-3">
              <span className="min-w-0 truncate text-sm leading-5 text-muted-foreground">Could not load plans</span>
              <Button variant="outline" size="sm" className="h-7 shrink-0 px-2 text-xs" onClick={pending.reload}>Retry</Button>
            </div>
          ) : !data || data.count === 0 ? (
            <p className="text-sm leading-5 text-muted-foreground">No plans waiting.</p>
          ) : (
            <>
              <h2 className="truncate text-2xl font-semibold leading-8">
                {data.count === 1 ? "1 plan needs approval" : `${data.count} plans need approval`}
              </h2>
              {rec && (
                <div className="flex min-w-0 items-center gap-2 text-sm leading-5">
                  <span className="min-w-0 truncate">
                    {data.count > 1 ? "Newest plan: " : ""}
                    {rec.projectedSavingsUsd >= 0 ? `${data.count > 1 ? "saves" : "Saves"} ${money(rec.projectedSavingsUsd)}` : `${data.count > 1 ? "costs" : "Costs"} ${money(-rec.projectedSavingsUsd)} more`}
                    {" "}({rec.projectedSavingsPercent.toFixed(1)}%)
                  </span>
                  {rec.usedSeededFallbackPrices && <span className="shrink-0"><EstimatedPriceBadge /></span>}
                </div>
              )}
            </>
          )}
        </div>
        {data && data.count > 0 && data.runId && (
          <Button asChild className="shrink-0 self-start sm:self-auto">
            <Link href={`/approvals/${encodeURIComponent(data.runId)}`}>Review</Link>
          </Button>
        )}
      </CardContent>
    </Card>
  )
}
