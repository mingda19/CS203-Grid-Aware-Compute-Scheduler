import { describe, it, expect } from "vitest"
import { layoutEvents, type CalendarEvent } from "./GoogleCalendarView"

const colorScheme = { bg: "", border: "", text: "", badge: "", dot: "" }

function event(id: string, startHour: number, durationHours: number, startMinute = 0): CalendarEvent {
  return {
    id, title: id, type: "ML Training", cluster: "c", powerKw: 10, savings: "$1",
    status: "Scheduled", dateStr: "2026-11-04", startHour, startMinute, durationHours, colorScheme,
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
