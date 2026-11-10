import { describe, it, expect } from "vitest"
import { clampToGrid, layoutEvents, runLengthHours, scheduleToEvent, splitAcrossDays, type CalendarEvent } from "./GoogleCalendarView"

const colorScheme = { bg: "", border: "", text: "", badge: "", dot: "" }

function event(id: string, startHour: number, durationHours: number, startMinute = 0): CalendarEvent {
  // These lane-layout cases only care about start and duration within one day, so the instants are
  // derived from them rather than stated: splitAcrossDays has its own cases below.
  const start = Date.UTC(2026, 10, 4, startHour, startMinute)
  return {
    id, title: id, type: "ML Training", cluster: "c", powerKw: 10, savings: "$1",
    status: "Scheduled", dateStr: "2026-11-04", startHour, startMinute, durationHours, colorScheme,
    startUtc: new Date(start).toISOString(),
    endUtc: new Date(start + durationHours * 3_600_000).toISOString(),
  }
}

/** A run stated by its real instants, which is what splitAcrossDays works from. */
function run(id: string, startUtc: string, endUtc: string): CalendarEvent {
  return {
    id, title: id, type: "ML Training", cluster: "c", powerKw: 10, savings: "$1",
    status: "Scheduled", dateStr: "", startHour: 0, startMinute: 0, durationHours: 0, colorScheme,
    startUtc, endUtc,
  }
}

describe("layoutEvents (bug 5: overlapping same-slot jobs render side by side)", () => {
  it("gives a single event the full lane", () => {
    const [result] = layoutEvents([event("a", 9, 2)])
    expect(result).toMatchObject({ lane: 0, laneCount: 1 })
  })

  it("splits two events that occupy the exact same slot into two lanes", () => {
    const laidOut = layoutEvents([event("a", 9, 2), event("b", 9, 2)])
    const lanes = laidOut.map((e) => e.lane).sort()
    expect(lanes).toEqual([0, 1])
    expect(laidOut.every((e) => e.laneCount === 2)).toBe(true)
  })

  it("reuses a lane once its previous occupant has ended", () => {
    // a: 09:00-10:00, b: 09:30-10:30 (overlaps a), c: 11:00-12:00 (starts after both end)
    const laidOut = layoutEvents([event("a", 9, 1), event("b", 9, 1, 30), event("c", 11, 1)])
    const byId = Object.fromEntries(laidOut.map((e) => [e.event.id, e]))
    expect(byId.a.lane).not.toBe(byId.b.lane) // a and b overlap, so they must differ
    expect(byId.a.laneCount).toBe(2)
    expect(byId.b.laneCount).toBe(2)
    // c doesn't overlap anything, so it gets its own single-lane cluster, not the busy day's lane count.
    expect(byId.c).toMatchObject({ lane: 0, laneCount: 1 })
  })

  it("keeps unrelated same-day events at full width instead of sharing the busiest cluster's lane count", () => {
    // Three-way overlap at 09:00-10:00, and a fourth, unrelated event at 14:00-15:00.
    const laidOut = layoutEvents([event("a", 9, 1), event("b", 9, 1), event("c", 9, 1), event("d", 14, 1)])
    const byId = Object.fromEntries(laidOut.map((e) => [e.event.id, e]))
    expect(byId.a.laneCount).toBe(3)
    expect(byId.b.laneCount).toBe(3)
    expect(byId.c.laneCount).toBe(3)
    expect(byId.d).toMatchObject({ lane: 0, laneCount: 1 })
  })

  it("treats a transitively-overlapping chain as one cluster, reusing a lane once it's free", () => {
    // a: 09:00-10:00, b: 09:30-11:00 (overlaps a only), c: 10:30-11:30 (overlaps b only, not a)
    // a and c don't overlap each other, so the cluster only needs 2 lanes, not 3: a and c
    // can share a lane as long as each individually avoids overlapping b.
    const laidOut = layoutEvents([event("a", 9, 1), event("b", 9, 1.5, 30), event("c", 10, 1, 30)])
    const byId = Object.fromEntries(laidOut.map((e) => [e.event.id, e]))
    expect(byId.a.laneCount).toBe(2)
    expect(byId.b.laneCount).toBe(2)
    expect(byId.c.laneCount).toBe(2)
    expect(byId.a.lane).not.toBe(byId.b.lane)
    expect(byId.b.lane).not.toBe(byId.c.lane)
    expect(byId.a.lane).toBe(byId.c.lane) // non-overlapping, so free to share a's now-vacated lane
  })
})

