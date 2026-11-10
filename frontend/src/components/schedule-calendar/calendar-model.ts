import {
  getMarketHoursMinutes, getMarketDateString, addMarketDays, marketDateToUtcIso, parseIsoDate,
  formatMarketTime, MARKET_TIMEZONE,
} from "@/lib/date-utils"
import type { WorkloadSchedule } from "@/lib/api"

export interface CalendarEvent {
  id: string
  title: string
  /** The job's category, shown as text in the details. */
  type: string
  savings: string | null
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  dateStr: string // YYYY-MM-DD
  startHour: number // 0-23
  startMinute: number
  durationHours: number
  /** The run's true bounds. Kept so a run spanning days can be clipped per day; see splitAcrossDays. */
  startUtc: string
  endUtc: string
  /** Set on a segment that began on an earlier day, so it is drawn as continuing rather than starting. */
  continuesBefore?: boolean
  /** Set on a segment that runs past midnight into the next day. */
  continuesAfter?: boolean
  /** Unique per rendered segment; `id` stays the run's id so selection still resolves to the run. */
  segmentKey?: string
  /** Whose job this is, and whether the signed-in user owns it. Only the owner changes the status. */
  ownerName?: string
  mine?: boolean
  /** The approved plan and the job behind this entry. Null for a row added by hand. */
  runId: string | null
  jobId: number | null
}

export function scheduleToEvent(schedule: WorkloadSchedule): CalendarEvent {
  const start = parseIsoDate(schedule.startUtc)
  const end = parseIsoDate(schedule.endUtc)
  const { hours: startHour, minutes: startMinute } = getMarketHoursMinutes(start)
  return {
    id: String(schedule.id),
    title: schedule.title,
    type: schedule.type,
    savings: schedule.savings,
    status: schedule.status,
    dateStr: getMarketDateString(start),
    startHour,
    startMinute,
    durationHours: Math.max((end.getTime() - start.getTime()) / 3_600_000, 0),
    startUtc: start.toISOString(),
    endUtc: end.toISOString(),
    ownerName: schedule.ownerName,
    mine: schedule.mine,
    runId: schedule.runId ?? null,
    jobId: schedule.jobId ?? null,
  }
}

/** Hours in a nominal calendar grid column. The grid is 24 rows of PX_PER_HOUR regardless of DST. */
export const HOURS_IN_DAY = 24
export const PX_PER_HOUR = 48
/** Shortest a block is drawn, so a very short run stays clickable. */
export const MIN_BLOCK_PX = 24

/**
 * Height in px for a block starting at {@code topPx} and lasting {@code durationHours}, never
 * extending past the bottom of the 24-hour grid, and never smaller than a readable block.
 */
export function clampToGrid(topPx: number, durationHours: number): number {
  const remainingPx = Math.max(HOURS_IN_DAY * PX_PER_HOUR - topPx, 0)
  const wanted = Math.max(durationHours, 0) * PX_PER_HOUR - 3
  return Math.max(Math.min(wanted, remainingPx), MIN_BLOCK_PX)
}

/** A run's whole length in hours, independent of how it was split across day columns. */
export function runLengthHours(event: CalendarEvent): number {
  return Math.max((new Date(event.endUtc).getTime() - new Date(event.startUtc).getTime()) / 3_600_000, 0)
}

/**
 * Splits a run into one segment per market day it covers, each clipped to that day.
 *
 * <p>A day column positions a run by its start hour and sizes it by its duration, so a run longer
 * than the hours left in its first day used to render as a single box taller than the column - a
 * 52-hour job starting 03:00 Thursday drew one box running 52 hours off the bottom of Thursday
 * instead of continuing into Friday and Saturday. Each segment gets its own date, start and
 * duration, so every day it touches draws the part belonging to that day.
 *
 * <p>Clipping is computed from the real instants against each market day's own midnight rather than
 * by adding 24 hours, because a market day is 23 or 25 hours long across a DST transition. Segments
 * keep the run's {@code id} so clicking any of them still opens the same run, and carry
 * {@code continuesBefore}/{@code continuesAfter} so the UI can show them as one run rather than
 * several jobs that happen to share a name.
 */
export function splitAcrossDays(event: CalendarEvent): CalendarEvent[] {
  const start = new Date(event.startUtc)
  const end = new Date(event.endUtc)
  if (!(end.getTime() > start.getTime())) {
    // Unusable or inverted instants. Returning the run untouched would hand the grid back exactly
    // the box this function exists to prevent - one longer than the day it is drawn in - so fall
    // back to its stated start and clip to the end of that day instead.
    return [{
      ...event,
      durationHours: Math.min(Math.max(event.durationHours, 0), HOURS_IN_DAY - event.startHour - event.startMinute / 60),
      segmentKey: event.id,
    }]
  }

  const segments: CalendarEvent[] = []
  const lastDate = getMarketDateString(end.getTime() - 1) // -1ms: an end exactly at midnight belongs to the day before
  for (let date = getMarketDateString(start); ; date = addMarketDays(date, 1)) {
    const dayStart = new Date(marketDateToUtcIso(date)).getTime()
    const dayEnd = new Date(marketDateToUtcIso(addMarketDays(date, 1))).getTime()
    const from = Math.max(start.getTime(), dayStart)
    const to = Math.min(end.getTime(), dayEnd)
    if (to > from) {
      const { hours, minutes } = getMarketHoursMinutes(from)
      segments.push({
        ...event,
        dateStr: date,
        startHour: hours,
        startMinute: minutes,
        durationHours: (to - from) / 3_600_000,
        continuesBefore: from > start.getTime(),
        continuesAfter: to < end.getTime(),
        segmentKey: `${event.id}@${date}`,
      })
    }
    if (date === lastDate) break
  }
  return segments.length ? segments : [{ ...event, segmentKey: event.id }]
}

