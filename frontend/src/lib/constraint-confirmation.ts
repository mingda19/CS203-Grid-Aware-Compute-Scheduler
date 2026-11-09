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
