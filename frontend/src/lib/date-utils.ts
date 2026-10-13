/**
 * Singapore Time (SGT / UTC+8) Standardization Utilities
 *
 * Ensures all user-facing timestamps, chart axes, calendar slots,
 * and audit entries consistently render in Asia/Singapore (SGT).
 */

export const SGT_TIMEZONE = "Asia/Singapore"

export function parseIsoDate(input: Date | string | number): Date {
  if (input instanceof Date) return input
  if (typeof input === "string") {
    // Append 'Z' if raw UTC ISO timestamp lacks timezone designator
    const formatted = input.includes("T") && !input.endsWith("Z") && !input.includes("+") && !input.includes("-", 10)
      ? `${input}Z`
      : input
    return new Date(formatted)
  }
  return new Date(input)
}

/**
 * Formats date and time in SGT: "10 Oct 2026, 09:30 SGT"
 */
export function formatSgtDateTime(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    const formatter = new Intl.DateTimeFormat("en-SG", {
      timeZone: SGT_TIMEZONE,
      day: "2-digit",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
    return `${formatter.format(d)} SGT`
  } catch {
    return "—"
  }
}

/**
 * Formats time only in SGT: "09:30 SGT" or "09:30"
 */
export function formatSgtTime(date: Date | string | number, includeZone = true): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    const formatter = new Intl.DateTimeFormat("en-SG", {
      timeZone: SGT_TIMEZONE,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
    const timeStr = formatter.format(d)
    return includeZone ? `${timeStr} SGT` : timeStr
  } catch {
    return "—"
  }
}

/**
 * Formats date only in SGT: "10 Oct 2026"
 */
export function formatSgtDate(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    return new Intl.DateTimeFormat("en-SG", {
      timeZone: SGT_TIMEZONE,
      day: "2-digit",
      month: "short",
      year: "numeric",
    }).format(d)
  } catch {
    return "—"
  }
}

/**
 * Formats short chart tick label in SGT: "10 Oct 09:00" or "09:00"
 */
export function formatSgtChartTick(isoUtcString: string, showDate = false): string {
  try {
    const d = parseIsoDate(isoUtcString)
    if (isNaN(d.getTime())) return ""
    if (showDate) {
      return new Intl.DateTimeFormat("en-SG", {
        timeZone: SGT_TIMEZONE,
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(d)
    }
    return new Intl.DateTimeFormat("en-SG", {
      timeZone: SGT_TIMEZONE,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(d)
  } catch {
    return ""
  }
}

/**
 * Extracts hours (0-23) and minutes (0-59) in SGT for calendar slot placement
 */
export function getSgtHoursMinutes(date: Date | string | number): { hours: number; minutes: number } {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return { hours: 0, minutes: 0 }
    // Using Intl to reliably get SGT hour and minute
    const parts = new Intl.DateTimeFormat("en-SG", {
      timeZone: SGT_TIMEZONE,
      hour: "numeric",
      minute: "numeric",
      hour12: false,
    }).formatToParts(d)
    let hours = 0
    let minutes = 0
    for (const part of parts) {
      if (part.type === "hour") hours = parseInt(part.value, 10)
      if (part.type === "minute") minutes = parseInt(part.value, 10)
    }
    return { hours: hours % 24, minutes }
  } catch {
    return { hours: 0, minutes: 0 }
  }
}

/**
 * Extracts YYYY-MM-DD date string in SGT
 */
export function getSgtDateString(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return ""
    const parts = new Intl.DateTimeFormat("en-CA", {
      // en-CA produces YYYY-MM-DD
      timeZone: SGT_TIMEZONE,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(d)
    return parts
  } catch {
    return ""
  }
}

/**
 * Converts SGT date string (YYYY-MM-DD) to UTC ISO boundary string for API query filters
 * e.g., "2026-10-10" in SGT (start of day = 00:00 SGT = 2026-10-09T16:00:00Z)
 */
export function sgtDateToUtcIso(sgtDateString: string, isEndOfDay = false): string {
  try {
    const [y, m, d] = sgtDateString.split("-").map(Number)
    if (!y || !m || !d) return new Date().toISOString()
    // SGT is UTC+8. So midnight SGT is 16:00 UTC previous day (-8 hours).
    const hour = isEndOfDay ? 23 : 0
    const minute = isEndOfDay ? 59 : 0
    const second = isEndOfDay ? 59 : 0
    // Date.UTC with SGT - 8 hours
    const utcTimestamp = Date.UTC(y, m - 1, d, hour - 8, minute, second)
    return new Date(utcTimestamp).toISOString()
  } catch {
    return new Date().toISOString()
  }
}

/**
 * Formats relative time from a past timestamp in English: "just now", "32s ago", "4m ago"
 */
export function formatSgtRelativeAgo(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    const now = Date.now()
    const diffSec = Math.floor((now - d.getTime()) / 1000)
    if (isNaN(diffSec) || diffSec < 0) return "just now"
    if (diffSec < 10) return "just now"
    if (diffSec < 60) return `${diffSec}s ago`
    const diffMin = Math.floor(diffSec / 60)
    if (diffMin < 60) return `${diffMin}m ago`
    const diffHour = Math.floor(diffMin / 60)
    if (diffHour < 24) return `${diffHour}h ago`
    const diffDay = Math.floor(diffHour / 24)
    return `${diffDay}d ago`
  } catch {
    return "recently"
  }
}

/**
 * Formats a window range in SGT: "09:30 – 13:30 SGT"
 */
export function formatSgtWindow(startUtc: string, endUtc: string): string {
  try {
    const startTime = formatSgtTime(startUtc, false)
    const endTime = formatSgtTime(endUtc, true)
    return `${startTime} – ${endTime}`
  } catch {
    return `${startUtc} – ${endUtc}`
  }
}
