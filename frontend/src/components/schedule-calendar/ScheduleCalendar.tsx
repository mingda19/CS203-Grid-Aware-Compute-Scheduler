"use client"

import * as React from "react"
import { CalendarDays, ChevronLeft, ChevronRight, RefreshCw, Search, User } from "lucide-react"
import { cn } from "@/lib/utils"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Card } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Toggle } from "@/components/ui/toggle"
import { Segmented } from "@/components/ui/segmented"
import { StatusBadge } from "@/components/ui/status-badge"
import { useIsMobile } from "@/components/ui/use-mobile"
import { workloadScheduleApi } from "@/lib/api"
import { entryStatus } from "@/lib/job-status"
import {
  addMarketDays, addMarketMonths, getMarketDateString, getMarketHoursMinutes, marketDateLabelAnchor, marketDayOfWeek, MARKET_ZONE_LABEL,
} from "@/lib/date-utils"
import {
  clampToGrid, collapseLanes, formatSegmentRange, hasEnded, layoutEvents, PX_PER_HOUR, scheduleToEvent, splitAcrossDays,
  type CalendarEvent,
} from "./calendar-model"
import { EntryDialog } from "./EntryDialog"

type View = "week" | "month" | "list"

const HOURS = Array.from({ length: 24 }, (_, i) => i)
const GRID_HEIGHT = 24 * PX_PER_HOUR
/** A block this tall has room for a second line. */
const TWO_LINE_PX = 40

/** inRange is false for the month grid's leading and trailing days from the neighbouring months. */
interface Day { dateStr: string; weekday: string; num: number; isToday: boolean; inRange: boolean }

// Day labels are noon-UTC anchors, never real instants, so they are read with UTC getters.
function makeDay(dateStr: string, todayStr: string, inRange = true): Day {
  const d = marketDateLabelAnchor(dateStr)
  return {
    dateStr,
    weekday: d.toLocaleDateString("en-US", { weekday: "short", timeZone: "UTC" }),
    num: d.getUTCDate(),
    isToday: dateStr === todayStr,
    inRange,
  }
}

function formatTitle(days: Day[], view: View, anchor: string): string {
  if (view === "month") {
    return marketDateLabelAnchor(anchor).toLocaleDateString("en-US", { month: "long", year: "numeric", timeZone: "UTC" })
  }
  const first = marketDateLabelAnchor(days[0].dateStr)
  const last = marketDateLabelAnchor(days[days.length - 1].dateStr)
  const month = (d: Date) => d.toLocaleDateString("en-US", { month: "short", timeZone: "UTC" })
  return month(first) === month(last)
    ? `${month(first)} ${first.getUTCDate()} – ${last.getUTCDate()}, ${first.getUTCFullYear()}`
    : `${month(first)} ${first.getUTCDate()} – ${month(last)} ${last.getUTCDate()}, ${last.getUTCFullYear()}`
}

function dayHeading(dateStr: string, todayStr: string): string {
  if (dateStr === todayStr) return "Today"
  if (dateStr === addMarketDays(todayStr, 1)) return "Tomorrow"
  return marketDateLabelAnchor(dateStr)
    .toLocaleDateString("en-GB", { weekday: "short", day: "numeric", month: "short", timeZone: "UTC" }).replace(",", "")
}

const edge = (mine: boolean | undefined) => (mine ? "border-primary" : "border-muted-foreground/70")

function describe(event: CalendarEvent): string {
  const who = event.mine ? "your job" : `submitted by ${event.ownerName ?? "another user"}`
  return `${event.title}, ${formatSegmentRange(event)}, ${who}, ${event.status}`
}

