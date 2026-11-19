/**
 * Market-local time formatting utilities.
 *
 * GACS serves ERCOT, so every user-facing timestamp, chart axis, calendar slot
 * and audit entry renders in the market's local wall time. Timestamps cross the
 * wire and sit in Postgres as UTC; this module is the only place that converts
 * between UTC and market-local time.
 *
 * To serve a different ISO, change MARKET_TIMEZONE and MARKET_ZONE_LABEL. Nothing
 * else in the app names a time zone.
 *
 * Central Time observes DST (CST = UTC-6, CDT = UTC-5), so a fixed hour offset is
 * never correct. Conversions here resolve the offset for the specific instant.
 */

/** IANA zone for the market we serve. ERCOT settles in Texas / Central Time. */
export const MARKET_TIMEZONE = "America/Chicago"

/**
 * Season-independent label for static UI chrome (column headers, captions) where
 * no single timestamp is in scope. Use marketZoneAbbrev() when a value is in hand.
 */
export const MARKET_ZONE_LABEL = "CT"

/** Day-first locale: "04 Nov 2026, 09:30" rather than US "Nov 04, 2026". */
const DISPLAY_LOCALE = "en-GB"

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
 * Market zone abbreviation in effect at a given instant: "CST" in winter, "CDT"
 * in summer. Derived rather than hardcoded because Central Time switches twice a
 * year, so the correct label depends on the timestamp being displayed.
 */
export function marketZoneAbbrev(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return MARKET_ZONE_LABEL
    const part = new Intl.DateTimeFormat("en-US", {
      timeZone: MARKET_TIMEZONE,
      timeZoneName: "short",
    }).formatToParts(d).find((p) => p.type === "timeZoneName")
    return part?.value ?? MARKET_ZONE_LABEL
  } catch {
    return MARKET_ZONE_LABEL
  }
}

/**
 * Offset of the market zone from UTC, in milliseconds, at a given UTC instant.
 * Negative for Central Time (behind UTC).
 *
 * The formatted wall time has no millisecond field, so the instant is floored to a
 * whole second before differencing. Without that, any sub-second remainder in the
 * input leaks straight into the result (a `Date.now()` ending in .602 produced an
 * offset of -6.000167 hours).
 */
function marketOffsetMs(utcMs: number): number {
  const wholeSecond = Math.floor(utcMs / 1000) * 1000
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: MARKET_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    minute: "2-digit",
    second: "2-digit",
    hourCycle: "h23",
  }).formatToParts(new Date(wholeSecond))
  const field = (type: string) => Number(parts.find((p) => p.type === type)?.value)
  const wallAsUtc = Date.UTC(
    field("year"), field("month") - 1, field("day"),
    field("hour"), field("minute"), field("second"),
  )
  return wallAsUtc - wholeSecond
}

/**
 * Resolves market-local wall-clock fields to the UTC instant they name.
 *
 * Guess by treating the wall time as UTC, correct by the offset in effect there,
 * then confirm the correction by re-reading the offset at the candidate instant.
 * Most of the year both agree and the first candidate is the answer. DST days need
 * the extra step, and each boundary has a defined outcome:
 *
 * - Autumn fallback: 01:00-02:00 occurs twice. Returns the first (CDT) occurrence.
 * - Spring forward: 02:00-03:00 never occurs. No instant has that wall time, so the
 *   request is shifted forward past the gap (02:30 resolves to 03:30 CDT), matching
 *   the "compatible" disambiguation used by Temporal and java.time.
 */
export function marketWallTimeToUtc(
  year: number, month: number, day: number,
  hour = 0, minute = 0, second = 0,
): Date {
  const guess = Date.UTC(year, month - 1, day, hour, minute, second)
  const firstOffset = marketOffsetMs(guess)
  const candidate = guess - firstOffset
  const candidateOffset = marketOffsetMs(candidate)
  if (candidateOffset === firstOffset) return new Date(candidate)

  // The offsets disagree, so this is a transition day. Prefer the candidate that
  // actually round-trips to the requested wall time; if neither does, the wall time
  // falls in the spring-forward gap and `candidate` is the shifted-forward instant.
  const alternative = guess - candidateOffset
  if (marketOffsetMs(alternative) === candidateOffset) return new Date(alternative)
  return new Date(candidate)
}

