"use client"

import * as React from "react"
import {
  CONSTRAINT_LABELS, outstanding, saveBlocker,
  type Decision, type ProposedConstraint,
} from "@/lib/constraint-confirmation"
import { formatMarketDateTime } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"

/**
 * Asks the operator about constraints the classifier was not sure enough about to apply on its own.
 *
 * <p>Shown while the job form is still open, because an unconfirmed guess must never be written to
 * the database: the job does not exist yet, so there is nothing to correct afterwards and no
 * record of a constraint nobody agreed to.
 *
 * <p>Each item asks one question in the operator's own words - their phrase, and what we think it
 * meant - rather than naming a constraint type they have never heard of. Where several of their
 * jobs could be meant, they pick, because job names are not unique and guessing would bind a hard
 * scheduling rule to an arbitrary one.
 */
export function ConstraintConfirmation({ proposals, decisions, onDecide }: {
  proposals: ProposedConstraint[]
  decisions: Record<string, Decision>
  onDecide: (key: string, decision: Decision) => void
}) {
  const pending = outstanding(proposals)
  if (pending.length === 0) return null

  const blocker = saveBlocker(proposals, decisions)
  const unanswered = pending.filter((p) => !decisions[p.key]).length

  return (
    <section
      aria-label="Confirm suggested constraints"
      className="space-y-3 rounded-lg border border-amber-500/40 bg-amber-500/5 p-4"
    >
      <div>
        <h4 className="text-sm font-semibold text-foreground">Confirm what we understood</h4>
        {/* Counts what is still open, not what was ever asked: once the operator has answered, the
            items stay listed so they can change their mind, but the prompt should stop nagging. */}
        <p className="text-xs text-muted-foreground">
          {unanswered === 0
            ? "All settled. You can save this job."
            : unanswered === 1
              ? "One part of your description could be read more than one way. Confirm it before saving."
              : `${unanswered} parts of your description could be read more than one way. Confirm them before saving.`}
        </p>
      </div>

      <ul className="space-y-3">
        {pending.map((proposal) => {
          const decision = decisions[proposal.key]
          const mustPickJob = (proposal.candidates?.length ?? 0) > 1
          return (
            <li key={proposal.key} className="rounded-md border bg-background p-3">
              <p className="text-xs text-muted-foreground">
                From your words: <q className="font-medium text-foreground">{proposal.phrase}</q>
              </p>
              <p className="mt-1 text-sm text-foreground">
                We read this as <strong>{CONSTRAINT_LABELS[proposal.type]}</strong>.
                <span className="ml-1 font-mono text-[11px] text-muted-foreground">
                  ({Math.round(proposal.probability * 100)}% sure)
                </span>
              </p>

              {decision?.accepted && mustPickJob && (
                <div className="mt-2">
                  <label className="text-xs font-medium text-foreground" htmlFor={`job-${proposal.key}`}>
                    Which job does it refer to?
                  </label>
                  <select
                    id={`job-${proposal.key}`}
                    className="mt-1 w-full rounded-md border bg-background px-2 py-1.5 text-sm"
                    value={decision.referencedJobId ?? ""}
                    onChange={(e) =>
                      onDecide(proposal.key, { accepted: true, referencedJobId: Number(e.target.value) })}
                  >
                    <option value="" disabled>Choose a job…</option>
                    {proposal.candidates?.map((candidate) => (
                      <option key={candidate.id} value={candidate.id}>
                        {/* Two jobs can share a name, so the start time is what tells them apart. */}
                        {candidate.name}
                        {candidate.startUtc ? ` — starts ${formatMarketDateTime(candidate.startUtc)}` : ""}
                      </option>
                    ))}
                  </select>
                </div>
              )}

              <div className="mt-2 flex gap-2">
                <Button
                  type="button"
                  size="sm"
                  variant={decision?.accepted ? "default" : "outline"}
                  onClick={() => onDecide(proposal.key, { accepted: true, referencedJobId: proposal.referencedJobId })}
                >
                  {decision?.accepted ? "Yes, that's right" : "Yes"}
                </Button>
                <Button
                  type="button"
                  size="sm"
                  variant={decision && !decision.accepted ? "destructive" : "outline"}
                  onClick={() => onDecide(proposal.key, { accepted: false })}
                >
                  {decision && !decision.accepted ? "Ignored" : "No, ignore it"}
                </Button>
              </div>
            </li>
          )
        })}
      </ul>

      {blocker && <p role="alert" className="text-xs font-medium text-amber-700 dark:text-amber-400">{blocker}</p>}
    </section>
  )
}
