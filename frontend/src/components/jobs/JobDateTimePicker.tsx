"use client"

import * as React from "react"
import { Calendar as CalendarIcon, ChevronLeft, ChevronRight, X } from "lucide-react"
import { cn } from "@/lib/utils"
import { MARKET_TIMEZONE, currentMarketHourInput } from "@/lib/date-utils"

export interface JobDateTimePickerProps {
  id?: string
  value: string // format: YYYY-MM-DDTHH:mm
  onChange: (value: string) => void
  label?: string
  placeholder?: string
  /** Extra lower bound, format YYYY-MM-DDTHH:mm. The picker never allows a time before the
   *  current market hour regardless, so this only ever narrows further. */
  minDateTime?: string // format: YYYY-MM-DDTHH:mm
  mode?: "start" | "deadline"
  align?: "left" | "right"
  disabled?: boolean
  required?: boolean
  error?: boolean
}

function getNowMarketParts() {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: MARKET_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date())
  const p = (type: string) => parts.find((item) => item.type === type)?.value ?? "00"
  return {
    year: parseInt(p("year"), 10),
    month: parseInt(p("month"), 10),
    day: parseInt(p("day"), 10),
    hour: parseInt(p("hour"), 10),
    minute: parseInt(p("minute"), 10),
  }
}

function pad2(n: number): string {
  return n < 10 ? `0${n}` : `${n}`
}

