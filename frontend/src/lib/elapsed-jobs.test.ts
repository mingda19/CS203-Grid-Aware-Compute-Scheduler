import { describe, expect, it } from "vitest"
import { elapsedJobLabel, elapsedJobsConfirmation, elapsedStartJobs } from "./elapsed-jobs"
import { currentMarketHourInput, currentMarketHourStart } from "./date-utils"
import type { ComputeJob } from "./api"

/**
 * Task 3. Seven of eleven jobs came back NO_FEASIBLE_SLOT because their start times had passed, so
 * the UI must spot that before optimising and ask, naming the jobs. The question has to be asked
 * only when it applies: adding a confirmation click to every optimise run would be a worse bug than
 * the one being fixed.
 */
const job = (over: Partial<ComputeJob> & { id: number }): ComputeJob => ({
  name: `job-${over.id}`, cpuHours: 4, gpuHours: 0,
  originalStartTime: "2026-11-04T10:00:00", deadline: "2026-11-04T20:00:00",
  flexibilityWindowHours: 2, isExempt: false, status: "PENDING", ...over,
})

// Fixed instant so these are not time bombs. 2026-11-04T18:30Z is 12:30 CST (UTC-6 in November),
// so the current market hour began at 18:00Z.
const NOW = "2026-11-04T18:30:00Z"

describe("detecting jobs whose start time has passed", () => {
  it("selects only the jobs that started before the current market hour", () => {
    const elapsed = job({ id: 1, originalStartTime: "2026-11-04T17:00:00" })
    const onTheHour = job({ id: 2, originalStartTime: "2026-11-04T18:00:00" })
    const later = job({ id: 3, originalStartTime: "2026-11-04T19:00:00" })

    expect(elapsedStartJobs([elapsed, onTheHour, later], NOW).map(j => j.id)).toEqual([1])
  })

  it("treats the current hour as still usable, matching the optimiser", () => {
    // The server's earliest schedulable slot is the current hour truncated, and starts are on the
    // hour, so a job starting exactly now has not elapsed and needs no confirmation.
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-11-04T18:00:00" })], NOW)).toEqual([])
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-11-04T17:59:00" })], NOW).map(j => j.id))
      .toEqual([1])
  })

  it("compares instants, not wall-clock strings, so the UTC offset cannot shift the answer", () => {
    // 2026-11-04T18:00:00Z is 12:00 CST. A naive comparison of the job's UTC string against the
    // market wall-clock string "2026-11-04T12:00" would call this job elapsed by six hours.
    expect(currentMarketHourInput(NOW)).toBe("2026-11-04T12:00")
    expect(currentMarketHourStart(NOW).toISOString()).toBe("2026-11-04T18:00:00.000Z")
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-11-04T18:00:00" })], NOW)).toEqual([])
  })

  it("gets the offset right in summer too, when Central Time is UTC-5", () => {
    // A fixed offset would be wrong for half the year; date-utils resolves it per instant.
    const summer = "2026-07-04T18:30:00Z"
    expect(currentMarketHourStart(summer).toISOString()).toBe("2026-07-04T18:00:00.000Z")
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-07-04T17:00:00" })], summer).map(j => j.id))
      .toEqual([1])
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-07-04T18:00:00" })], summer)).toEqual([])
  })

  it("leaves exempt jobs out: the opt-in cannot move them, so naming them would mislead", () => {
    const exempt = job({ id: 1, originalStartTime: "2026-11-04T17:00:00", isExempt: true })
    const flexible = job({ id: 2, originalStartTime: "2026-11-04T17:00:00" })

    expect(elapsedStartJobs([exempt, flexible], NOW).map(j => j.id)).toEqual([2])
  })

  it("ignores an unparseable start rather than inventing an elapsed job", () => {
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "not-a-date" })], NOW)).toEqual([])
  })

  it("accepts a start that already carries an explicit Z", () => {
    expect(elapsedStartJobs([job({ id: 1, originalStartTime: "2026-11-04T17:00:00Z" })], NOW).map(j => j.id))
      .toEqual([1])
  })

  it("returns nothing when no job has elapsed, so the common path adds no confirmation click", () => {
    const upcoming = [
      job({ id: 1, originalStartTime: "2026-11-04T19:00:00" }),
      job({ id: 2, originalStartTime: "2026-11-05T02:00:00" }),
    ]
    expect(elapsedStartJobs(upcoming, NOW)).toEqual([])
    expect(elapsedStartJobs([], NOW)).toEqual([])
  })
})

describe("what the confirmation says", () => {
  it("names each job by name and id, never the id alone", () => {
    expect(elapsedJobLabel(job({ id: 118, name: "Nightly ETL" }))).toBe("Nightly ETL (#118)")
  })

  it("states plainly that only hours from the current hour to each deadline will be used", () => {
    const text = elapsedJobsConfirmation(7, NOW)
    expect(text).toContain("These 7 jobs' start time has already passed")
    expect(text).toContain("only the hours from")
    expect(text).toContain("up to each job's own deadline")
    // The two guarantees that make the widening safe to accept.
    expect(text).toContain("Nothing will be scheduled before the current hour")
    expect(text).toContain("deadline has also passed stays unschedulable")
    // In market-local time, with the zone named once for that instant - never a bare UTC timestamp.
    expect(text).toContain("from 04 Nov 2026, 12:00 CST onwards")
  })

  it("reads correctly for a single job", () => {
    expect(elapsedJobsConfirmation(1, NOW)).toContain("This job's start time has already passed")
  })

  it("names CDT in summer, not a hardcoded CST", () => {
    expect(elapsedJobsConfirmation(1, "2026-07-04T18:30:00Z")).toContain("CDT")
  })
})