/**
 * Formats date and time in market time: "04 Nov 2026, 09:30 CST"
 */
export function formatMarketDateTime(date: Date | string | number, includeZone = true): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    const formatter = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
      timeZone: MARKET_TIMEZONE,
      day: "2-digit",
      month: "short",
      year: "numeric",
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
    return includeZone ? `${formatter.format(d)} ${marketZoneAbbrev(d)}` : formatter.format(d)
  } catch {
    return "—"
  }
}

/**
 * Formats time only in market time: "09:30 CST" or "09:30"
 */
export function formatMarketTime(date: Date | string | number, includeZone = true): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    const formatter = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
      timeZone: MARKET_TIMEZONE,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    })
    const timeStr = formatter.format(d)
    return includeZone ? `${timeStr} ${marketZoneAbbrev(d)}` : timeStr
  } catch {
    return "—"
  }
}

/**
 * Formats date only in market time: "04 Nov 2026"
 */
export function formatMarketDate(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return "—"
    return new Intl.DateTimeFormat(DISPLAY_LOCALE, {
      timeZone: MARKET_TIMEZONE,
      day: "2-digit",
      month: "short",
      year: "numeric",
    }).format(d)
  } catch {
    return "—"
  }
}

/**
 * Formats short chart tick label in market time: "4 Nov 09:00" or "09:00"
 */
export function formatMarketChartTick(isoUtcString: string, showDate = false): string {
  try {
    const d = parseIsoDate(isoUtcString)
    if (isNaN(d.getTime())) return ""
    if (showDate) {
      return new Intl.DateTimeFormat(DISPLAY_LOCALE, {
        timeZone: MARKET_TIMEZONE,
        day: "numeric",
        month: "short",
        hour: "2-digit",
        minute: "2-digit",
        hour12: false,
      }).format(d)
    }
    return new Intl.DateTimeFormat(DISPLAY_LOCALE, {
      timeZone: MARKET_TIMEZONE,
      hour: "2-digit",
      minute: "2-digit",
      hour12: false,
    }).format(d)
  } catch {
    return ""
  }
}

/**
 * Extracts hours (0-23) and minutes (0-59) in market time for calendar slot placement
 */