export function JobDateTimePicker({
  id,
  value,
  onChange,
  minDateTime,
  mode = "start",
  align,
  disabled = false,
  required = false,
  error = false,
}: JobDateTimePickerProps) {
  const [open, setOpen] = React.useState(false)
  const containerRef = React.useRef<HTMLDivElement>(null)

  const nowMarket = React.useMemo(() => getNowMarketParts(), [])

  /**
   * The earliest value this picker will produce: the current market hour, on the hour, or
   * `minDateTime` when that is later. Work cannot be run in an hour that has already passed, so the
   * backend rejects a job starting before the current hour - offering one here would only produce a
   * form the operator cannot submit. Both strings are fixed-width YYYY-MM-DDTHH:mm, so comparing
   * them lexicographically is the same as comparing the instants.
   */
  const earliest = React.useMemo(() => {
    const nowFloor = currentMarketHourInput()
    return minDateTime && minDateTime > nowFloor ? minDateTime : nowFloor
  }, [minDateTime])

  const earliestDate = earliest.split("T")[0]

  /** `candidate`, or `earliest` when that is later. */
  const notBeforeEarliest = (candidate: string) => (candidate < earliest ? earliest : candidate)

  // Parse current value
  const parsed = React.useMemo(() => {
    if (!value || !value.includes("T")) return null
    const [d, t] = value.split("T")
    const [y, m, day] = d.split("-").map(Number)
    const [h, min] = t.split(":").map(Number)
    if (isNaN(y) || isNaN(m) || isNaN(day)) return null
    return { year: y, month: m, day, hour: isNaN(h) ? 0 : h, minute: isNaN(min) ? 0 : min }
  }, [value])

  const [viewYear, setViewYear] = React.useState<number>(() => parsed?.year ?? nowMarket.year)
  const [viewMonth, setViewMonth] = React.useState<number>(() => parsed?.month ?? nowMarket.month)

  const [previousValue, setPreviousValue] = React.useState(value)
  if (previousValue !== value) {
    setPreviousValue(value)
    if (parsed) {
      setViewYear(parsed.year)
      setViewMonth(parsed.month)
    }
  }

  // Close on click outside
  React.useEffect(() => {
    if (!open) return
    const handleClickOutside = (e: MouseEvent) => {
      if (containerRef.current && !containerRef.current.contains(e.target as Node)) {
        setOpen(false)
      }
    }
    document.addEventListener("mousedown", handleClickOutside)
    return () => document.removeEventListener("mousedown", handleClickOutside)
  }, [open])

  const daysInMonth = new Date(viewYear, viewMonth, 0).getDate()
  const firstDayOfWeek = new Date(viewYear, viewMonth - 1, 1).getDay()

  const prevMonth = () => {
    if (viewMonth === 1) {
      setViewMonth(12)
      setViewYear((y) => y - 1)
    } else {
      setViewMonth((m) => m - 1)
    }
  }

  const nextMonth = () => {
    if (viewMonth === 12) {
      setViewMonth(1)
      setViewYear((y) => y + 1)
    } else {
      setViewMonth((m) => m + 1)
    }
  }

  const monthNames = [
    "Jan", "Feb", "Mar", "Apr", "May", "Jun",
    "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
  ]

  const isDateDisabled = (day: number) => {
    const dateStr = `${viewYear}-${pad2(viewMonth)}-${pad2(day)}`
    return dateStr < earliestDate
  }

  const handleSelectDay = (day: number) => {
    // Carry the time already chosen, else the floor's own time. Never the next hour modulo 24, which
    // wrapped to 00:00 of the same day late in the evening and so pointed at the past.
    const currentHour = parsed ? parsed.hour : parseInt(earliest.slice(11, 13), 10)
    const currentMinute = parsed ? parsed.minute : 0
    const newDateStr = `${viewYear}-${pad2(viewMonth)}-${pad2(day)}T${pad2(currentHour)}:${pad2(currentMinute)}`
    onChange(notBeforeEarliest(newDateStr))
    setOpen(false)
  }

  const alignRight = align === "right" || mode === "deadline"

  return (
    <div className="relative w-full" ref={containerRef}>
      {/* Direct editable input with calendar toggle button */}
      <div className="relative flex items-center">
        <input
          id={id}
          name={id}
          type="datetime-local"
          step={mode === "start" ? "3600" : "60"}
          required={required}
          disabled={disabled}
          value={value}
          min={earliest}
          onChange={(e) => onChange(e.target.value)}
          className={cn(
            "flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 pr-16 text-sm ring-offset-background",
            "file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-muted-foreground",
            "focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2",
            "disabled:cursor-not-allowed disabled:opacity-50",
            "[&::-webkit-calendar-picker-indicator]:opacity-0 [&::-webkit-calendar-picker-indicator]:w-0",
            error && "border-destructive ring-destructive",
            open && "ring-1 ring-emerald-500 border-emerald-500"
          )}
        />

        <div className="absolute right-1.5 flex items-center gap-1">
          {value && !disabled && (
            <button
              type="button"
              onClick={() => onChange("")}
              title="Clear timing"
              className="p-1 text-muted-foreground hover:text-foreground rounded transition-colors"
            >
              <X className="h-3.5 w-3.5" />
            </button>
          )}

          <button
            type="button"
            disabled={disabled}
            onClick={() => setOpen(!open)}
            title="Toggle calendar dropdown"
            className={cn(
              "p-1.5 rounded-md text-emerald-600 dark:text-emerald-400 hover:bg-emerald-500/10 transition-colors",
              open && "bg-emerald-500/20"
            )}
          >
            <CalendarIcon className="h-4 w-4" />
          </button>
        </div>
      </div>

      {/* Pure Compact Calendar Dropdown */}
      {open && (
        <div
          className={cn(
            "absolute z-50 w-[280px] sm:w-[300px] rounded-lg border border-border bg-popover p-3 text-popover-foreground shadow-xl animate-in fade-in-0 zoom-in-95 top-full mt-1.5",
            alignRight ? "right-0" : "left-0"
          )}
        >
          {/* Month Header Navigation */}
          <div className="flex items-center justify-between mb-2">
            <button
              type="button"
              onClick={prevMonth}
              className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            >
              <ChevronLeft className="h-4 w-4" />
            </button>
            <div className="text-xs font-semibold">
              {monthNames[viewMonth - 1]} {viewYear}
            </div>
            <button
              type="button"
              onClick={nextMonth}
              className="rounded p-1 text-muted-foreground hover:bg-muted hover:text-foreground transition-colors"
            >
              <ChevronRight className="h-4 w-4" />
            </button>
          </div>

          {/* Calendar Grid */}
          <div className="grid grid-cols-7 gap-1 text-center text-xs mb-2">
            {["Su", "Mo", "Tu", "We", "Th", "Fr", "Sa"].map((day) => (
              <span key={day} className="text-[10px] font-medium text-muted-foreground py-0.5">
                {day}
              </span>
            ))}
            {Array.from({ length: firstDayOfWeek }).map((_, i) => (
              <div key={`empty-${i}`} />
            ))}
            {Array.from({ length: daysInMonth }, (_, i) => i + 1).map((day) => {
              const isSelected =
                parsed?.year === viewYear &&
                parsed?.month === viewMonth &&
                parsed?.day === day
              const isToday =
                nowMarket.year === viewYear &&
                nowMarket.month === viewMonth &&
                nowMarket.day === day
              const disabledDay = isDateDisabled(day)

              return (
                <button
                  key={day}
                  type="button"
                  disabled={disabledDay}
                  onClick={() => handleSelectDay(day)}
                  className={cn(
                    "h-6 w-6 sm:h-7 sm:w-7 mx-auto flex items-center justify-center rounded text-xs transition-all",
                    disabledDay && "cursor-not-allowed opacity-25 text-muted-foreground",
                    !disabledDay && !isSelected && "hover:bg-muted hover:text-foreground",
                    isToday && !isSelected && "border border-emerald-500/60 font-semibold text-emerald-600 dark:text-emerald-400",
                    isSelected && "bg-emerald-600 text-white font-bold shadow-xs"
                  )}
                >
                  {day}
                </button>
              )
            })}
          </div>

          {/* Clean Footer */}
          <div className="mt-2 pt-2 border-t border-border flex items-center justify-between text-[11px]">
            <button
              type="button"
              onClick={() => {
                const currentHour = parsed ? parsed.hour : parseInt(earliest.slice(11, 13), 10)
                const currentMinute = parsed ? parsed.minute : 0
                const todayStr = `${nowMarket.year}-${pad2(nowMarket.month)}-${pad2(nowMarket.day)}T${pad2(currentHour)}:${pad2(currentMinute)}`
                onChange(notBeforeEarliest(todayStr))
                setOpen(false)
              }}
              className="text-emerald-600 hover:text-emerald-700 font-medium"
            >
              Today
            </button>
            <button
              type="button"
              onClick={() => setOpen(false)}
              className="text-muted-foreground hover:text-foreground font-medium"
            >
              Close
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
