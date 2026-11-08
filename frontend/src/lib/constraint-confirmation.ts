/**
 * What the operator still has to settle before a job can be saved.
 *
 * The classifier returns a probability per constraint. Code decides what to do with it: a high one
 * applies, a middling one is shown to the operator, a low one never reaches them. Nothing in that
 * band may be written to the database unsettled, so this runs while the form is still open - the
 * job does not exist yet, and an unconfirmed guess must never become part of one.
 */

/** Constraint kinds the classifier can report, matching the backend's ConstraintType. */
export type ConstraintTypeName =
  | "DEADLINE"
  | "FLEXIBILITY_WINDOW"
  | "ABSOLUTE_TIME_WINDOW"
  | "PRECEDENCE"
  | "DEPENDENCY"
  | "MUTUAL_EXCLUSION"
  | "SYNCHRONIZED_FINISH"

/**
 * How each kind reads to an operator, who should never be shown an enum name.
 *
 * PRECEDENCE and DEPENDENCY are the two directions of one ordering, and they are worded to be hard
 * to mix up. This is the one label pair where a misread is not a cosmetic problem: confirming the
 * wrong direction runs the operator's jobs in the opposite order to what they asked for, and the
 * resulting schedule looks perfectly reasonable, so nothing downstream will catch it. Hence "before"
 * and "after" carried in the same phrase shape rather than, say, "precedes" and "depends on".
 */
export const CONSTRAINT_LABELS: Record<ConstraintTypeName, string> = {
  DEADLINE: "a time this job must finish by",
  FLEXIBILITY_WINDOW: "how long this job may be delayed",
  ABSOLUTE_TIME_WINDOW: "a daily window this job may run in",
  PRECEDENCE: "this job must finish before another job starts",
  DEPENDENCY: "this job must wait until another job has finished",
  MUTUAL_EXCLUSION: "this job must never run alongside another job",
  SYNCHRONIZED_FINISH: "this job must finish alongside another job",
}

/** A job the operator owns, offered when a reference needs settling. */
export interface JobChoice {
  id: number
  name: string
  /** Shown beside the name when two jobs share one, so they can be told apart. */
  startUtc?: string
}

export interface ProposedConstraint {
  /** Stable within one parse, so answers can be keyed to it. */
  key: string
  type: ConstraintTypeName
  /** Probability the reading is correct, 0-1. Not the model's confidence - a different quantity. */
  probability: number
  /** The operator's own words this came from. */
  phrase: string
  /** Set when the constraint binds a second job and exactly one candidate matched. */
  referencedJobId?: number
  /** Set when several of the operator's jobs could be meant, so they must pick. */
  candidates?: JobChoice[]
  /** True when the probability fell in the review band. */
  needsConfirmation: boolean
}

/** What the operator decided about one proposal. */
export type Decision =
  | { accepted: false }
  | { accepted: true; referencedJobId?: number }

/**
 * Whether a proposal still needs an answer before the job can be saved.
 *
 * Two reasons: the probability landed in the review band, or the words matched more than one of
 * the operator's jobs. The second is not a confidence problem - the classifier may be certain the
 * message means "gpt-agent-5" while two jobs carry that name - so it is asked separately.
 */
export function needsOperatorInput(proposal: ProposedConstraint): boolean {
  return proposal.needsConfirmation || (proposal.candidates?.length ?? 0) > 1
}

/** The proposals blocking a save, in the order they should be presented. */
export function outstanding(proposals: ProposedConstraint[]): ProposedConstraint[] {
  return proposals.filter(needsOperatorInput)
}

/**
 * Why the job cannot be saved yet, or null when it can.
 *
 * Returning the reason rather than a boolean lets the form say what is missing instead of
 * disabling a button with no explanation.
 */
export function saveBlocker(
  proposals: ProposedConstraint[],
  decisions: Record<string, Decision>,
): string | null {
  const unanswered = outstanding(proposals).filter((p) => !decisions[p.key])
  if (unanswered.length > 0) {
    return unanswered.length === 1
      ? "Confirm the remaining suggested constraint before saving."
      : `Confirm the ${unanswered.length} remaining suggested constraints before saving.`
  }
  const unresolved = outstanding(proposals).filter((p) => {
    const decision = decisions[p.key]
    if (!decision?.accepted) return false
    // An accepted constraint that binds another job is only settled once that job is chosen.
    return (p.candidates?.length ?? 0) > 1 && decision.referencedJobId === undefined
  })
  return unresolved.length > 0 ? "Choose which job each constraint refers to before saving." : null
}

/** The proposals that will actually be applied, once every question has been answered. */
export function applied(
  proposals: ProposedConstraint[],
  decisions: Record<string, Decision>,
): Array<{ proposal: ProposedConstraint; referencedJobId?: number }> {
  return proposals
    .filter((p) => {
      const decision = decisions[p.key]
      // Anything that never needed asking is applied; anything asked about needs a yes.
      return needsOperatorInput(p) ? decision?.accepted === true : true
    })
    .map((p) => ({
      proposal: p,
      referencedJobId: decisions[p.key]?.accepted
        ? (decisions[p.key] as { referencedJobId?: number }).referencedJobId ?? p.referencedJobId
        : p.referencedJobId,
    }))
}
