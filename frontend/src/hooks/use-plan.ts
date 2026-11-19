"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import { ApiRequestError, scheduleReviewApi, type ScheduleDecisionAction, type SchedulePlanDetail } from "@/lib/api"
import { decisionUnavailable, rationaleError } from "@/lib/schedule-review-utils"

/** Loads one plan (this read also checks the plan is still valid) and records decisions on it. */
export function usePlan(runId: string) {
  const router = useRouter()
  const [reload, setReload] = React.useState(0)
  const [loaded, setLoaded] = React.useState<{ key: string; plan: SchedulePlanDetail } | null>(null)
  const [failed, setFailed] = React.useState<{ key: string; message: string; status?: number } | null>(null)
  const [decisionError, setDecisionError] = React.useState<string | null>(null)
  const [conflict, setConflict] = React.useState(false)
  const [submitting, setSubmitting] = React.useState(false)
  const busy = React.useRef(false)
  const mounted = React.useRef(true)
  const key = `${runId}:${reload}`

  React.useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  React.useEffect(() => {
    let active = true
    scheduleReviewApi.detail(runId).then(response => {
      if (!response.success || !response.data) throw new Error(response.message || "Could not load this plan")
      if (active) setLoaded({ key, plan: response.data })
    }).catch(error => {
      if (active) setFailed({ key, message: error instanceof Error ? error.message : "Could not load this plan",
        status: error instanceof ApiRequestError ? error.status : undefined })
    })
    return () => { active = false }
  }, [runId, key])

  const plan = loaded?.key === key ? loaded.plan : null
  const error = failed?.key === key ? failed : null

  /** "ok": recorded and the page moved on. "conflict": the plan changed, nothing recorded, plan reloaded. "error": see decisionError. */
  const decide = async (action: ScheduleDecisionAction, comment: string): Promise<"ok" | "conflict" | "error"> => {
    if (busy.current || !plan) return "error"
    const invalid = rationaleError(comment) || decisionUnavailable(plan, action)
    if (invalid) { setDecisionError(invalid); return "error" }
    busy.current = true
    setSubmitting(true); setDecisionError(null)
    try {
      const response = await scheduleReviewApi.decide(plan.runId, action, comment)
      if (!response.success || !response.data) throw new Error(response.message || "Decision was not recorded")
      // The plan left Pending (or Approved, for a revoke), so the list is the next stop.
      router.push(`/approvals?tab=${action === "revoke" ? "APPROVED" : "PENDING"}&done=${action}`)
      return "ok"
    } catch (caught) {
      if (caught instanceof ApiRequestError && caught.status === 409) {
        if (mounted.current) { setConflict(true); setReload(value => value + 1) }
        return "conflict"
      }
      if (mounted.current) setDecisionError(caught instanceof Error ? caught.message : "Could not record the decision. Try again.")
      return "error"
    } finally {
      busy.current = false
      if (mounted.current) setSubmitting(false)
    }
  }

  return { plan, loading: !plan && !error, error, decisionError, conflict, submitting, decide,
    retry: () => setReload(value => value + 1) }
}
