"use client"

import * as React from "react"
import { jobsApi, type ConstraintDraft, type ConstraintParseResponse } from "@/lib/api"
import { constraintReviewBlocker } from "@/lib/constraint-review"
import { formatMarketDateTime, MARKET_TIMEZONE, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"

type Review = { result: ConstraintParseResponse; draft: ConstraintDraft; raw: string }

export function ConstraintParseReview({ draft, confirmedSource, onConfirm, onPendingChange }: {
  draft: ConstraintDraft
  confirmedSource: string
  onConfirm: (result: ConstraintParseResponse, raw: string) => void
  onPendingChange: (pending: boolean) => void
}) {
  const [raw, setRaw] = React.useState(confirmedSource)
  const [review, setReview] = React.useState<Review | null>(null)
  const [loading, setLoading] = React.useState(false)
  const [error, setError] = React.useState("")
  const [confirmed, setConfirmed] = React.useState(false)
  const requestId = React.useRef(0)
  React.useEffect(() => () => { requestId.current++ }, [])

  const invalidate = () => {
    requestId.current++
    setReview(null)
    setLoading(false)
    setError("")
    setConfirmed(false)
  }

  const parse = async () => {
    const id = ++requestId.current
    setLoading(true)
    setReview(null)
    setError("")
    setConfirmed(false)
    onPendingChange(true)
    try {
      const response = await jobsApi.parseConstraints({ rawConstraints: raw, draft, timeZone: MARKET_TIMEZONE })
      if (id !== requestId.current) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not parse constraints")
      setReview({ result: response.data, draft, raw })
    } catch (e) {
      if (id === requestId.current) setError(e instanceof Error ? e.message : "Could not parse constraints. Try again.")
    } finally {
      if (id === requestId.current) setLoading(false)
    }
  }

  const blocker = review ? constraintReviewBlocker(review.result, review.draft, draft) : null
  return (
    <section aria-label="Natural-language constraints" className="mt-4 space-y-3 rounded-lg border bg-muted/20 p-4">
      <div>
        <label htmlFor="raw-job-constraints" className="text-sm font-medium">Describe scheduling constraints</label>
        <p id="constraints-help" className="text-xs text-muted-foreground">Optional. Describe timing, flexibility, priority, or exemption. Times are interpreted in ERCOT market local time (Central). Parsing does not save or change the job.</p>
      </div>
      <textarea id="raw-job-constraints" aria-describedby="constraints-help" maxLength={1000} rows={3}
        className="w-full rounded-md border bg-background p-3 text-sm"
        placeholder="e.g. can wait 4 hours, high priority"
        value={raw} onChange={e => { invalidate(); setRaw(e.target.value); onPendingChange(e.target.value !== confirmedSource) }} />
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" disabled={loading || !raw.trim()} onClick={() => void parse()}>{loading ? "Parsing…" : "Parse and review"}</Button>
        <Button type="button" variant="ghost" onClick={() => { invalidate(); setRaw(confirmedSource); onPendingChange(false) }}>Discard text changes</Button>
        <span className="text-xs text-muted-foreground">{raw.length}/1,000 characters</span>
      </div>
      {loading && <p role="status" className="text-sm">Parsing constraints for review…</p>}
      {error && <p role="alert" className="text-sm text-destructive">{error} Use “Parse and review” to retry.</p>}
      {review && <>
        <ConstraintComparison raw={review.raw} result={review.result} />
        {blocker && <p role="alert" className="text-sm text-destructive">{blocker}</p>}
        <Button type="button" disabled={!!blocker} onClick={() => {
          if (constraintReviewBlocker(review.result, review.draft, draft)) return
          onConfirm(review.result, review.raw)
          onPendingChange(false)
          setReview(null)
          setConfirmed(true)
        }}>Confirm and apply proposed values</Button>
        <p className="text-xs text-muted-foreground">Confirm applies these values to the form. Use “Create job” or “Save changes” below to save the job.</p>
      </>}
      {confirmed && <p role="status" className="text-sm text-emerald-700 dark:text-emerald-400">Constraints confirmed and applied. Review the form below, then save the job.</p>}
    </section>
  )
}

export function ConstraintComparison({ raw, result }: { raw: string; result: ConstraintParseResponse }) {
  const values: Array<[keyof ConstraintDraft, string, string]> = [
    ["originalStartTime", `Original start (${MARKET_ZONE_LABEL})`, result.fields.originalStartTime ? formatMarketDateTime(result.fields.originalStartTime) : "Not set"],
    ["deadline", `Deadline (${MARKET_ZONE_LABEL})`, result.fields.deadline ? formatMarketDateTime(result.fields.deadline) : "Not set"],
    ["flexibilityWindowHours", "Flexibility", result.fields.flexibilityWindowHours == null ? "Not set" : `${result.fields.flexibilityWindowHours} hours`],
    ["priority", "Priority", result.fields.priority ?? "Not set"],
    ["isExempt", "Exempt from rescheduling", result.fields.isExempt == null ? "Not set" : result.fields.isExempt ? "Yes" : "No"],
  ]
  return <div className="space-y-3">
    <div className="grid gap-4 md:grid-cols-2">
      <div className="min-w-0 rounded-md border bg-background p-3">
        <h4 className="mb-2 text-sm font-semibold">Original text</h4>
        <p className="whitespace-pre-wrap break-words text-sm">{raw}</p>
      </div>
      <div className="min-w-0 rounded-md border bg-background p-3">
        <h4 className="mb-2 text-sm font-semibold">Proposed values</h4>
        <dl className="space-y-2 text-sm">{values.map(([field, label, value]) => {
          const evidence = result.evidence.filter(item => item.field === field)
          return <div key={field}><dt className="text-xs text-muted-foreground">{label}</dt><dd className="break-words">{value}
            <span className="block text-xs text-muted-foreground">{evidence.length ? evidence.map(item => `From: “${item.phrase}”`).join("; ") : "Kept from current form"}</span>
          </dd></div>
        })}</dl>
      </div>
    </div>
    {!!result.conflicts.length && <div className="text-sm"><p className="font-medium">Values that differ</p><ul className="list-disc space-y-1 pl-4">{result.conflicts.map((item, index) => <li key={index}>{item.message}</li>)}</ul></div>}
    {!!result.ambiguities.length && <div className="text-sm"><p className="font-medium">Clarification needed</p><ul className="list-disc space-y-1 pl-4">{result.ambiguities.map((item, index) => <li key={index}>“{item.phrase}”: {item.message}</li>)}</ul></div>}
    {!!result.unsupported.length && <div className="text-sm"><p className="font-medium">Unsupported text</p><ul className="list-disc space-y-1 pl-4">{result.unsupported.map((item, index) => <li key={index}>{item.phrase}</li>)}</ul></div>}
  </div>
}
