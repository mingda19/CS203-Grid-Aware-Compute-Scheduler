"use client"

import * as React from "react"
import { scheduleReviewApi, type SchedulePlanSummary } from "@/lib/api"

export type ReviewTab = "PENDING" | "APPROVED"

/** One page of the review queue. Results and errors carry the key they were fetched for, so a changed tab or page never shows stale rows. */
export function usePlanList(tab: ReviewTab, page: number) {
  const [reload, setReload] = React.useState(0)
  const [result, setResult] = React.useState<{ key: string; plans: SchedulePlanSummary[]; totalPages: number; total: number } | null>(null)
  const [failed, setFailed] = React.useState<{ key: string; message: string } | null>(null)
  const key = `${tab}:${page}:${reload}`

  React.useEffect(() => {
    let active = true
    scheduleReviewApi.list(page, tab).then(response => {
      if (!response.success || !response.data) throw new Error(response.message || "Could not load plans")
      if (active) setResult({ key, plans: response.data.content, totalPages: response.data.totalPages, total: response.data.totalElements })
    }).catch(error => { if (active) setFailed({ key, message: error instanceof Error ? error.message : "Could not load plans" }) })
    return () => { active = false }
  }, [tab, page, key])

  const current = result?.key === key ? result : null
  const error = failed?.key === key ? failed.message : null
  return { plans: current?.plans ?? [], totalPages: current?.totalPages ?? 0, total: current?.total ?? 0,
    loading: !current && !error, error, reload: () => setReload(value => value + 1) }
}
