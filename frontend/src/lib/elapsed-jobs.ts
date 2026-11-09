import type { ComputeJob } from "./api"
import { currentMarketHourStart, formatMarketDateTime } from "./date-utils"

/**
 * Jobs whose `originalStartTime` is already behind the current market hour.
 *
 * The optimiser will not place work in an hour that has passed, and a job's flexibility window is
 * anchored to its start time - so once that start is behind us, the whole window
 * `[originalStartTime, originalStartTime + flexibilityWindowHours]` can be behind us too, and the
 * job comes back `NO_FEASIBLE_SLOT` with its deadline still hours away. That is what asking the
 * operator to confirm `rescheduleElapsedJobs` is for, and this is the list the dialog names.
 *
 * `originalStartTime` is a timezone-free string meaning UTC (see `toInputDate`), so it is compared
 * as an instant against {@link currentMarketHourStart} - the same boundary the server derives from
 * its clock. Comparing wall-clock strings instead would be wrong by the UTC offset, and ambiguous
 * for the repeated hour when Central Time falls back.
 *
 * Exempt jobs are left out: they are pinned to their original time by definition, so the opt-in
 * cannot move them and naming them in the dialog would promise something that will not happen.
 *
 * A job whose start cannot be parsed is also left out: it is not evidence of an elapsed start, and
 * the consequence of a false positive here is an extra confirmation click on the common path.
 */
export function elapsedStartJobs(jobs: ComputeJob[], now: Date | string | number = Date.now()): ComputeJob[] {
  const hourStart = currentMarketHourStart(now).getTime()
  return jobs.filter(job => {
    if (job.isExempt) return false
    const start = Date.parse(utcIso(job.originalStartTime))
    return !Number.isNaN(start) && start < hourStart
  })
}

/** The API's timezone-free UTC strings need an explicit `Z` before `Date.parse` treats them as UTC. */
function utcIso(value: string): string {
  return value.endsWith("Z") || value.includes("+") ? value : `${value}Z`
}

/**
 * One line per elapsed job for the confirmation dialog: name and id, never the id alone - an
 * operator scanning a list of seven cannot tell which of their jobs "#118" is.
 */
export function elapsedJobLabel(job: ComputeJob): string {
  return `${job.name} (#${job.id})`
}

/**
 * What the confirmation dialog states plainly, in market-local time: optimising will use only the
 * hours from now to each job's deadline. Kept here rather than in the component so the wording is
 * covered by tests and cannot drift from what the flag actually does.
 */
export function elapsedJobsConfirmation(count: number, now: Date | string | number = Date.now()): string {
  const hourStart = currentMarketHourStart(now)
  return `${count === 1 ? "This job's" : `These ${count} jobs'`} start time has already passed. `
    + `If you continue, the optimiser will use only the hours from `
    + `${formatMarketDateTime(hourStart.toISOString())} `
    + `onwards, up to each job's own deadline - which may place them outside the flexibility window `
    + `you originally set. Nothing will be scheduled before the current hour, and any job whose `
    + `deadline has also passed stays unschedulable.`
}