export function getMarketHoursMinutes(date: Date | string | number): { hours: number; minutes: number } {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return { hours: 0, minutes: 0 }
    const parts = new Intl.DateTimeFormat("en-GB", {
      timeZone: MARKET_TIMEZONE,
      hour: "numeric",
      minute: "numeric",
      hourCycle: "h23",
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
 * Extracts YYYY-MM-DD date string in market time
 */
export function getMarketDateString(date: Date | string | number): string {
  try {
    const d = parseIsoDate(date)
    if (isNaN(d.getTime())) return ""
    return new Intl.DateTimeFormat("en-CA", {
      // en-CA produces YYYY-MM-DD
      timeZone: MARKET_TIMEZONE,
      year: "numeric",
      month: "2-digit",
      day: "2-digit",
    }).format(d)
  } catch {
    return ""
  }
}

/**
 * Converts a market-local date string (YYYY-MM-DD) to the UTC ISO instant at that
 * day's start or end, for API query filters.
 *
 * e.g. "2026-11-04" start of day = 00:00 CST = 2026-11-04T06:00:00Z, while
 * "2026-07-14" start of day = 00:00 CDT = 2026-07-14T05:00:00Z. The hour differs
 * by season, which is why this resolves the offset instead of subtracting one.
 */
export function marketDateToUtcIso(marketDateString: string, isEndOfDay = false): string {
  try {
    const [y, m, d] = marketDateString.split("-").map(Number)
    if (!y || !m || !d) return new Date().toISOString()
    const hour = isEndOfDay ? 23 : 0
    const minute = isEndOfDay ? 59 : 0
    const second = isEndOfDay ? 59 : 0
    return marketWallTimeToUtc(y, m, d, hour, minute, second).toISOString()
  } catch {
    return new Date().toISOString()
  }
}

/**
 * Formats relative time from a past timestamp in English: "just now", "32s ago", "4m ago".
 * Zone-independent — it compares two instants.
 */
export function formatRelativeAgo(date: Date | string | number, now = Date.now()): string {
  try {
    const d = parseIsoDate(date)
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

function pad2(n: number): string {
  return String(n).padStart(2, "0")
}

/**
 * Builds a YYYY-MM-DD string from year/month(1-12)/day components, normalizing
 * out-of-range values (day 0, day 32, month 13, negative day, ...) the same way
 * the `Date` constructor rolls over an overflowing field - e.g. month 13 becomes
 * January of the next year, day 0 becomes the last day of the previous month.
 * Pure calendar arithmetic: no timezone is resolved, so this is safe to use on a
 * date string that already came from {@link getMarketDateString} without
 * re-introducing the UTC-vs-local-day mismatch that produced bug 5's off-by-one
 * calendar highlight.
 */
export function marketDateStringFromParts(year: number, month: number, day: number): string {
  const d = new Date(Date.UTC(year, month - 1, day))
  return `${d.getUTCFullYear()}-${pad2(d.getUTCMonth() + 1)}-${pad2(d.getUTCDate())}`
}

/** Adds (or, for a negative count, subtracts) whole days to a YYYY-MM-DD market date string. */
export function addMarketDays(dateString: string, days: number): string {
  const [y, m, d] = dateString.split("-").map(Number)
  return marketDateStringFromParts(y, m, d + days)
}

/** Adds (or subtracts) whole months to a YYYY-MM-DD market date string. */
export function addMarketMonths(dateString: string, months: number): string {
  const [y, m, d] = dateString.split("-").map(Number)
  // Resolve the target month first, then clamp the day, so Oct 31 + 1 is Nov 30, not Dec 1.
  const [ty, tm] = marketDateStringFromParts(y, m + months, 1).split("-").map(Number)
  return marketDateStringFromParts(ty, tm, Math.min(d, marketDaysInMonth(ty, tm)))
}

/** Day of week (0 = Sunday, ... 6 = Saturday) for a YYYY-MM-DD market date string. */
export function marketDayOfWeek(dateString: string): number {
  const [y, m, d] = dateString.split("-").map(Number)
  return new Date(Date.UTC(y, m - 1, d)).getUTCDay()
}

/** Number of days in a given year/month (1-12), as a market calendar date would have. */
export function marketDaysInMonth(year: number, month: number): number {
  return new Date(Date.UTC(year, month, 0)).getUTCDate()
}

/**
 * A `Date` anchored at noon UTC on a market date string - never a real instant, just
 * a carrier for calendar display math (`.getUTCFullYear()`, `.getUTCMonth()`,
 * `.getUTCDate()`, `.getUTCDay()`, or `toLocaleDateString(locale, {..., timeZone:
 * "UTC"})`) that needs a `Date` object rather than a string. Noon keeps every real
 * UTC offset (-12 to +14) from crossing into an adjacent calendar day. Never read
 * this with a non-UTC getter/formatter, and never pass it to a market-timezone-aware
 * helper like {@link getMarketDateString} expecting it to resolve to something else -
 * it already IS the date those helpers would resolve to.
 */
export function marketDateLabelAnchor(dateString: string): Date {
  const [y, m, d] = dateString.split("-").map(Number)
  return new Date(Date.UTC(y, m - 1, d, 12))
}

/**
 * Formats a window range in market time: "09:30 – 13:30 CST"
 */
export function formatMarketWindow(startUtc: string, endUtc: string): string {
  try {
    const startTime = formatMarketTime(startUtc, false)
    const endTime = formatMarketTime(endUtc, true)
    return `${startTime} – ${endTime}`
  } catch {
    return `${startUtc} – ${endUtc}`
  }
}

/** What the market zone is called and offset by at a given instant. */
export interface MarketZoneDescriptor {
  /** "CST" or "CDT" */
  abbrev: string
  /** "Central Standard Time" or "Central Daylight Time" */
  fullName: string
  /** Signed offset from UTC as hours and minutes, e.g. "-6:00" or "-5:00" */
  utcOffsetLabel: string
}

/**
 * Describes the market zone as of an instant, for header chrome and tooltips.
 * All three fields shift with DST, so none of them can be a constant.
 */
export function marketZoneDescriptor(date: Date | string | number = Date.now()): MarketZoneDescriptor {
  const d = parseIsoDate(date)
  const valid = !isNaN(d.getTime())
  const instant = valid ? d : new Date()
  const longName = new Intl.DateTimeFormat("en-US", {
    timeZone: MARKET_TIMEZONE,
    timeZoneName: "long",
  }).formatToParts(instant).find((p) => p.type === "timeZoneName")?.value
  return {
    abbrev: marketZoneAbbrev(instant),
    fullName: longName ?? "Market local time",
    utcOffsetLabel: utcOffsetLabel(marketOffsetMs(instant.getTime())),
  }
}

/**
 * Formats a UTC offset as a signed "H:MM" label, e.g. "-6:00" for CST or "+5:30"
 * for a half-hour zone. Rounded to whole minutes, which every real zone offset is.
 */
function utcOffsetLabel(offsetMs: number): string {
  const totalMinutes = Math.round(offsetMs / 60_000)
  const sign = totalMinutes < 0 ? "-" : "+"
  const absMinutes = Math.abs(totalMinutes)
  const hours = Math.floor(absMinutes / 60)
  const minutes = absMinutes % 60
  return `${sign}${hours}:${String(minutes).padStart(2, "0")}`
}

/**
 * The current market hour as a `datetime-local` input value, "YYYY-MM-DDTHH:mm" with minutes at 00.
 *
 * This is the floor for any job start or deadline the operator can enter. Work cannot be run in an
 * hour that has already passed, so the backend rejects anything earlier (`JobService`) and the
 * optimiser will not place work there (`OptimizationProblem.earliestStart`). Exported so the picker
 * and the form's own validation share one definition rather than each deriving its own.
 */
export function currentMarketHourInput(now: Date | string | number = Date.now()): string {
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: MARKET_TIMEZONE,
    year: "numeric",
    month: "2-digit",
    day: "2-digit",
    hour: "2-digit",
    hourCycle: "h23",
  }).formatToParts(parseIsoDate(now))
  const field = (type: string) => parts.find((p) => p.type === type)?.value ?? "00"
  return `${field("year")}-${field("month")}-${field("day")}T${field("hour")}:00`
}

/**
 * The instant the current market hour began, as a `Date`.
 *
 * Truncates the instant to the UTC hour. Central Time's offsets are whole hours, so a market hour
 * starts on a UTC hour boundary, and truncating the instant is right even in the repeated hour when
 * Central Time falls back, which a wall-clock round trip through {@link marketWallTimeToUtc} would
 * resolve to the first occurrence, one hour too early. Callers get an instant, which is what a
 * comparison against a UTC timestamp from the API needs: job times travel as timezone-free strings
 * that mean UTC.
 *
 * This is the same boundary the optimiser enforces server-side (`OptimizationProblem.earliestStart`,
 * the current hour truncated), so "has this job's start passed?" gets the same answer on both sides.
 */
export function currentMarketHourStart(now: Date | string | number = Date.now()): Date {
  return new Date(Math.floor(parseIsoDate(now).getTime() / 36e5) * 36e5)
}

/**
 * The one short display format for a date and time in market time: "Wed 11 Nov, 09:00", 24-hour,
 * no zone (each surface labels the zone once). The year is added only when it is not the current
 * year, as "Thu 11 Nov 2027, 09:00". Full dates stay in tooltips (formatMarketDateTime).
 */
export function formatShortDateTime(date: Date | string | number, now: Date | string | number = Date.now()): string {
  const d = parseIsoDate(date)
  if (isNaN(d.getTime())) return "—"
  const year = (value: Date) => getMarketDateString(value).slice(0, 4)
  const day = new Intl.DateTimeFormat(DISPLAY_LOCALE, {
    timeZone: MARKET_TIMEZONE,
    weekday: "short",
    day: "numeric",
    month: "short",
    ...(year(d) !== year(parseIsoDate(now)) && { year: "numeric" as const }),
  }).format(d).replace(",", "")
  return `${day}, ${formatMarketTime(d, false)}`
}