export function ScheduleCalendar() {
  const [anchor, setAnchor] = React.useState(() => getMarketDateString(new Date()))
  const [view, setView] = React.useState<View>("week")
  const [onlyMine, setOnlyMine] = React.useState(false)
  const [searchOpen, setSearchOpen] = React.useState(false)
  const [query, setQuery] = React.useState("")
  const [events, setEvents] = React.useState<CalendarEvent[]>([])
  const [loading, setLoading] = React.useState(true)
  const [saving, setSaving] = React.useState(false)
  const [error, setError] = React.useState<string | null>(null)
  const [selected, setSelected] = React.useState<CalendarEvent | null>(null)
  const [refreshKey, setRefreshKey] = React.useState(0)
  const [nowMs, setNowMs] = React.useState(() => Date.now())

  // A seven-column grid cannot fit a job title below md, so Week falls back to List there.
  const isMobile = useIsMobile()
  const effective: View = view === "week" && isMobile ? "list" : view

  React.useEffect(() => {
    const timer = setInterval(() => setNowMs(Date.now()), 60_000)
    return () => clearInterval(timer)
  }, [])

  React.useEffect(() => {
    let active = true
    workloadScheduleApi.list().then((response) => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Unable to load schedules")
      setEvents(response.data.map(scheduleToEvent))
      setError(null)
    }).catch((e) => {
      if (active) setError(e instanceof Error ? e.message : "Unable to load schedules")
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [refreshKey])

  const todayStr = getMarketDateString(nowMs)
  const days = React.useMemo(() => {
    if (effective === "month") {
      const first = `${anchor.slice(0, 8)}01`
      const start = addMarketDays(first, -marketDayOfWeek(first))
      return Array.from({ length: 42 }, (_, i) => {
        const dateStr = addMarketDays(start, i)
        return makeDay(dateStr, todayStr, dateStr.slice(0, 7) === anchor.slice(0, 7))
      })
    }
    const sunday = addMarketDays(anchor, -marketDayOfWeek(anchor))
    return Array.from({ length: 7 }, (_, i) => makeDay(addMarketDays(sunday, i), todayStr))
  }, [anchor, effective, todayStr])

  // Every run split into one segment per day it touches, so a run crossing midnight is drawn in each column.
  const segmentsByDay = React.useMemo(() => {
    const needle = query.trim().toLowerCase()
    const byDay = new Map<string, CalendarEvent[]>()
    for (const event of events) {
      if (onlyMine && !event.mine) continue
      if (needle && !`${event.title} ${event.ownerName ?? ""}`.toLowerCase().includes(needle)) continue
      for (const segment of splitAcrossDays(event)) {
        byDay.set(segment.dateStr, [...(byDay.get(segment.dateStr) ?? []), segment])
      }
    }
    const start = (e: CalendarEvent) => e.startHour * 60 + e.startMinute
    for (const segments of byDay.values()) segments.sort((a, b) => start(a) - start(b))
    return byDay
  }, [events, onlyMine, query])

  const move = (direction: 1 | -1) =>
    setAnchor(effective === "month" ? addMarketMonths(anchor, direction) : addMarketDays(anchor, 7 * direction))
  const searchRef = React.useRef<HTMLInputElement>(null)
  React.useEffect(() => { if (searchOpen) searchRef.current?.focus() }, [searchOpen])
  const closeSearch = () => { setSearchOpen(false); setQuery("") }
  const todayInView = days.some((d) => d.isToday)
  const scrollRef = React.useRef<HTMLDivElement>(null)
  const isGrid = effective === "week"
  React.useEffect(() => {
    if (!isGrid || !scrollRef.current) return
    const { hours } = getMarketHoursMinutes(Date.now())
    scrollRef.current.scrollTop = (todayInView ? Math.max(hours - 1, 0) : 7) * PX_PER_HOUR
  }, [isGrid, todayInView])

  const changeStatus = async (event: CalendarEvent, status: CalendarEvent["status"]) => {
    const id = Number(event.id)
    if (!Number.isInteger(id)) return
    setSaving(true)
    setError(null)
    try {
      const response = await workloadScheduleApi.updateStatus(id, status)
      if (!response.success || !response.data) throw new Error(response.message || "Unable to update schedule")
      const updated = scheduleToEvent(response.data)
      setEvents((prev) => prev.map((item) => (item.id === event.id ? updated : item)))
      setSelected(updated)
    } catch (e) {
      setError(e instanceof Error ? e.message : "Unable to update schedule")
    } finally {
      setSaving(false)
    }
  }

  const refresh = () => { setLoading(true); setError(null); setRefreshKey((k) => k + 1) }
  const columns = `3rem repeat(${days.length}, minmax(0, 1fr))`

  return (
    <Card className="isolate flex h-[calc(100dvh-6rem)] min-h-[480px] flex-col overflow-hidden sm:h-[calc(100dvh-7rem)] lg:h-[calc(100dvh-8rem)]">
      <header className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 border-b border-border px-3 py-2">
        <div className="flex min-w-0 flex-1 basis-72 items-center gap-1">
          <Button variant="ghost" size="icon" className="size-9 shrink-0" aria-label="Today" title="Today" onClick={() => setAnchor(todayStr)}>
            <CalendarDays className="size-4" />
          </Button>
          <Button variant="ghost" size="icon" className="size-9 shrink-0" aria-label="Previous" onClick={() => move(-1)}>
            <ChevronLeft className="size-4" />
          </Button>
          <Button variant="ghost" size="icon" className="size-9 shrink-0" aria-label="Next" onClick={() => move(1)}>
            <ChevronRight className="size-4" />
          </Button>
          {/* Hidden while searching below lg: the box needs the room, and the two must never touch. */}
          <h2 className={cn("min-w-0 truncate pr-3 text-base font-semibold", searchOpen && "hidden lg:block")}>{formatTitle(days, effective, anchor)}</h2>
          {/* Always mounted so it can slide out from the magnifier toward the left and back. */}
          <div
            aria-hidden={!searchOpen}
            className={cn(
              "ml-auto min-w-0 overflow-hidden transition-[width,opacity] duration-300 ease-out motion-reduce:transition-none",
              searchOpen ? "w-56 opacity-100 sm:w-72" : "w-0 opacity-0",
            )}
          >
            <Input
              ref={searchRef}
              type="search"
              tabIndex={searchOpen ? 0 : -1}
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              onKeyDown={(e) => { if (e.key === "Escape") closeSearch() }}
              placeholder="Search jobs or people"
              aria-label="Search jobs or people"
              className="h-9 focus-visible:ring-inset focus-visible:ring-offset-0"
            />
          </div>
        </div>
        <div className="flex shrink-0 items-center gap-2">
          <Toggle
            size="sm"
            pressed={searchOpen}
            onPressedChange={(open) => (open ? setSearchOpen(true) : closeSearch())}
            aria-label="Search"
            title="Search"
            className="px-0 data-[state=on]:bg-primary/15 data-[state=on]:text-foreground"
          >
            <Search />
          </Toggle>
          <Toggle
            size="sm"
            pressed={onlyMine}
            onPressedChange={setOnlyMine}
            aria-label="Only mine"
            title="Only mine"
            className="px-0 data-[state=on]:bg-primary/15 data-[state=on]:text-foreground"
          >
            <User />
          </Toggle>
          <Segmented<View>
            label="View"
            value={effective}
            onChange={setView}
            options={[
              ...(isMobile ? [] : [{ value: "week" as const, label: "Week" }]),
              { value: "month", label: "Month" },
              { value: "list", label: "List" },
            ]}
          />
          <Button variant="ghost" size="icon" className="size-9" aria-label="Refresh" onClick={refresh} disabled={loading}>
            <RefreshCw className={cn("size-4", loading && "animate-spin")} />
          </Button>
        </div>
      </header>

      {error && (
        <Alert variant="destructive" className="m-3 mb-0 flex w-auto items-center justify-between gap-3 py-2">
          <AlertDescription className="min-w-0 break-words">{error}</AlertDescription>
          <Button variant="outline" size="sm" className="shrink-0" onClick={refresh}>Retry</Button>
        </Alert>
      )}

      {isGrid ? (
        <div ref={scrollRef} className="relative min-h-0 flex-1 overflow-y-auto overflow-x-hidden">
          <div className="sticky top-0 z-20 grid border-b border-border bg-card" style={{ gridTemplateColumns: columns }}>
            <div className="px-2 py-2 text-right text-xs text-muted-foreground">{MARKET_ZONE_LABEL}</div>
            {days.map((day) => (
              <div key={day.dateStr} className="flex min-w-0 flex-col items-center gap-0.5 border-l border-border/60 py-1.5">
                <span className="text-xs text-muted-foreground">{day.weekday}</span>
                <span className={cn("grid size-7 place-items-center rounded-full text-sm tabular-nums", day.isToday && "bg-primary text-primary-foreground")}>
                  {day.num}
                </span>
              </div>
            ))}
          </div>

          <div className="relative" style={{ height: GRID_HEIGHT }}>
            {HOURS.slice(1).map((h) => (
              <div key={h} className="absolute inset-x-0 border-t border-border/60" style={{ top: h * PX_PER_HOUR }} />
            ))}
            <div className="absolute inset-0 grid" style={{ gridTemplateColumns: columns }}>
              <div className="relative">
                {HOURS.map((h) => (
                  <span
                    key={h}
                    className={cn("absolute right-2 text-xs tabular-nums text-muted-foreground", h > 0 && "-translate-y-1/2")}
                    style={{ top: h * PX_PER_HOUR }}
                  >
                    {String(h).padStart(2, "0")}:00
                  </span>
                ))}
              </div>
              {days.map((day) => (
                <DayColumn
                  key={day.dateStr}
                  day={day}
                  segments={segmentsByDay.get(day.dateStr) ?? []}
                  nowMs={nowMs}
                  onOpen={setSelected}
                  onOpenDay={() => { setAnchor(day.dateStr); setView("list") }}
                />
              ))}
            </div>
          </div>
        </div>
      ) : effective === "month" ? (
        <div className="relative flex min-h-0 flex-1 flex-col">
          <div className="grid grid-cols-7 border-b border-border text-center text-xs text-muted-foreground">
            {days.slice(0, 7).map((day) => <div key={day.dateStr} className="py-1.5">{day.weekday}</div>)}
          </div>
          <div className="grid min-h-0 flex-1 grid-cols-7 grid-rows-6">
            {days.map((day) => (
              <MonthCell
                key={day.dateStr}
                day={day}
                segments={segmentsByDay.get(day.dateStr) ?? []}
                onOpen={() => { setAnchor(day.dateStr); setView("week") }}
              />
            ))}
          </div>
        </div>
      ) : (
        <div className="min-h-0 flex-1 overflow-y-auto p-3">
          {days.map((day) => {
            const rows = segmentsByDay.get(day.dateStr) ?? []
            if (rows.length === 0) return null
            return (
              <section key={day.dateStr} className="mb-4">
                <h3 className="mb-1 text-sm font-semibold">{dayHeading(day.dateStr, todayStr)}</h3>
                <ul className="divide-y divide-border/60">
                  {rows.map((event) => (
                    <li key={event.segmentKey}>
                      <button
                        type="button"
                        aria-label={describe(event)}
                        onClick={() => setSelected(event)}
                        className={cn(
                          "flex w-full min-w-0 items-center gap-3 border-l-2 px-3 py-2 text-left text-sm hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
                          edge(event.mine),
                          hasEnded(event, nowMs) && "opacity-60",
                        )}
                      >
                        <span className="w-24 shrink-0 tabular-nums text-muted-foreground">{formatSegmentRange(event)}</span>
                        <span className="min-w-0 flex-1 truncate">
                          {event.title}
                          {!event.mine && event.ownerName && <span className="text-muted-foreground"> · {event.ownerName}</span>}
                        </span>
                        <StatusBadge {...entryStatus(event.status)} />
                      </button>
                    </li>
                  ))}
                </ul>
              </section>
            )
          })}
        </div>
      )}

      <EntryDialog event={selected} saving={saving} onClose={() => setSelected(null)} onStatus={(e, s) => void changeStatus(e, s)} />
    </Card>
  )
}

/** One day of the month grid. Titles from sm up, dots below it. Clicking opens that day's week. */
function MonthCell({ day, segments, onOpen }: { day: Day; segments: CalendarEvent[]; onOpen: () => void }) {
  // Two lines at most: both jobs, or one job and "+N more".
  const chips = segments.length > 2 ? segments.slice(0, 1) : segments
  return (
    <button
      type="button"
      onClick={onOpen}
      aria-label={`${marketDateLabelAnchor(day.dateStr).toLocaleDateString("en-US", { weekday: "long", month: "long", day: "numeric", timeZone: "UTC" })}, ${segments.length} ${segments.length === 1 ? "job" : "jobs"}`}
      className={cn(
        "flex min-h-0 min-w-0 flex-col items-center gap-1 overflow-hidden border-b border-l border-border/60 p-1 text-left hover:bg-muted/60 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-inset focus-visible:ring-ring sm:items-stretch",
        !day.inRange && "bg-muted/30 text-muted-foreground",
      )}
    >
      <span className={cn("grid size-6 shrink-0 place-items-center self-center rounded-full text-xs tabular-nums sm:self-start", day.isToday && "bg-primary text-primary-foreground")}>
        {day.num}
      </span>
      <span className="hidden min-w-0 flex-col gap-0.5 sm:flex">
        {chips.map((event) => (
          <span
            key={event.segmentKey}
            className={cn("truncate rounded-l-none rounded-r-sm border-l-2 px-1 text-xs text-foreground", edge(event.mine), event.mine ? "bg-primary/15" : "bg-muted-foreground/15")}
          >
            {event.title}
          </span>
        ))}
        {segments.length > 2 && <span className="truncate px-1 text-xs text-muted-foreground">+{segments.length - 1} more</span>}
      </span>
      <span className="flex gap-0.5 sm:hidden">
        {segments.slice(0, 3).map((event) => (
          <span key={event.segmentKey} className={cn("size-1.5 rounded-full", event.mine ? "bg-primary" : "bg-muted-foreground/70")} />
        ))}
      </span>
    </button>
  )
}

function DayColumn({ day, segments, nowMs, onOpen, onOpenDay }: {
  day: Day
  segments: CalendarEvent[]
  nowMs: number
  onOpen: (event: CalendarEvent) => void
  onOpenDay: () => void
}) {
  const laid = layoutEvents(segments)
  const { shown, more } = collapseLanes(laid)
  const place = (lane: number, laneCount: number) => ({
    left: `calc(${(lane * 100) / laneCount}% + 1px)`,
    width: `calc(${100 / laneCount}% - 2px)`,
  })
  const now = getMarketHoursMinutes(nowMs)

  return (
    <div className="relative min-w-0 border-l border-border/60">
      {shown.map(({ event, lane, laneCount }) => {
        const top = (event.startHour + event.startMinute / 60) * PX_PER_HOUR
        const height = clampToGrid(top, event.durationHours)
        const running = event.status === "Running" || event.status === "Throttled"
        return (
          <button
            key={event.segmentKey}
            type="button"
            title={event.title}
            aria-label={describe(event)}
            onClick={() => onOpen(event)}
            className={cn(
              "absolute flex flex-col overflow-hidden rounded-l-none rounded-r-md border-l-2 px-1.5 py-0.5 text-left text-xs focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring",
              edge(event.mine),
              event.mine ? "bg-primary/15 hover:bg-primary/25" : "bg-muted-foreground/15 hover:bg-muted-foreground/25",
              event.continuesBefore && "rounded-t-none",
              event.continuesAfter && "rounded-b-none",
              hasEnded(event, nowMs) && "opacity-60",
            )}
            style={{ top, height, ...place(lane, laneCount) }}
          >
            <span className="block w-full truncate font-medium text-foreground">
              {running && <span className="mr-1 inline-block size-1.5 rounded-full bg-primary align-middle" />}
              {event.title}
            </span>
            {height >= TWO_LINE_PX && (
              <span className="block w-full truncate tabular-nums text-muted-foreground">
                {formatSegmentRange(event)}
                {!event.mine && event.ownerName && ` · ${event.ownerName}`}
              </span>
            )}
          </button>
        )
      })}
      {more.map((block) => {
        const top = (block.startMin / 60) * PX_PER_HOUR
        return (
          <button
            key={`more-${block.group}`}
            type="button"
            aria-label={`${block.count} more jobs, show this week as a list`}
            onClick={onOpenDay}
            className="absolute flex items-start overflow-hidden rounded-l-none rounded-r-md border border-dashed border-muted-foreground/60 bg-muted-foreground/15 px-1.5 py-0.5 text-xs font-medium hover:bg-muted-foreground/25 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring"
            style={{ top, height: clampToGrid(top, (block.endMin - block.startMin) / 60), ...place(2, 3) }}
          >
            +{block.count}
          </button>
        )
      })}
      {day.isToday && (
        <div className="pointer-events-none absolute inset-x-0 z-10 h-px bg-primary" style={{ top: (now.hours + now.minutes / 60) * PX_PER_HOUR }}>
          <span className="absolute -left-1 -top-[3px] size-2 rounded-full bg-primary" />
        </div>
      )}
    </div>
  )
}