describe("splitAcrossDays (a run crossing midnight is drawn in every day column it touches)", () => {
  it("leaves a run inside one day as a single segment", () => {
    const segments = splitAcrossDays(run("a", "2026-11-05T15:00:00Z", "2026-11-05T18:00:00Z"))

    expect(segments).toHaveLength(1)
    expect(segments[0]).toMatchObject({ dateStr: "2026-11-05", startHour: 9, durationHours: 3 })
    expect(segments[0].continuesBefore).toBeFalsy()
    expect(segments[0].continuesAfter).toBeFalsy()
  })

  it("splits the reported 52-hour run from 03:00 into three days instead of overflowing one", () => {
    // 03:00 CT on 05 Nov is 09:00Z; 52 hours later is 07:00 CT on 07 Nov.
    const segments = splitAcrossDays(run("gpt-agent-7", "2026-11-05T09:00:00Z", "2026-11-07T13:00:00Z"))

    expect(segments.map((s) => [s.dateStr, s.startHour, s.durationHours])).toEqual([
      ["2026-11-05", 3, 21],
      ["2026-11-06", 0, 24],
      ["2026-11-07", 0, 7],
    ])
  })

  it("marks which segments continue, so three boxes read as one run", () => {
    const segments = splitAcrossDays(run("a", "2026-11-05T09:00:00Z", "2026-11-07T13:00:00Z"))

    expect(segments.map((s) => [s.continuesBefore ?? false, s.continuesAfter ?? false])).toEqual([
      [false, true], [true, true], [true, false],
    ])
  })

  it("keeps the run's id on every segment but gives each a distinct render key", () => {
    const segments = splitAcrossDays(run("gpt-agent-7", "2026-11-05T09:00:00Z", "2026-11-07T13:00:00Z"))

    expect(new Set(segments.map((s) => s.id))).toEqual(new Set(["gpt-agent-7"]))
    expect(new Set(segments.map((s) => s.segmentKey)).size).toBe(3)
  })

  it("does not emit an empty segment for a run ending exactly at midnight", () => {
    // 00:00 CT on 06 Nov is 06:00Z. That instant belongs to the 5th, not the 6th.
    const segments = splitAcrossDays(run("a", "2026-11-05T09:00:00Z", "2026-11-06T06:00:00Z"))

    expect(segments.map((s) => s.dateStr)).toEqual(["2026-11-05"])
    expect(segments[0].durationHours).toBe(21)
  })

  it("gives the autumn DST day its true 25 hours rather than assuming 24", () => {
    // Clocks go back on 01 Nov 2026, so that market day is 25 hours long. Splitting by adding 24
    // hours would leave an hour of the run unplaced and shift every later segment.
    const segments = splitAcrossDays(run("a", "2026-10-31T19:00:00Z", "2026-11-02T18:00:00Z"))
    const first = segments.find((s) => s.dateStr === "2026-11-01")

    expect(first?.durationHours).toBe(25)
    expect(first?.startHour).toBe(0)
  })

  it("tolerates a zero-length or inverted run instead of looping forever", () => {
    expect(splitAcrossDays(run("a", "2026-11-05T09:00:00Z", "2026-11-05T09:00:00Z"))).toHaveLength(1)
    expect(splitAcrossDays(run("a", "2026-11-05T12:00:00Z", "2026-11-05T09:00:00Z"))).toHaveLength(1)
  })
})

describe("runLengthHours", () => {
  it("reports the whole run, not the clicked segment", () => {
    const whole = run("a", "2026-11-05T09:00:00Z", "2026-11-07T13:00:00Z")

    expect(runLengthHours(whole)).toBe(52)
    // Every segment still answers for the whole run, which is what the detail dialog shows.
    expect(splitAcrossDays(whole).map(runLengthHours)).toEqual([52, 52, 52])
  })

  it("never goes negative on an inverted run", () => {
    expect(runLengthHours(run("a", "2026-11-05T12:00:00Z", "2026-11-05T09:00:00Z"))).toBe(0)
  })
})

