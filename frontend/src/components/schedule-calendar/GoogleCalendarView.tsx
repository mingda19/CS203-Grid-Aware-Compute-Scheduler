"use client"

import * as React from "react"
import { useRouter } from "next/navigation"
import { ChevronLeft, ChevronRight, Calendar as CalendarIcon, Clock, Plus, Search, Check, X, Server, Zap, DollarSign, CalendarDays, SlidersHorizontal, RefreshCw, Sparkles } from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { workloadScheduleApi, type WorkloadSchedule } from "@/lib/api"
import {
  getMarketHoursMinutes, getMarketDateString, MARKET_ZONE_LABEL, formatMarketDateTime,
  addMarketDays, addMarketMonths, marketDayOfWeek, marketDaysInMonth,
  marketDateStringFromParts, marketDateLabelAnchor, marketDateToUtcIso, parseIsoDate,
} from "@/lib/date-utils"

export type WorkloadType = "ML Training" | "Crypto Mining" | "HPC Batch" | "HVAC Pre-Cool" | "Grid Alert"

export interface CalendarEvent {
  id: string
  title: string
  type: WorkloadType
  cluster: string
  powerKw: number
  savings: string
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  dateStr: string // YYYY-MM-DD
  startHour: number // 0-23
  startMinute: number // 0 or 30
  durationHours: number // e.g. 2, 3.5, 4
  /** The run's true bounds. Kept so a run spanning days can be clipped per day; see splitAcrossDays. */
  startUtc: string
  endUtc: string
  /** Set on a segment that began on an earlier day, so it is drawn as continuing rather than starting. */
  continuesBefore?: boolean
  /** Set on a segment that runs past midnight into the next day. */
  continuesAfter?: boolean
  /** Unique per rendered segment; `id` stays the run's id so selection still resolves to the run. */
  segmentKey?: string
  colorScheme: {
    bg: string
    border: string
    text: string
    badge: string
    dot: string
  }
  notes?: string
  /** Whose job this is, and whether the signed-in user owns it. Only the owner changes the status. */
  ownerName?: string
  mine?: boolean
}

const TYPE_CONFIG: Record<
  WorkloadType,
  {
    name: string
    bg: string
    border: string
    text: string
    badge: string
    dot: string
    iconColor: string
  }
> = {
  "ML Training": {
    name: "ML Training",
    bg: "bg-blue-500/15 dark:bg-blue-500/20 hover:bg-blue-500/25",
    border: "border-l-4 border-l-blue-500 border border-blue-500/30",
    text: "text-blue-700 dark:text-blue-300",
    badge: "bg-blue-500/20 text-blue-700 dark:text-blue-300",
    dot: "bg-blue-500",
    iconColor: "#3b82f6",
  },
  "Crypto Mining": {
    name: "Crypto Mining",
    bg: "bg-amber-500/15 dark:bg-amber-500/20 hover:bg-amber-500/25",
    border: "border-l-4 border-l-amber-500 border border-amber-500/30",
    text: "text-amber-700 dark:text-amber-300",
    badge: "bg-amber-500/20 text-amber-700 dark:text-amber-300",
    dot: "bg-amber-500",
    iconColor: "#f59e0b",
  },
  "HPC Batch": {
    name: "HPC Batch",
    bg: "bg-purple-500/15 dark:bg-purple-500/20 hover:bg-purple-500/25",
    border: "border-l-4 border-l-purple-500 border border-purple-500/30",
    text: "text-purple-700 dark:text-purple-300",
    badge: "bg-purple-500/20 text-purple-700 dark:text-purple-300",
    dot: "bg-purple-500",
    iconColor: "#8b5cf6",
  },
  "HVAC Pre-Cool": {
    name: "HVAC Pre-Cool",
    bg: "bg-emerald-500/15 dark:bg-emerald-500/20 hover:bg-emerald-500/25",
    border: "border-l-4 border-l-emerald-500 border border-emerald-500/30",
    text: "text-emerald-700 dark:text-emerald-300",
    badge: "bg-emerald-500/20 text-emerald-700 dark:text-emerald-300",
    dot: "bg-emerald-500",
    iconColor: "#10b981",
  },
  "Grid Alert": {
    name: "Grid Peak Alert",
    bg: "bg-rose-500/15 dark:bg-rose-500/20 hover:bg-rose-500/25",
    border: "border-l-4 border-l-rose-500 border border-rose-500/30",
    text: "text-rose-700 dark:text-rose-300",
    badge: "bg-rose-500/20 text-rose-700 dark:text-rose-300",
    dot: "bg-rose-500",
    iconColor: "#f43f5e",
  },
}

// Format date helper: YYYY-MM-DD in market local time
function toDateStr(d: Date): string {
  return getMarketDateString(d)
}

