"use client"

import * as React from "react"
import { jobsApi, type ConstraintDisagreement, type ConstraintDraft, type ConstraintParseResponse, type ConstraintSuggestion } from "@/lib/api"
import { applyDisagreementAnswers, constraintReviewBlocker, isPairSuggestion, type DisagreementAnswers, type SuggestionAnswers } from "@/lib/constraint-review"
import { CONSTRAINT_LABELS } from "@/lib/constraint-confirmation"
import { formatMarketDateTime, MARKET_TIMEZONE, MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"

type Review = { result: ConstraintParseResponse; draft: ConstraintDraft; raw: string }

export function ConstraintParseReview({ draft, confirmedSource, editingJobId, onConfirm, onPendingChange }: {
  draft: ConstraintDraft
  confirmedSource: string
  editingJobId?: number
  onConfirm: (result: ConstraintParseResponse, raw: string) => void
  onPendingChange: (pending: boolean) => void
}) {
  const [raw, setRaw] = React.useState(confirmedSource)
  const [review, setReview] = React.useState<Review | null>(null)
  const [loading, setLoading] = React.useState(false)
  const [error, setError] = React.useState("")
  const [confirmed, setConfirmed] = React.useState(false)
  const [answers, setAnswers] = React.useState<DisagreementAnswers>({})
  const [suggestionAnswers, setSuggestionAnswers] = React.useState<SuggestionAnswers>({})
  const requestId = React.useRef(0)
  React.useEffect(() => () => { requestId.current++ }, [])

  const invalidate = () => {
    requestId.current++
    setReview(null)
    setLoading(false)
    setError("")
    setConfirmed(false)
    // An answer belongs to the question it was given for. Keeping it across a re-parse would let a
    // stale choice satisfy the blocker for a question the operator has not seen.
    setAnswers({})
    setSuggestionAnswers({})
  }

  const parse = async () => {
    const id = ++requestId.current
    setLoading(true)
    setReview(null)
    setError("")
    setConfirmed(false)
    setAnswers({})
    setSuggestionAnswers({})
    onPendingChange(true)
    try {
      const response = await jobsApi.parseConstraints({ rawConstraints: raw, draft, timeZone: MARKET_TIMEZONE, editingJobId })
      if (id !== requestId.current) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not parse constraints")
      setReview({ result: response.data, draft, raw })
    } catch (e) {
      if (id === requestId.current) setError(e instanceof Error ? e.message : "Could not parse constraints. Try again.")
    } finally {
      if (id === requestId.current) setLoading(false)
    }
  }

  const blocker = review ? constraintReviewBlocker(review.result, review.draft, draft, answers, suggestionAnswers) : null
  return (
    <section aria-label="Natural-language constraints" className="mt-4 space-y-4 rounded-lg border bg-muted/20 p-4">
      <div>
        <label htmlFor="raw-job-constraints" className="text-sm font-medium">Describe scheduling constraints</label>
        <p id="constraints-help" className="text-xs text-muted-foreground">Optional. Describe timing, flexibility, priority, exemption, or an ordering against another job. Times are interpreted in ERCOT market local time (Central). Parsing does not save or change the job.</p>
      </div>
      <textarea id="raw-job-constraints" aria-describedby="constraints-help" maxLength={1000} rows={3}
        className="w-full rounded-md border bg-background px-4 py-2 text-sm"
        placeholder="e.g. can wait 4 hours, high priority"
        value={raw} onChange={e => { invalidate(); setRaw(e.target.value); onPendingChange(e.target.value !== confirmedSource) }} />
      <div className="flex flex-wrap items-center gap-2">
        <Button type="button" variant="outline" disabled={loading || !raw.trim()} onClick={() => void parse()}>{loading ? "Parsing…" : "Parse and review"}</Button>
        <span className="text-xs text-muted-foreground">{raw.length}/1,000 characters</span>
      </div>
      {loading && <p role="status" className="text-sm">Parsing constraints for review…</p>}
      {error && <p role="alert" className="text-sm text-destructive-foreground">{error} Use “Parse and review” to retry.</p>}
      {review && <>
        {(review.result.warnings ?? []).map((warning, i) => <p key={i} role="status" className="text-sm text-warning-foreground">{warning}</p>)}
        <ConstraintComparison raw={review.raw} result={review.result} />
        <DisagreementChoices disagreements={review.result.disagreements ?? []} answers={answers}
          onAnswer={(field, option) => setAnswers(prev => ({ ...prev, [field]: option }))} />
        <Suggestions suggestions={review.result.suggestions ?? []} answers={suggestionAnswers}
          onAnswer={(index, answer) => setSuggestionAnswers(prev => ({ ...prev, [index]: answer }))} />
        {blocker && <p role="alert" className="text-sm text-destructive-foreground">{blocker}</p>}
        <Button type="button" disabled={!!blocker} onClick={() => {
          if (constraintReviewBlocker(review.result, review.draft, draft, answers, suggestionAnswers)) return
          const disagreements = review.result.disagreements ?? []
          onConfirm({ ...review.result,
            suggestions: (review.result.suggestions ?? []).filter((_, index) => suggestionAnswers[index] !== "DROP"),
            fields: applyDisagreementAnswers(review.result.fields, disagreements, answers, review.draft) },
            review.raw)
          onPendingChange(false)
          setReview(null)
          setConfirmed(true)
        }}>Confirm and apply proposed values</Button>
        <p className="text-xs text-muted-foreground">Confirm applies these values to the form. Use “Create job” or “Save changes” below to save the job.</p>
      </>}
      {confirmed && <p role="status" className="text-sm text-success-foreground">Constraints confirmed and applied. Review the form below, then save the job.</p>}
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
  return <div className="space-y-4">
    <div className="grid gap-4 md:grid-cols-2">
      <div className="min-w-0 rounded-md border bg-background p-4">
        <h4 className="mb-2 text-sm font-semibold">Original text</h4>
        <p className="whitespace-pre-wrap break-words text-sm">{raw}</p>
      </div>
      <div className="min-w-0 rounded-md border bg-background p-4">
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

/**
 * The questions where the classifier and the parser read the text differently.
 *
 * Radio buttons, not a "dismiss" affordance: both readings are plausible and the operator has to
 * pick one, so there is no safe default to pre-select. Nothing is chosen until they choose it, and
 * `constraintReviewBlocker` keeps the confirm button disabled until every question has an answer -
 * silence is not consent about what their own text meant.
 */
export function DisagreementChoices({ disagreements, answers, onAnswer }: {
  disagreements: ConstraintDisagreement[]
  answers: DisagreementAnswers
  onAnswer: (field: keyof ConstraintDraft, optionId: string) => void
}) {
  if (!disagreements.length) return null
  return (
    <div className="space-y-4 rounded-md border border-warning/40 bg-warning/10 p-4">
      <div>
        <p className="text-sm font-medium">What does your text mean?</p>
        <p className="text-xs text-muted-foreground">
          Your wording was read two ways. Pick the one you meant — nothing is applied until you do.
        </p>
      </div>
      {disagreements.map(disagreement => (
        <fieldset key={disagreement.field} className="space-y-2">
          <legend className="text-sm">{disagreement.question}</legend>
          {disagreement.options.map(option => (
            <label key={option.id} className="flex items-start gap-2 text-sm">
              <input type="radio" className="mt-1" name={`disagreement-${disagreement.field}`}
                value={option.id} checked={answers[disagreement.field] === option.id}
                onChange={() => onAnswer(disagreement.field, option.id)} />
              <span>{option.label}</span>
            </label>
          ))}
        </fieldset>
      ))}
    </div>
  )
}

/**
 * Constraints the classifier found that the form has no field for.
 *
 * Pair relationships can be saved. Other recognised kinds remain informational.
 */
export function Suggestions({ suggestions, answers = {}, onAnswer }: {
  suggestions: ConstraintSuggestion[]
  answers?: SuggestionAnswers
  onAnswer?: (index: number, answer: "KEEP" | "DROP") => void
}) {
  if (!suggestions.length) return null
  return (
    <div className="text-sm">
      <p className="font-medium">Recognised constraints</p>
      <ul className="list-disc space-y-1 pl-4">
        {suggestions.map((suggestion, index) => (
          <li key={index}>
            {CONSTRAINT_LABELS[suggestion.type] ?? suggestion.type}
            {isPairSuggestion(suggestion) && <span> (job #{suggestion.referencedJobId})</span>}
            <span className="text-muted-foreground"> — from “{suggestion.phrase}”</span>
            {isPairSuggestion(suggestion) ? <fieldset className="mt-1 flex gap-3">
              <legend className="sr-only">Keep this job relationship?</legend>
              {(["KEEP", "DROP"] as const).map(answer => <label key={answer} className="flex items-center gap-1">
                <input type="radio" name={`suggestion-${index}`} value={answer}
                  checked={(answers[index] ?? (suggestion.needsConfirmation ? undefined : "KEEP")) === answer}
                  onChange={() => onAnswer?.(index, answer)} />
                {answer === "KEEP" ? "Keep relationship" : "Ignore relationship"}
              </label>)}
            </fieldset> : <p className="text-xs text-muted-foreground">Recognised only; this type cannot be saved yet.</p>}
          </li>
        ))}
      </ul>
      <p className="mt-1 text-xs text-muted-foreground">
        Kept job relationships are applied to the form when you confirm, and saved with the job when you save it.
      </p>
    </div>
  )
}