describe("clampToGrid (a box never paints past the bottom of the day)", () => {
  it("sizes an ordinary block from its duration", () => {
    expect(clampToGrid(9 * 60, 2)).toBe(2 * 60 - 3)
  })

  it("clips a block that would run past midnight", () => {
    // 22:00 with 6 hours left to run: 2 hours of grid remain, so 2 hours is what is drawn.
    expect(clampToGrid(22 * 60, 6)).toBe(2 * 60)
  })

  it("clips the 52-hour run that overflowed the calendar", () => {
    // The original defect: 03:00 + 52h drew a box 3120px tall in a 1440px grid.
    expect(clampToGrid(3 * 60, 52)).toBe(21 * 60)
    expect(clampToGrid(3 * 60, 52)).toBeLessThanOrEqual(24 * 60)
  })

  it("keeps a very short block readable rather than collapsing it", () => {
    expect(clampToGrid(9 * 60, 0.1)).toBe(24)
    expect(clampToGrid(9 * 60, 0)).toBe(24)
  })

  it("never returns a negative height for a block starting past the grid", () => {
    expect(clampToGrid(30 * 60, 2)).toBe(24)
  })
})

/**
 * Built from rows that were actually in workload_schedules when the calendar was reported as
 * overflowing, driven through the same pipeline the page uses (scheduleToEvent -> splitAcrossDays
 * -> clampToGrid). The grid is 24 rows of 60px, so no box may end past 1440px; the original defect
 * drew an 88-hour run as a single 5280px box.
 */
describe("real scheduled rows never paint outside the 24-hour grid", () => {
  const GRID_PX = 24 * 60

  const row = (id: number, title: string, startUtc: string, endUtc: string) => ({
    id, title, type: "ML Training", cluster: "c", powerKw: 1, savings: null,
    status: "Scheduled" as const, startUtc, endUtc, notes: null,
  })

  const REAL_ROWS = [
    row(112, "meow", "2026-11-06T08:00:00Z", "2026-11-10T00:00:00Z"), // 88h, four market days
    row(80, "ML long run", "2026-12-20T22:00:00Z", "2026-12-21T16:00:00Z"), // 18h over midnight
    row(111, "fine", "2026-11-06T04:00:00Z", "2026-11-06T09:00:00Z"), // crosses midnight in CT
    row(26, "DeepSeek Distill", "2026-11-02T16:00:00Z", "2026-11-02T20:00:00Z"), // ordinary
  ]

  it.each(REAL_ROWS.map((r) => [r.title, r] as const))("keeps every segment of %s inside the grid", (_t, r) => {
    for (const seg of splitAcrossDays(scheduleToEvent(r as never))) {
      const topPx = seg.startHour * 60 + (seg.startMinute / 60) * 60
      expect(topPx + clampToGrid(topPx, seg.durationHours)).toBeLessThanOrEqual(GRID_PX)
    }
  })

  it("splits the 88-hour run across its four market days without losing or duplicating time", () => {
    const segments = splitAcrossDays(scheduleToEvent(REAL_ROWS[0] as never))

    expect(segments.map((s) => s.dateStr))
      .toEqual(["2026-11-06", "2026-11-07", "2026-11-08", "2026-11-09"])
    // The parts add back up to the whole run: nothing dropped at a day boundary.
    expect(segments.reduce((total, s) => total + s.durationHours, 0)).toBe(88)
  })

  it("marks the middle days as continuing so they do not read as separate jobs", () => {
    const segments = splitAcrossDays(scheduleToEvent(REAL_ROWS[0] as never))

    expect(segments.map((s) => `${s.continuesBefore ? "<" : "."}${s.continuesAfter ? ">" : "."}`))
      .toEqual([".>", "<>", "<>", "<."])
  })
})

describe("whose entry it is", () => {
  const row = { id: 7, title: "Train", type: "ML Training", cluster: "c", powerKw: 1, savings: null, status: "Scheduled" as const,
    startUtc: "2026-11-06T12:00:00Z", endUtc: "2026-11-06T14:00:00Z", notes: null }

  it("carries the owner's name and whether the signed-in user owns the entry", () => {
    expect(scheduleToEvent({ ...row, ownerName: "Ada", mine: false })).toMatchObject({ ownerName: "Ada", mine: false })
    expect(scheduleToEvent({ ...row, ownerName: "Me", mine: true })).toMatchObject({ ownerName: "Me", mine: true })
  })
})