export interface LaidOutEvent {
  event: CalendarEvent
  lane: number
  laneCount: number
  /** Which connected-overlap group the event is in, within one layoutEvents call. */
  group: number
}

/**
 * Events in the same day column used to all render full-width at their own top offset, so two jobs
 * in the same (or an overlapping) slot fully overlapped - only the last one painted was visible or
 * clickable. Assigns each event a lane (greedy interval graph coloring: the lowest-numbered lane
 * whose last event ends at or before this one starts) within its own connected-overlap group, so
 * unrelated events elsewhere in the day keep full width instead of all sharing the busiest
 * group's lane count.
 */
export function layoutEvents(events: CalendarEvent[]): LaidOutEvent[] {
  const withRange = events
    .map((event) => {
      const startMin = event.startHour * 60 + event.startMinute
      return { event, startMin, endMin: startMin + Math.max(event.durationHours * 60, 1) }
    })
    .sort((a, b) => a.startMin - b.startMin || a.endMin - b.endMin)

  const clusters: (typeof withRange)[] = []
  let clusterEnd = -Infinity
  for (const item of withRange) {
    if (clusters.length === 0 || item.startMin >= clusterEnd) {
      clusters.push([item])
    } else {
      clusters[clusters.length - 1].push(item)
    }
    clusterEnd = Math.max(clusterEnd, item.endMin)
  }

  const result: LaidOutEvent[] = []
  clusters.forEach((cluster, group) => {
    const laneEnds: number[] = [] // laneEnds[lane] = end time of the last event placed in that lane
    const lanes: number[] = []
    for (const item of cluster) {
      let lane = laneEnds.findIndex((end) => end <= item.startMin)
      if (lane === -1) lane = laneEnds.length
      laneEnds[lane] = item.endMin
      lanes.push(lane)
    }
    const laneCount = laneEnds.length
    cluster.forEach((item, i) => result.push({ event: item.event, lane: lanes[i], laneCount, group }))
  })
  return result
}

/** A "+N" block standing in for the jobs in lanes 2 and up of a crowded group. */
export interface MoreBlock {
  group: number
  count: number
  startMin: number
  endMin: number
}

export const MAX_DRAWN_LANES = 3

/**
 * Week view has no room for more than three lanes. In a group wider than that, lanes 0 and 1 are
 * drawn as usual and every other job is replaced by one "+N" block spanning the group's earliest
 * start to its latest end, in lane 2 of 3. Day view skips this and draws every lane.
 */
export function collapseLanes(laid: LaidOutEvent[]): { shown: LaidOutEvent[]; more: MoreBlock[] } {
  const shown: LaidOutEvent[] = []
  const hidden = new Map<number, LaidOutEvent[]>()
  for (const item of laid) {
    if (item.laneCount <= MAX_DRAWN_LANES) shown.push(item)
    else if (item.lane < MAX_DRAWN_LANES - 1) shown.push({ ...item, laneCount: MAX_DRAWN_LANES })
    else hidden.set(item.group, [...(hidden.get(item.group) ?? []), item])
  }
  const more = [...hidden].map(([group, items]) => {
    const startMin = Math.min(...items.map(({ event }) => event.startHour * 60 + event.startMinute))
    const endMin = Math.max(...items.map(({ event }) => event.startHour * 60 + event.startMinute + Math.max(event.durationHours * 60, 1)))
    return { group, count: items.length, startMin, endMin }
  })
  return { shown, more }
}

/** "Tue 11 Nov, 09:00" in market time, 24-hour, no zone (the zone is labelled once in the view). */
export function formatDayTime(iso: string): string {
  const d = parseIsoDate(iso)
  if (isNaN(d.getTime())) return "—"
  const day = new Intl.DateTimeFormat("en-GB", { timeZone: MARKET_TIMEZONE, weekday: "short", day: "numeric", month: "short" })
    .format(d).replace(",", "")
  return `${day}, ${formatMarketTime(d, false)}`
}

/** "09:00–13:00" for the part of a run a segment draws. */
export function formatSegmentRange(event: CalendarEvent): string {
  const start = event.startHour * 60 + event.startMinute
  const end = Math.round(start + event.durationHours * 60)
  const clock = (min: number) => `${String(Math.floor(min / 60)).padStart(2, "0")}:${String(min % 60).padStart(2, "0")}`
  return `${clock(start)}–${clock(Math.min(end, 24 * 60))}` // a segment ends at 24:00 at the latest
}

/** A run whose end has passed. Many ended runs still say Scheduled, so status alone cannot tell. */
export function hasEnded(event: CalendarEvent, nowMs = Date.now()): boolean {
  return new Date(event.endUtc).getTime() <= nowMs
}

export function formatLength(hours: number): string {
  return `${hours % 1 === 0 ? hours : hours.toFixed(1)} h`
}