export function scheduleToEvent(schedule: WorkloadSchedule): CalendarEvent {
  const start = parseIsoDate(schedule.startUtc)
  const end = parseIsoDate(schedule.endUtc)
  const type = (Object.keys(TYPE_CONFIG) as WorkloadType[]).includes(schedule.type as WorkloadType)
    ? schedule.type as WorkloadType
    : "ML Training"
  const { hours: startHour, minutes: startMinute } = getMarketHoursMinutes(start)
  const dateStr = getMarketDateString(start)
  return {
    id: String(schedule.id),
    title: schedule.title,
    type,
    cluster: schedule.cluster,
    powerKw: schedule.powerKw,
    savings: schedule.savings ?? "—",
    status: schedule.status,
    dateStr,
    startHour,
    startMinute,
    durationHours: Math.max((end.getTime() - start.getTime()) / 3_600_000, 0),
    startUtc: start.toISOString(),
    endUtc: end.toISOString(),
    colorScheme: TYPE_CONFIG[type],
    notes: schedule.notes ?? undefined,
    ownerName: schedule.ownerName,
    mine: schedule.mine,
  }
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
/** Hours in a nominal calendar grid column. The grid is 24 rows of PX_PER_HOUR regardless of DST. */
const HOURS_IN_DAY = 24
const PX_PER_HOUR = 60

/**
 * Height in px for a block starting at {@code topPx} and lasting {@code durationHours}, never
 * extending past the bottom of the 24-hour grid, and never smaller than a readable 24px.
 */
export function clampToGrid(topPx: number, durationHours: number): number {
  const remainingPx = Math.max(HOURS_IN_DAY * PX_PER_HOUR - topPx, 0)
  const wanted = Math.max(durationHours, 0) * PX_PER_HOUR - 3
  return Math.max(Math.min(wanted, remainingPx), 24)
}

/** A run's whole length in hours, independent of how it was split across day columns. */
export function runLengthHours(event: CalendarEvent): number {
  return Math.max((new Date(event.endUtc).getTime() - new Date(event.startUtc).getTime()) / 3_600_000, 0)
}

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

interface LaidOutEvent {
  event: CalendarEvent
  lane: number
  laneCount: number
}

/**
 * Bug 5: events in the same day column used to all render full-width at their own
 * top offset, so two jobs in the same (or an overlapping) slot fully overlapped -
 * only the last one painted was visible or clickable. Assigns each event a lane
 * (greedy interval graph coloring: the lowest-numbered lane whose last event ends
 * at or before this one starts) within its own connected-overlap cluster, so unrelated
 * events elsewhere in the day keep full width instead of all sharing the busiest
 * cluster's lane count.
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
  for (const cluster of clusters) {
    const laneEnds: number[] = [] // laneEnds[lane] = end time of the last event placed in that lane
    const lanes: number[] = []
    for (const item of cluster) {
      let lane = laneEnds.findIndex((end) => end <= item.startMin)
      if (lane === -1) lane = laneEnds.length
      laneEnds[lane] = item.endMin
      lanes.push(lane)
    }
    const laneCount = laneEnds.length
    cluster.forEach((item, i) => result.push({ event: item.event, lane: lanes[i], laneCount }))
  }
  return result
}

type CalendarViewMode = "week" | "month" | "day" | "schedule"

const HOURS_OF_DAY = Array.from({ length: 24 }, (_, i) => i)

export function GoogleCalendarView() {
  const router = useRouter()
  // Bug 5: anchored at noon UTC on the market-local calendar day being viewed (never
  // a real instant) so every getter/formatter below that touches it can read UTC
  // fields directly instead of accidentally resolving a *different* local-browser
  // calendar day - see marketDateLabelAnchor's doc for why noon UTC is safe for this.
  const [currentDate, setCurrentDate] = React.useState<Date>(() => marketDateLabelAnchor(getMarketDateString(new Date())))
  const [viewMode, setViewMode] = React.useState<CalendarViewMode>("week")
  const [events, setEvents] = React.useState<CalendarEvent[]>([])
  const [loading, setLoading] = React.useState(true)
  const [saving, setSaving] = React.useState(false)
  const [loadError, setLoadError] = React.useState<string | null>(null)
  const [selectedEvent, setSelectedEvent] = React.useState<CalendarEvent | null>(null)
  const [isSidebarOpen, setIsSidebarOpen] = React.useState<boolean>(true)
  const [searchQuery, setSearchQuery] = React.useState<string>("")
  const [activeTypes, setActiveTypes] = React.useState<Record<WorkloadType, boolean>>({
    "ML Training": true,
    "Crypto Mining": true,
    "HPC Batch": true,
    "HVAC Pre-Cool": true,
    "Grid Alert": true,
  })

  const [refreshKey, setRefreshKey] = React.useState(0)
  React.useEffect(() => {
    let active = true
    workloadScheduleApi.list().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Unable to load schedules")
      setEvents(response.data.map(scheduleToEvent))
    }).catch(error => {
      if (active) setLoadError(error instanceof Error ? error.message : "Unable to load schedules")
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [refreshKey])

  // Current time representation for live red line
  const now = new Date()
  const todayStr = toDateStr(now)
  const marketNow = getMarketHoursMinutes(now)
  const currentHourDecimal = marketNow.hours + marketNow.minutes / 60

  // Filter events
  const filteredEvents = React.useMemo(() => {
    return events.filter((ev) => {
      if (!activeTypes[ev.type]) return false
      if (searchQuery.trim()) {
        const query = searchQuery.toLowerCase()
        return (
          ev.title.toLowerCase().includes(query) ||
          ev.cluster.toLowerCase().includes(query) ||
          ev.id.toLowerCase().includes(query)
        )
      }
      return true
    })
  }, [events, activeTypes, searchQuery])

  /**
   * Every visible run split into per-day segments, so a run that crosses midnight is drawn in each
   * day column it touches instead of overflowing the first one. Computed once per filtered list
   * rather than per column.
   */
  const daySegments = React.useMemo(
    () => filteredEvents.flatMap(splitAcrossDays),
    [filteredEvents],
  )

  // Navigation handlers. currentDate is a market-date label anchor (see its
  // declaration), so stepping by day/week/month is pure calendar-string arithmetic -
  // never local Date math, which is what put the "now" highlight a day off in the
  // first place.
  const handlePrev = () => {
    const anchor = getMarketDateString(currentDate)
    const next = viewMode === "day" ? addMarketDays(anchor, -1)
      : viewMode === "week" ? addMarketDays(anchor, -7)
      : addMarketMonths(anchor, -1)
    setCurrentDate(marketDateLabelAnchor(next))
  }

  const handleNext = () => {
    const anchor = getMarketDateString(currentDate)
    const next = viewMode === "day" ? addMarketDays(anchor, 1)
      : viewMode === "week" ? addMarketDays(anchor, 7)
      : addMarketMonths(anchor, 1)
    setCurrentDate(marketDateLabelAnchor(next))
  }

  const handleToday = () => {
    setCurrentDate(marketDateLabelAnchor(getMarketDateString(new Date())))
  }

  // Calculate week days for the week view
  const weekDays = React.useMemo(() => {
    const anchor = getMarketDateString(currentDate)
    const sunday = addMarketDays(anchor, -marketDayOfWeek(anchor)) // 0 = Sun; align to Sunday

    const days: { date: Date; dateStr: string; dayName: string; dayNum: number; isToday: boolean }[] = []
    for (let i = 0; i < 7; i++) {
      const dStr = addMarketDays(sunday, i)
      const d = marketDateLabelAnchor(dStr)
      days.push({
        date: d,
        dateStr: dStr,
        dayName: d.toLocaleDateString("en-US", { weekday: "short", timeZone: "UTC" }),
        dayNum: d.getUTCDate(),
        isToday: dStr === todayStr,
      })
    }
    return days
  }, [currentDate, todayStr])

  // Calculate month cells for the month view. All in market-date-string arithmetic
  // (see currentDate's declaration) - no local Date getter/setter touches a day
  // number here, which is what previously let the grid's displayed day drift a day
  // away from the market day actually used to match events and the "today" flag.
  const monthCells = React.useMemo(() => {
    const [year, month] = getMarketDateString(currentDate).split("-").map(Number)
    const startOffset = marketDayOfWeek(marketDateStringFromParts(year, month, 1)) // 0 = Sun
    const totalDays = marketDaysInMonth(year, month)

    const cells: {
      date: Date
      dateStr: string
      dayNum: number
      isCurrentMonth: boolean
      isToday: boolean
    }[] = []

    // Previous month fill
    for (let i = startOffset - 1; i >= 0; i--) {
      const dStr = marketDateStringFromParts(year, month, -i)
      const d = marketDateLabelAnchor(dStr)
      cells.push({ date: d, dateStr: dStr, dayNum: d.getUTCDate(), isCurrentMonth: false, isToday: dStr === todayStr })
    }

    // Current month days
    for (let i = 1; i <= totalDays; i++) {
      const dStr = marketDateStringFromParts(year, month, i)
      cells.push({ date: marketDateLabelAnchor(dStr), dateStr: dStr, dayNum: i, isCurrentMonth: true, isToday: dStr === todayStr })
    }

    // Trailing next month days (to complete multiple of 7)
    const remaining = 42 - cells.length
    for (let i = 1; i <= remaining; i++) {
      const dStr = marketDateStringFromParts(year, month + 1, i)
      const d = marketDateLabelAnchor(dStr)
      cells.push({ date: d, dateStr: dStr, dayNum: d.getUTCDate(), isCurrentMonth: false, isToday: dStr === todayStr })
    }

    return cells
  }, [currentDate, todayStr])

  // Mini-calendar date matrix for sidebar
  const miniCalendarCells = React.useMemo(() => {
    const currentStr = getMarketDateString(currentDate)
    const [year, month] = currentStr.split("-").map(Number)
    const startOffset = marketDayOfWeek(marketDateStringFromParts(year, month, 1))
    const totalDays = marketDaysInMonth(year, month)

    const cells: { date: Date; dateStr: string; dayNum: number; isCurrentMonth: boolean; isSelected: boolean }[] = []

    for (let i = startOffset - 1; i >= 0; i--) {
      const dStr = marketDateStringFromParts(year, month, -i)
      cells.push({ date: marketDateLabelAnchor(dStr), dateStr: dStr, dayNum: marketDateLabelAnchor(dStr).getUTCDate(), isCurrentMonth: false, isSelected: dStr === currentStr })
    }

    for (let i = 1; i <= totalDays; i++) {
      const dStr = marketDateStringFromParts(year, month, i)
      cells.push({ date: marketDateLabelAnchor(dStr), dateStr: dStr, dayNum: i, isCurrentMonth: true, isSelected: dStr === currentStr })
    }

    const remaining = 35 - cells.length
    if (remaining > 0) {
      for (let i = 1; i <= remaining; i++) {
        const dStr = marketDateStringFromParts(year, month + 1, i)
        cells.push({ date: marketDateLabelAnchor(dStr), dateStr: dStr, dayNum: marketDateLabelAnchor(dStr).getUTCDate(), isCurrentMonth: false, isSelected: dStr === currentStr })
      }
    }

    return cells
  }, [currentDate])

  // Formatted date title. currentDate and weekDays[].date are noon-UTC label
  // anchors (never real instants), so every read here must use the UTC getters
  // and pass timeZone: "UTC" to toLocaleDateString - a local getter/formatter would
  // silently reinterpret the anchor in the viewer's own timezone.
  const dateTitle = React.useMemo(() => {
    if (viewMode === "day") {
      return currentDate.toLocaleDateString("en-US", {
        weekday: "short",
        month: "long",
        day: "numeric",
        year: "numeric",
        timeZone: "UTC",
      })
    }
    if (viewMode === "week") {
      const start = weekDays[0].date
      const end = weekDays[6].date
      const startMonth = start.toLocaleDateString("en-US", { month: "short", timeZone: "UTC" })
      const endMonth = end.toLocaleDateString("en-US", { month: "short", timeZone: "UTC" })
      if (startMonth === endMonth) {
        return `${startMonth} ${start.getUTCDate()} – ${end.getUTCDate()}, ${start.getUTCFullYear()}`
      }
      return `${startMonth} ${start.getUTCDate()} – ${endMonth} ${end.getUTCDate()}, ${end.getUTCFullYear()}`
    }
    return currentDate.toLocaleDateString("en-US", { month: "long", year: "numeric", timeZone: "UTC" })
  }, [currentDate, viewMode, weekDays])

  // Format hour label
  const formatHour = (hour: number) => {
    if (hour === 0) return "12 AM"
    if (hour < 12) return `${hour} AM`
    if (hour === 12) return "12 PM"
    return `${hour - 12} PM`
  }

  const handleUpdateStatus = async (event: CalendarEvent, status: CalendarEvent["status"]) => {
    const id = Number(event.id)
    if (!Number.isInteger(id)) return
    setSaving(true)
    setLoadError(null)
    try {
      const response = await workloadScheduleApi.updateStatus(id, status)
      if (!response.success || !response.data) throw new Error(response.message || "Unable to update schedule")
      const updated = scheduleToEvent(response.data)
      setEvents((prev) => prev.map((item) => item.id === event.id ? updated : item))
      setSelectedEvent(updated)
    } catch (error) {
      setLoadError(error instanceof Error ? error.message : "Unable to update schedule")
    } finally {
      setSaving(false)
    }
  }

  return (
    <div className="flex flex-col h-[820px] bg-background text-foreground rounded-xl border border-border shadow-sm overflow-hidden font-sans">
      {/* 1. TOP GOOGLE CALENDAR HEADER / TOOLBAR */}
      <header className="flex items-center justify-between px-4 py-2.5 border-b border-border bg-card/60 backdrop-blur-sm select-none gap-2">
        {/* Left branding & navigation */}
        <div className="flex items-center gap-3">
          <Button
            variant="ghost"
            size="sm"
            onClick={() => setIsSidebarOpen(!isSidebarOpen)}
            className="h-8 w-8 p-0 text-muted-foreground hover:text-foreground"
            title="Toggle sidebar"
          >
            <SlidersHorizontal className="h-4 w-4" />
          </Button>

          <div className="flex items-center gap-2 mr-2">
            <div className="h-8 w-8 rounded-lg bg-blue-600/10 border border-blue-500/20 flex items-center justify-center text-blue-600 dark:text-blue-400">
              <CalendarIcon className="h-4 w-4" />
            </div>
            <div className="hidden sm:block">
              <span className="font-semibold text-sm tracking-tight">GACS Schedule</span>
              <span className="text-[10px] ml-1.5 px-1.5 py-0.5 rounded-full bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 font-medium">
                Saved schedules
              </span>
            </div>
          </div>

          <Button
            variant="outline"
            size="sm"
            onClick={handleToday}
            className="h-8 px-3 text-xs font-medium rounded-md hover:bg-muted"
          >
            Today
          </Button>

          <Button variant="ghost" size="sm" onClick={() => { setLoading(true); setLoadError(null); setRefreshKey(key => key + 1) }} disabled={loading} title="Refresh schedules" className="h-8 w-8 p-0">
            <RefreshCw className={cn("h-3.5 w-3.5", loading && "animate-spin")} />
          </Button>

          <div className="flex items-center gap-0.5">
            <Button
              variant="ghost"
              size="sm"
              onClick={handlePrev}
              className="h-8 w-8 p-0 rounded-full text-muted-foreground hover:text-foreground"
              title="Previous"
            >
              <ChevronLeft className="h-4 w-4" />
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={handleNext}
              className="h-8 w-8 p-0 rounded-full text-muted-foreground hover:text-foreground"
              title="Next"
            >
              <ChevronRight className="h-4 w-4" />
            </Button>
          </div>

          <h2 className="text-sm md:text-base font-semibold text-foreground tracking-tight ml-1 min-w-[140px]">
            {dateTitle}
          </h2>
        </div>

        {/* Center search bar */}
        <div className="hidden md:flex items-center relative max-w-xs w-full mx-4">
          <Search className="absolute left-2.5 h-3.5 w-3.5 text-muted-foreground" />
          <input
            type="text"
            placeholder="Search scheduled jobs, clusters..."
            value={searchQuery}
            onChange={(e) => setSearchQuery(e.target.value)}
            className="w-full h-8 pl-8 pr-3 text-xs rounded-lg bg-muted/60 border border-border/80 focus:bg-background focus:outline-none focus:ring-1 focus:ring-primary placeholder:text-muted-foreground/70"
          />
          {searchQuery && (
            <button
              onClick={() => setSearchQuery("")}
              className="absolute right-2 text-muted-foreground hover:text-foreground"
            >
              <X className="h-3 w-3" />
            </button>
          )}
        </div>

        {/* Right view switcher & create button */}
        <div className="flex items-center gap-2">
          {/* View mode switcher */}
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            <button
              onClick={() => setViewMode("week")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "week"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Week
            </button>
            <button
              onClick={() => setViewMode("month")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "month"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Month
            </button>
            <button
              onClick={() => setViewMode("day")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "day"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Day
            </button>
            <button
              onClick={() => setViewMode("schedule")}
              className={cn(
                "px-2.5 py-1 rounded-md font-medium transition-all",
                viewMode === "schedule"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              Agenda
            </button>
          </div>

          {/* "+ Create" button Google Calendar Style */}
          <Button
            size="sm"
            onClick={() => router.push("/approvals")}
            className="h-8 px-3 text-xs bg-primary text-primary-foreground hover:bg-primary/90 rounded-md font-medium shadow-xs gap-1.5"
          >
            <Plus className="h-3.5 w-3.5" />
            <span className="hidden sm:inline">Review schedules</span>
          </Button>
        </div>
      </header>

      {(loading || loadError) && <div className={cn("px-4 py-2 text-xs", loadError ? "bg-destructive/10 text-destructive" : "bg-muted/40 text-muted-foreground")} role={loadError ? "alert" : "status"}>
        {loadError ? `Could not load or update schedules: ${loadError}` : "Loading schedules…"}
      </div>}

      {/* 2. BODY CONTENT: SIDEBAR + MAIN CALENDAR */}
      <div className="flex flex-1 overflow-hidden relative">
        {/* COLLAPSIBLE LEFT SIDEBAR (GOOGLE CALENDAR STYLE) */}
        {isSidebarOpen && (
          <aside className="w-64 border-r border-border bg-card/40 flex flex-col p-3.5 gap-4 overflow-y-auto select-none shrink-0 transition-all">
            {/* Quick Action Button */}
            <button
              onClick={() => router.push("/approvals")}
              className="flex items-center gap-2.5 px-4 py-2.5 rounded-full border border-border shadow-xs bg-background hover:shadow-md hover:bg-muted/30 transition-all text-xs font-semibold text-foreground group"
            >
              <div className="h-5 w-5 rounded-full bg-blue-500/10 text-blue-600 flex items-center justify-center group-hover:rotate-90 transition-transform">
                <Plus className="h-3.5 w-3.5" />
              </div>
              <span>Review approvals</span>
            </button>

            {/* Mini Month Calendar */}
            <div className="border border-border/70 rounded-lg p-2.5 bg-background/50">
              <div className="flex items-center justify-between mb-2 px-1">
                <span className="text-xs font-semibold text-foreground">
                  {currentDate.toLocaleDateString("en-US", { month: "short", year: "numeric", timeZone: "UTC" })}
                </span>
                <div className="flex items-center gap-1">
                  <button
                    onClick={() => setCurrentDate(marketDateLabelAnchor(addMarketMonths(getMarketDateString(currentDate), -1)))}
                    className="h-5 w-5 rounded hover:bg-muted flex items-center justify-center text-muted-foreground"
                  >
                    <ChevronLeft className="h-3 w-3" />
                  </button>
                  <button
                    onClick={() => setCurrentDate(marketDateLabelAnchor(addMarketMonths(getMarketDateString(currentDate), 1)))}
                    className="h-5 w-5 rounded hover:bg-muted flex items-center justify-center text-muted-foreground"
                  >
                    <ChevronRight className="h-3 w-3" />
                  </button>
                </div>
              </div>

              {/* Day headers */}
              <div className="grid grid-cols-7 gap-1 text-center text-[10px] font-semibold text-muted-foreground mb-1">
                <span>S</span>
                <span>M</span>
                <span>T</span>
                <span>W</span>
                <span>T</span>
                <span>F</span>
                <span>S</span>
              </div>

              {/* Days grid */}
              <div className="grid grid-cols-7 gap-1 text-center text-[11px]">
                {miniCalendarCells.map((cell, idx) => (
                  <button
                    key={idx}
                    onClick={() => {
                      setCurrentDate(cell.date)
                    }}
                    className={cn(
                      "h-6 w-6 mx-auto rounded-full flex items-center justify-center transition-colors",
                      cell.isCurrentMonth ? "text-foreground font-medium" : "text-muted-foreground/50",
                      cell.isSelected && "bg-blue-600 text-white font-bold",
                      !cell.isSelected && cell.dateStr === todayStr && "border border-blue-500 font-bold text-blue-600",
                      !cell.isSelected && "hover:bg-muted"
                    )}
                  >
                    {cell.dayNum}
                  </button>
                ))}
              </div>
            </div>

            {/* Calendar Cluster / Type Filters */}
            <div className="flex flex-col gap-2">
              <div className="flex items-center justify-between px-1">
                <span className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                  Workload Categories
                </span>
                <span className="text-[10px] text-muted-foreground font-mono">{filteredEvents.length} active</span>
              </div>

              <div className="space-y-1">
                {(Object.keys(TYPE_CONFIG) as WorkloadType[]).map((typeKey) => {
                  const cfg = TYPE_CONFIG[typeKey]
                  const isChecked = activeTypes[typeKey]
                  const count = events.filter((e) => e.type === typeKey).length

                  return (
                    <label
                      key={typeKey}
                      onClick={() =>
                        setActiveTypes((prev) => ({
                          ...prev,
                          [typeKey]: !prev[typeKey],
                        }))
                      }
                      className="flex items-center justify-between px-2 py-1.5 rounded-md hover:bg-muted/60 cursor-pointer text-xs transition-colors"
                    >
                      <div className="flex items-center gap-2">
                        <div
                          className={cn(
                            "h-3.5 w-3.5 rounded border flex items-center justify-center transition-colors",
                            isChecked
                              ? `${cfg.dot} border-transparent text-white`
                              : "border-muted-foreground/40 bg-background"
                          )}
                        >
                          {isChecked && <Check className="h-2.5 w-2.5 stroke-[3]" />}
                        </div>
                        <span className={cn("text-xs", isChecked ? "text-foreground font-medium" : "text-muted-foreground line-through")}>
                          {cfg.name}
                        </span>
                      </div>
                      <span className="text-[10px] text-muted-foreground font-mono">
                        {count}
                      </span>
                    </label>
                  )
                })}
              </div>
            </div>

            {/* Grid Awareness Info Card */}
            <div className="mt-auto p-3 rounded-lg bg-emerald-500/10 border border-emerald-500/20 text-xs text-foreground">
              <div className="flex items-center gap-1.5 font-semibold text-emerald-600 dark:text-emerald-400 mb-1">
                <Sparkles className="h-3.5 w-3.5" />
                <span>Grid Price Dispatch</span>
              </div>
              <p className="text-[11px] text-muted-foreground leading-relaxed">
                Calendar entries come from approved schedules. Status updates record scheduling state; they do not control compute hardware.
              </p>
            </div>
          </aside>
        )}

        {/* MAIN CALENDAR DISPLAY AREA */}
        <main className="flex-1 flex flex-col overflow-hidden bg-background">
          {/* ======================================================== */}
          {/* VIEW 1: WEEK VIEW (SIGNATURE GOOGLE CALENDAR TIME GRID) */}
          {/* ======================================================== */}
          {viewMode === "week" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              {/* Day header row */}
              <div className="sticky top-0 z-20 flex border-b border-border bg-card/95 backdrop-blur-sm">
                {/* Time gutter spacer */}
                <div className="w-16 shrink-0 border-r border-border/80 flex items-end justify-center pb-2 text-[10px] font-mono text-muted-foreground">
                  {MARKET_ZONE_LABEL}
                </div>
                {/* 7 Days headers */}
                <div className="grid grid-cols-7 flex-1 divide-x divide-border/80">
                  {weekDays.map((colDay, idx) => (
                    <div
                      key={idx}
                      className={cn(
                        "py-2 px-2 text-center transition-colors",
                        colDay.isToday && "bg-blue-500/5 dark:bg-blue-500/10"
                      )}
                    >
                      <div className="text-[11px] font-semibold uppercase tracking-wider text-muted-foreground">
                        {colDay.dayName}
                      </div>
                      <div
                        className={cn(
                          "inline-flex items-center justify-center h-7 w-7 rounded-full text-xs font-bold mt-0.5",
                          colDay.isToday
                            ? "bg-blue-600 text-white shadow-xs"
                            : "text-foreground"
                        )}
                      >
                        {colDay.dayNum}
                      </div>
                    </div>
                  ))}
                </div>
              </div>

              {/* Time grid body */}
              <div className="flex flex-1 relative min-h-[1440px]">
                {/* Vertical time labels */}
                <div className="w-16 shrink-0 border-r border-border/80 select-none divide-y divide-transparent">
                  {HOURS_OF_DAY.map((h) => (
                    <div
                      key={h}
                      className="h-[60px] relative text-[10px] font-mono text-muted-foreground pr-2 text-right pt-0.5"
                    >
                      <span>{formatHour(h)}</span>
                    </div>
                  ))}
                </div>

                {/* 7 Days Columns */}
                <div className="grid grid-cols-7 flex-1 divide-x divide-border/80 relative">
                  {weekDays.map((colDay, colIdx) => {
                    const dayEvents = daySegments.filter((e) => e.dateStr === colDay.dateStr)

                    return (
                      <div
                        key={colIdx}
                        className={cn(
                          "relative h-full",
                          colDay.isToday && "bg-blue-500/[0.02]"
                        )}
                      >
                        {/* 24 Horizontal hour lines */}
                        {HOURS_OF_DAY.map((h) => (
                          <div
                            key={h}
                            className="h-[60px] border-b border-border/40 hover:bg-muted/15 transition-colors"
                          />
                        ))}

                        {/* Red current time indicator line if today */}
                        {colDay.isToday && (
                          <div
                            className="absolute left-0 right-0 z-10 pointer-events-none flex items-center"
                            style={{ top: `${currentHourDecimal * 60}px` }}
                          >
                            <div className="h-2.5 w-2.5 rounded-full bg-red-500 -ml-1 shadow-xs" />
                            <div className="flex-1 h-[2px] bg-red-500" />
                          </div>
                        )}

                        {/* Event blocks - side by side within a lane when they share a slot (bug 5) */}
                        {layoutEvents(dayEvents).map(({ event: ev, lane, laneCount }) => {
                          const topPx = ev.startHour * 60 + (ev.startMinute / 60) * 60
                          // Clamped to what is left of the day. splitAcrossDays should already have
                          // made every segment fit, so this is a backstop, not the mechanism: a
                          // single bad duration should shorten one box, never paint over the rest of
                          // the calendar as a 52-hour job once did.
                          const heightPx = clampToGrid(topPx, ev.durationHours)
                          const widthPct = 100 / laneCount

                          return (
                            <button
                              key={ev.segmentKey ?? ev.id}
                              onClick={() => setSelectedEvent(ev)}
                              style={{
                                top: `${topPx}px`,
                                height: `${heightPx}px`,
                                left: `calc(${lane * widthPct}% + 2px)`,
                                width: `calc(${widthPct}% - 4px)`,
                              }}
                              className={cn(
                                "absolute rounded-md p-1.5 text-left text-xs overflow-hidden transition-all shadow-xs cursor-pointer z-10 hover:shadow-md hover:scale-[1.01] hover:z-20",
                                ev.colorScheme.bg,
                                ev.colorScheme.border
                              )}
                            >
                              <div className="flex items-center justify-between gap-1">
                                <span className={cn("font-semibold truncate text-[11px]", ev.colorScheme.text)}>
                                  {ev.title}
                                </span>
                                <span className="text-[9px] font-mono shrink-0 px-1 rounded bg-background/60 text-foreground font-medium">
                                  {ev.powerKw > 0 ? `Est. ${ev.powerKw} kW` : "ALERT"}
                                </span>
                              </div>

                              <div className="flex items-center gap-1.5 mt-0.5 text-[10px] text-muted-foreground font-mono">
                                <Clock className="h-2.5 w-2.5 shrink-0" />
                                <span>
                                  {ev.continuesBefore && "↑ "}
                                  {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")} (
                                  {Math.round(ev.durationHours * 10) / 10}h{ev.continuesAfter || ev.continuesBefore ? " of " + Math.round(runLengthHours(ev)) + "h" : ""})
                                  {ev.continuesAfter && " ↓"}
                                </span>
                              </div>

                              {heightPx > 45 && (
                                <div className="mt-1 flex items-center justify-between text-[10px] text-muted-foreground truncate">
                                  <span className="truncate">{ev.cluster}</span>
                                  <span className="font-semibold text-emerald-600 dark:text-emerald-400 font-mono shrink-0">
                                    {ev.savings}
                                  </span>
                                </div>
                              )}
                            </button>
                          )
                        })}
                      </div>
                    )
                  })}
                </div>
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 2: MONTH VIEW (GOOGLE CALENDAR 7X6 GRID)           */}
          {/* ======================================================== */}
          {viewMode === "month" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              {/* Day header row */}
              <div className="grid grid-cols-7 border-b border-border bg-card/95 sticky top-0 z-10 text-center py-2 text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                <span>Sun</span>
                <span>Mon</span>
                <span>Tue</span>
                <span>Wed</span>
                <span>Thu</span>
                <span>Fri</span>
                <span>Sat</span>
              </div>

              {/* 7x6 month grid */}
              <div className="grid grid-cols-7 flex-1 auto-rows-fr divide-x divide-y divide-border/60 min-h-[640px]">
                {monthCells.map((cell, idx) => {
                  const dayEvents = daySegments.filter((e) => e.dateStr === cell.dateStr)

                  return (
                    <div
                      key={idx}
                      onClick={() => {
                        setCurrentDate(cell.date)
                        setViewMode("day")
                      }}
                      className={cn(
                        "p-1.5 flex flex-col min-h-[95px] transition-colors cursor-pointer hover:bg-muted/30 group",
                        !cell.isCurrentMonth && "bg-muted/15 text-muted-foreground/60",
                        cell.isToday && "bg-blue-500/[0.04]"
                      )}
                    >
                      <div className="flex items-center justify-between mb-1">
                        <span
                          className={cn(
                            "h-5 w-5 rounded-full inline-flex items-center justify-center text-xs font-semibold",
                            cell.isToday
                              ? "bg-blue-600 text-white font-bold"
                              : cell.isCurrentMonth
                              ? "text-foreground group-hover:bg-muted"
                              : "text-muted-foreground/50"
                          )}
                        >
                          {cell.dayNum}
                        </span>
                        {dayEvents.length > 0 && (
                          <span className="text-[10px] text-muted-foreground font-mono">
                            {dayEvents.length} job{dayEvents.length > 1 ? "s" : ""}
                          </span>
                        )}
                      </div>

                      {/* Event Chips */}
                      <div className="space-y-1 overflow-hidden flex-1">
                        {dayEvents.slice(0, 3).map((ev) => (
                          <button
                            key={ev.segmentKey ?? ev.id}
                            onClick={(e) => {
                              e.stopPropagation()
                              setSelectedEvent(ev)
                            }}
                            className={cn(
                              "w-full text-left px-1.5 py-0.5 rounded text-[10px] font-medium truncate flex items-center gap-1 hover:brightness-95 transition-all",
                              ev.colorScheme.bg,
                              ev.colorScheme.text
                            )}
                          >
                            <span className={cn("h-1.5 w-1.5 rounded-full shrink-0", ev.colorScheme.dot)} />
                            <span className="font-mono text-[9px] shrink-0">
                              {String(ev.startHour).padStart(2, "0")}:00
                            </span>
                            <span className="truncate">{ev.title}</span>
                          </button>
                        ))}

                        {dayEvents.length > 3 && (
                          <div className="text-[10px] text-muted-foreground font-semibold px-1">
                            +{dayEvents.length - 3} more
                          </div>
                        )}
                      </div>
                    </div>
                  )
                })}
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 3: DAY VIEW (DETAILED 24H TIMELINE)                */}
          {/* ======================================================== */}
          {viewMode === "day" && (
            <div className="flex-1 flex flex-col overflow-y-auto">
              <div className="sticky top-0 z-20 flex border-b border-border bg-card/95 px-6 py-3 items-center justify-between">
                <div>
                  <h3 className="text-base font-bold text-foreground">
                    {currentDate.toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric", timeZone: "UTC" })}
                  </h3>
                  <p className="text-xs text-muted-foreground">
                    {daySegments.filter((e) => e.dateStr === toDateStr(currentDate)).length} workloads scheduled for
                    this 24-hour cycle
                  </p>
                </div>
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => router.push("/approvals")}
                  className="text-xs"
                >
                  <Plus className="h-3.5 w-3.5 mr-1" /> Review approvals
                </Button>
              </div>

              <div className="flex flex-1 relative min-h-[1440px]">
                {/* Time gutter */}
                <div className="w-20 shrink-0 border-r border-border select-none">
                  {HOURS_OF_DAY.map((h) => (
                    <div
                      key={h}
                      className="h-[60px] text-xs font-mono text-muted-foreground pr-3 text-right pt-0.5"
                    >
                      {formatHour(h)}
                    </div>
                  ))}
                </div>

                {/* Day column */}
                <div className="flex-1 relative">
                  {HOURS_OF_DAY.map((h) => (
                    <div key={h} className="h-[60px] border-b border-border/40 hover:bg-muted/10 transition-colors" />
                  ))}

                  {/* Red line if today */}
                  {toDateStr(currentDate) === todayStr && (
                    <div
                      className="absolute left-0 right-0 z-10 pointer-events-none flex items-center"
                      style={{ top: `${currentHourDecimal * 60}px` }}
                    >
                      <div className="h-3 w-3 rounded-full bg-red-500 -ml-1.5 shadow-xs" />
                      <div className="flex-1 h-[2px] bg-red-500" />
                    </div>
                  )}

                  {/* Events - side by side within a lane when they share a slot (bug 5) */}
                  {layoutEvents(daySegments.filter((e) => e.dateStr === toDateStr(currentDate)))
                    .map(({ event: ev, lane, laneCount }) => {
                      const topPx = ev.startHour * 60 + (ev.startMinute / 60) * 60
                      // Same backstop as the week grid: never paint past the bottom of the day.
                      const heightPx = Math.max(clampToGrid(topPx, ev.durationHours), 36)
                      // The original left-4 right-8 margins (16px/32px, 48px total) stay fixed;
                      // only the remaining width is split evenly across lanes.
                      const laneWidth = `(100% - 48px) / ${laneCount}`

                      return (
                        <button
                          key={ev.segmentKey ?? ev.id}
                          onClick={() => setSelectedEvent(ev)}
                          style={{
                            top: `${topPx}px`,
                            height: `${heightPx}px`,
                            left: `calc(16px + (${laneWidth}) * ${lane} + 2px)`,
                            width: `calc(${laneWidth} - 4px)`,
                          }}
                          className={cn(
                            "absolute rounded-lg p-3 text-left overflow-hidden transition-all shadow-xs cursor-pointer z-10 hover:shadow-lg hover:ring-2 hover:ring-primary/40 hover:z-20",
                            ev.colorScheme.bg,
                            ev.colorScheme.border
                          )}
                        >
                          <div className="flex items-center justify-between">
                            <span className={cn("font-bold text-sm", ev.colorScheme.text)}>{ev.title}</span>
                            <span className="text-xs font-mono px-2 py-0.5 rounded bg-background/80 font-bold">
                              Est. {ev.powerKw} kW
                            </span>
                          </div>
                          <div className="flex items-center gap-4 mt-1 text-xs text-muted-foreground">
                            <span className="font-mono">
                              {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")} –{" "}
                              {String(Math.floor(ev.startHour + ev.durationHours)).padStart(2, "0")}:00 (
                              {ev.durationHours} hrs)
                            </span>
                            <span>•</span>
                            <span className="font-medium text-foreground">{ev.cluster}</span>
                            <span>•</span>
                            <span className="font-mono text-emerald-600 dark:text-emerald-400 font-bold">
                              Savings: {ev.savings}
                            </span>
                          </div>
                          {ev.notes && <p className="mt-1 text-xs text-muted-foreground italic">{ev.notes}</p>}
                        </button>
                      )
                    })}
                </div>
              </div>
            </div>
          )}

          {/* ======================================================== */}
          {/* VIEW 4: SCHEDULE / AGENDA VIEW (CHRONOLOGICAL LIST)     */}
          {/* ======================================================== */}
          {viewMode === "schedule" && (
            <div className="flex-1 overflow-y-auto p-4 sm:p-6 space-y-4">
              <div className="flex items-center justify-between pb-3 border-b border-border">
                <div>
                  <h3 className="text-base font-bold text-foreground">Agenda & Dispatch Schedule</h3>
                  <p className="text-xs text-muted-foreground">Chronological sequence of all compute jobs and curtailments</p>
                </div>
                <div className="text-xs text-muted-foreground font-mono">
                  Showing {filteredEvents.length} scheduled jobs
                </div>
              </div>

              {filteredEvents.length === 0 ? (
                <div className="text-center py-16 text-muted-foreground">
                  <CalendarDays className="h-10 w-10 mx-auto mb-2 opacity-40" />
                  <p className="text-sm font-medium">No schedules match your current filters.</p>
                </div>
              ) : (
                <div className="space-y-3">
                  {filteredEvents.map((ev) => (
                    <div
                      key={ev.id}
                      onClick={() => setSelectedEvent(ev)}
                      className={cn(
                        "p-4 rounded-xl border transition-all cursor-pointer hover:shadow-md flex flex-col sm:flex-row sm:items-center sm:justify-between gap-3",
                        ev.colorScheme.border,
                        "bg-card hover:bg-muted/30"
                      )}
                    >
                      <div className="flex items-start gap-3">
                        <div
                          className={cn(
                            "h-10 w-10 rounded-lg flex items-center justify-center shrink-0 mt-0.5",
                            ev.colorScheme.bg
                          )}
                        >
                          <Server className="h-5 w-5" style={{ color: ev.colorScheme.dot }} />
                        </div>
                        <div>
                          <div className="flex items-center gap-2">
                            <span className="font-bold text-sm text-foreground">{ev.title}</span>
                            <span className="text-[10px] font-mono px-1.5 py-0.5 rounded bg-muted text-muted-foreground">
                              {ev.id}
                            </span>
                            <span className={cn("text-[10px] px-2 py-0.5 rounded-full font-semibold", ev.colorScheme.badge)}>
                              {ev.type}
                            </span>
                          </div>
                          <div className="flex flex-wrap items-center gap-3 mt-1.5 text-xs text-muted-foreground">
                            <span className="font-mono text-foreground font-medium">{ev.cluster}</span>
                            <span>•</span>
                            <span className="font-mono">
                              Date: {ev.dateStr} at {String(ev.startHour).padStart(2, "0")}:{String(ev.startMinute).padStart(2, "0")}
                            </span>
                            <span>•</span>
                            <span>Duration: {ev.durationHours}h</span>
                          </div>
                        </div>
                      </div>

                      <div className="flex items-center gap-4 self-end sm:self-center">
                        <div className="text-right">
                          <div className="text-xs font-mono font-bold text-foreground">Est. {ev.powerKw} kW</div>
                          <div className="text-[11px] font-mono font-semibold text-emerald-600 dark:text-emerald-400">
                            {ev.savings}
                          </div>
                        </div>
                        <span
                          className={cn(
                            "px-2.5 py-1 rounded-full text-xs font-semibold inline-flex items-center gap-1.5",
                            ev.status === "Running"
                              ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                              : "bg-blue-500/15 text-blue-600 dark:text-blue-400"
                          )}
                        >
                          <span
                            className={cn(
                              "h-1.5 w-1.5 rounded-full",
                              ev.status === "Running" ? "bg-emerald-500 animate-pulse" : "bg-blue-500"
                            )}
                          />
                          {ev.status}
                        </span>
                      </div>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </main>
      </div>

      {/* 3. EVENT DETAILS MODAL (GOOGLE CALENDAR DIALOG STYLE) */}
      {selectedEvent && (
        <div className="fixed inset-0 z-50 flex items-center justify-center p-4 bg-black/60 backdrop-blur-xs animate-in fade-in duration-150">
          <div
            className="w-full max-w-lg rounded-2xl bg-card border border-border shadow-2xl overflow-hidden p-6 relative animate-in zoom-in-95 duration-150"
            onClick={(e) => e.stopPropagation()}
          >
            {/* Close button */}
            <button
              onClick={() => setSelectedEvent(null)}
              className="absolute top-4 right-4 h-8 w-8 rounded-full hover:bg-muted flex items-center justify-center text-muted-foreground hover:text-foreground"
            >
              <X className="h-4 w-4" />
            </button>

            {/* Header tag */}
            <div className="flex items-center gap-2 mb-2">
              <span className={cn("text-xs font-semibold px-2.5 py-0.5 rounded-full", selectedEvent.colorScheme.badge)}>
                {selectedEvent.type}
              </span>
              <span className="text-xs font-mono text-muted-foreground">{selectedEvent.id}</span>
              <span
                className={cn(
                  "ml-auto text-xs px-2 py-0.5 rounded font-semibold",
                  selectedEvent.status === "Running"
                    ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400"
                    : "bg-muted text-muted-foreground"
                )}
              >
                {selectedEvent.status}
              </span>
            </div>

            <h3 className="text-lg font-bold text-foreground">{selectedEvent.title}</h3>
            {selectedEvent.ownerName && <p className="mb-3 text-xs text-muted-foreground">{selectedEvent.mine ? "Your job" : `Submitted by ${selectedEvent.ownerName}`}</p>}

            <div className="space-y-3 py-2 text-xs">
              <div className="flex items-center gap-3 text-muted-foreground">
                <CalendarIcon className="h-4 w-4 text-blue-500 shrink-0" />
                {/* The run's own start and length, not the clicked segment's. A run crossing midnight
                    is drawn as one segment per day, so a segment's date and duration are the part in
                    that column - reporting those here would understate a multi-day run. */}
                <span className="font-medium text-foreground">
                  {formatMarketDateTime(selectedEvent.startUtc)} → {formatMarketDateTime(selectedEvent.endUtc)}
                  {" "}({runLengthHours(selectedEvent).toFixed(runLengthHours(selectedEvent) % 1 === 0 ? 0 : 1)} hours)
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <Server className="h-4 w-4 text-purple-500 shrink-0" />
                <span>
                  Hardware Cluster: <strong className="text-foreground">{selectedEvent.cluster}</strong>
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <Zap className="h-4 w-4 text-amber-500 shrink-0" />
                <span>
                  Estimated power: <strong className="text-foreground">{selectedEvent.powerKw} kW</strong>
                </span>
              </div>

              <div className="flex items-center gap-3 text-muted-foreground">
                <DollarSign className="h-4 w-4 text-emerald-500 shrink-0" />
                <span>
                  Projected savings:{" "}
                  <strong className="text-emerald-600 dark:text-emerald-400">{selectedEvent.savings}</strong>
                </span>
              </div>

              {selectedEvent.notes && (
                <div className="p-3 rounded-lg bg-muted/60 border border-border/80 text-muted-foreground mt-2">
                  <div className="font-semibold text-foreground text-[11px] mb-1">Schedule notes</div>
                  <p className="text-[11px] leading-relaxed">{selectedEvent.notes}</p>
                </div>
              )}
            </div>

            <div className="flex items-center justify-between gap-3 mt-6 pt-4 border-t border-border">
              {/* No delete: to withdraw work, revoke the plan on Approvals or delete the job. */}
              <p className="text-[11px] text-muted-foreground">
                {selectedEvent.mine === false ? `Owned by ${selectedEvent.ownerName ?? "another user"}. Only the owner can change its status.` : ""}
              </p>

              <div className="flex items-center gap-2">
                <Button
                  variant="outline"
                  size="sm"
                  onClick={() => setSelectedEvent(null)}
                  className="text-xs h-8"
                >
                  Close
                </Button>
                {selectedEvent.mine !== false && <Button
                  size="sm"
                  onClick={() => void handleUpdateStatus(selectedEvent, selectedEvent.status === "Running" ? "Scheduled" : "Running")}
                  disabled={saving}
                  className="text-xs h-8 bg-primary text-primary-foreground"
                >
                  {selectedEvent.status === "Running" ? "Mark scheduled" : "Mark running"}
                </Button>}
              </div>
            </div>
          </div>
        </div>
      )}


    </div>
  )
}

export default GoogleCalendarView
