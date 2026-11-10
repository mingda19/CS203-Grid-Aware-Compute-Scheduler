"use client"

import * as React from "react"
import { ApiRequestError, scheduleReviewApi, type ScheduleDecisionAction, type SchedulePlanDetail, type SchedulePlanSummary } from "@/lib/api"
import { decisionUnavailable, rationaleError, selectReviewRun } from "@/lib/schedule-review-utils"

export type ReviewTab = "PENDING" | "APPROVED"

export function useScheduleReview(onDecision?: () => void) {
  const [tab, setTab] = React.useState<ReviewTab>("PENDING")
  const [page, setPage] = React.useState(0)
  const [reload, setReload] = React.useState(0)
  const [list, setList] = React.useState<{ plans: SchedulePlanSummary[]; totalPages: number; key: string } | null>(null)
  const [listError, setListError] = React.useState<string | null>(null)
  const [selected, setSelected] = React.useState("")
  const [detail, setDetail] = React.useState<{ plan: SchedulePlanDetail; key: string } | null>(null)
  const [detailError, setDetailError] = React.useState<string | null>(null)
  const [decisionError, setDecisionError] = React.useState<string | null>(null)
  const [conflict, setConflict] = React.useState(false)
  const [notice, setNotice] = React.useState<string | null>(null)
  const [submitting, setSubmitting] = React.useState(false)
  const busy = React.useRef(false)
  const mounted = React.useRef(true)
  const listKey = `${tab}:${page}:${reload}`
  const detailKey = `${selected}:${reload}`
  const listLoading = list?.key !== listKey && !listError
  const detailLoading = !!selected && detail?.key !== detailKey && !detailError
  const plan = detail?.key === detailKey ? detail.plan : null

  React.useEffect(() => { mounted.current = true; return () => { mounted.current = false } }, [])
  React.useEffect(() => {
    let active = true
    scheduleReviewApi.list(page, tab).then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load schedule plans")
      const data = response.data
      setList({ plans: data.content, totalPages: data.totalPages, key: listKey })
      const requested = page === 0 && typeof window !== "undefined" ? new URLSearchParams(window.location.search).get("runId") : null
      setSelected(current => selectReviewRun(current, data.content.map(item => item.runId), requested))
    }).catch(error => { if (active) setListError(error instanceof Error ? error.message : "Could not load schedule plans") })
    return () => { active = false }
  }, [tab, page, reload, listKey])

  React.useEffect(() => {
    if (!selected) return
    let active = true
    scheduleReviewApi.detail(selected).then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load schedule details")
      setDetail({ plan: response.data, key: detailKey })
    }).catch(error => { if (active) setDetailError(error instanceof Error ? error.message : "Could not load schedule details") })
    return () => { active = false }
  }, [selected, reload, detailKey])

  const refresh = () => {
    if (busy.current) return
    setListError(null); setDetailError(null); setDecisionError(null); setNotice(null); setConflict(false)
    setReload(value => value + 1)
  }
  const select = (runId: string) => {
    if (busy.current) return
    setSelected(runId); setDetailError(null); setDecisionError(null); setNotice(null); setConflict(false)
  }
  const changeTab = (next: ReviewTab) => {
    if (busy.current || next === tab) return
    setTab(next); setPage(0); setSelected(""); setDetail(null); setListError(null); setDetailError(null); setDecisionError(null); setNotice(null); setConflict(false)
  }
  const changePage = (next: number) => {
    if (busy.current) return
    setPage(next); setSelected(""); setDetail(null); setListError(null); setDetailError(null); setDecisionError(null); setNotice(null); setConflict(false)
  }

  const decide = async (action: ScheduleDecisionAction, comment: string): Promise<boolean> => {
    if (busy.current || conflict || listLoading || detailLoading || listError || detailError) return false
    const invalid = rationaleError(comment) || decisionUnavailable(plan, action)
    if (invalid || !plan) { setDecisionError(invalid); return false }
    busy.current = true
    setSubmitting(true); setDecisionError(null); setNotice(null)
    try {
      const response = await scheduleReviewApi.decide(plan.runId, action, comment)
      if (!mounted.current) return false
      if (!response.success || !response.data) throw new Error(response.message || "Decision was not recorded")
      const updated = response.data
      setDetail({ key: detailKey, plan: { ...plan, reviewState: updated.reviewState } })
      setList(current => current && ({ ...current, plans: current.plans.map(item => item.runId === updated.runId ? { ...item, reviewState: updated.reviewState } : item) }))
      setNotice(`Decision recorded: ${updated.reviewState.replaceAll("_", " ").toLowerCase()}.`)
      onDecision?.()
      return true
    } catch (error) {
      if (!mounted.current) return false
      setDecisionError(error instanceof ApiRequestError && error.status === 409 ? `409 conflict: ${error.message}` : error instanceof Error ? error.message : "Could not record decision. Try again.")
      if (error instanceof ApiRequestError && error.status === 409) {
        setConflict(true)
        setReload(value => value + 1) // the plan was replaced: reload the queue so the current one shows
      }
      return false
    } finally {
      busy.current = false
      if (mounted.current) setSubmitting(false)
    }
  }

  return { tab, plans: list?.key === listKey ? list.plans : [], totalPages: list?.totalPages ?? 0, page,
    selected, plan, listLoading, detailLoading, listError, detailError, decisionError, conflict, notice,
    submitting, refresh, select, changeTab, changePage, decide }
}
