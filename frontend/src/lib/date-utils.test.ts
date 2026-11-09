import { describe, it, expect } from "vitest"
import {
  formatMarketDateTime,
  formatMarketTime,
  formatMarketDate,
  formatMarketChartTick,
  getMarketHoursMinutes,
  getMarketDateString,
  marketDateToUtcIso,
  marketWallTimeToUtc,
  marketZoneAbbrev,
  marketZoneDescriptor,
  formatRelativeAgo,
  formatMarketWindow,
  marketDateStringFromParts,
  addMarketDays,
  addMarketMonths,
  marketDayOfWeek,
  marketDaysInMonth,
  marketDateLabelAnchor,
} from "./date-utils"

// GACS serves ERCOT, so market local time is America/Chicago: CST (UTC-6) in
// winter, CDT (UTC-5) in summer. In 2026 DST runs 08 Mar to 01 Nov.
describe("market local time (ERCOT / America/Chicago) formatting", () => {
  it("formats datetime in CDT during summer", () => {
    // 14:30Z - 5h = 09:30 CDT
    const formatted = formatMarketDateTime("2026-10-10T14:30:00Z")
    expect(formatted).toBe("10 Oct 2026, 09:30 CDT")
  })

  it("formats datetime in CST during winter", () => {
    // 15:30Z - 6h = 09:30 CST. Same wall time as above, one hour further from UTC.
    const formatted = formatMarketDateTime("2026-11-04T15:30:00Z")
    expect(formatted).toBe("04 Nov 2026, 09:30 CST")
  })

  it("rolls back to the previous market day when UTC has already advanced", () => {
    // 2026-10-10T04:00:00Z is still 23:00 on 09 Oct in Central time
    expect(formatMarketDate("2026-10-10T04:00:00Z")).toBe("09 Oct 2026")
    expect(formatMarketTime("2026-10-10T04:00:00Z", true)).toBe("23:00 CDT")
    expect(formatMarketTime("2026-10-10T04:00:00Z", false)).toBe("23:00")
  })

  it("formats chart ticks in market time", () => {
    expect(formatMarketChartTick("2026-10-10T19:00:00Z", false)).toBe("14:00")
    expect(formatMarketChartTick("2026-10-10T19:00:00Z", true)).toContain("14:00")
    expect(formatMarketChartTick("2026-10-10T19:00:00Z", true)).toContain("10 Oct")
  })

  it("extracts market hours and minutes for calendar positioning", () => {
    const { hours, minutes } = getMarketHoursMinutes("2026-10-10T14:30:00Z")
    expect(hours).toBe(9)
    expect(minutes).toBe(30)

    // 04:00Z is 23:00 the previous market day — hour must not wrap to 0
    const late = getMarketHoursMinutes("2026-10-10T04:00:00Z")
    expect(late.hours).toBe(23)
    expect(late.minutes).toBe(0)
  })

  it("gets market date string as YYYY-MM-DD", () => {
    expect(getMarketDateString("2026-10-10T04:00:00Z")).toBe("2026-10-09")
    expect(getMarketDateString("2026-10-10T14:30:00Z")).toBe("2026-10-10")
  })

  it("formats a window range with the zone abbreviation on the end only", () => {
    expect(formatMarketWindow("2026-10-10T14:30:00Z", "2026-10-10T18:30:00Z"))
      .toBe("09:30 – 13:30 CDT")
  })

  it("reports relative age without reference to a zone", () => {
    expect(formatRelativeAgo(new Date().toISOString())).toBe("just now")
    expect(formatRelativeAgo("2026-10-10T14:30:00Z", Date.parse("2026-10-10T16:30:00Z"))).toBe("2h ago")
  })
})

describe("zone abbreviation derivation", () => {
  it("reports CST in winter and CDT in summer", () => {
    expect(marketZoneAbbrev("2026-11-04T15:30:00Z")).toBe("CST")
    expect(marketZoneAbbrev("2026-07-14T14:30:00Z")).toBe("CDT")
  })

  it("describes the zone with a long name and signed UTC offset", () => {
    expect(marketZoneDescriptor("2026-11-04T15:30:00Z")).toEqual({
      abbrev: "CST",
      fullName: "Central Standard Time",
      utcOffsetLabel: "-6:00",
    })
    expect(marketZoneDescriptor("2026-07-14T14:30:00Z")).toEqual({
      abbrev: "CDT",
      fullName: "Central Daylight Time",
      utcOffsetLabel: "-5:00",
    })
  })
})

// These are the cases a fixed hour offset cannot express. Central Time shifts twice
// a year, so day boundaries sent to the API land on different UTC hours by season.
describe("zone offset label precision", () => {
  it("ignores sub-second precision in the instant", () => {
    // Regression: the wall time read back from Intl has no millisecond field, so
    // differencing it against a ms-precision instant used to yield "-6.000167".
    expect(marketZoneDescriptor(Date.parse("2026-11-04T15:30:00.602Z")).utcOffsetLabel).toBe("-6:00")
    expect(marketZoneDescriptor(Date.parse("2026-11-04T15:30:00.999Z")).utcOffsetLabel).toBe("-6:00")
    expect(marketZoneDescriptor().utcOffsetLabel).toMatch(/^[+-]\d{1,2}:\d{2}$/)
  })
})

describe("market day boundaries -> UTC (DST aware)", () => {
  it("uses a -5h offset for a summer day", () => {
    expect(marketDateToUtcIso("2026-10-10", false)).toBe("2026-10-10T05:00:00.000Z")
    expect(marketDateToUtcIso("2026-10-10", true)).toBe("2026-10-11T04:59:59.000Z")
  })

  it("uses a -6h offset for a winter day", () => {
    expect(marketDateToUtcIso("2026-11-04", false)).toBe("2026-11-04T06:00:00.000Z")
    expect(marketDateToUtcIso("2026-11-04", true)).toBe("2026-11-05T05:59:59.000Z")
  })

  it("starts the spring-forward and autumn-fallback days on the pre-transition offset", () => {
    // 08 Mar 2026 begins in CST (-6); the switch happens at 02:00 local
    expect(marketDateToUtcIso("2026-03-08", false)).toBe("2026-03-08T06:00:00.000Z")
    // 01 Nov 2026 begins in CDT (-5); the switch happens at 02:00 local
    expect(marketDateToUtcIso("2026-11-01", false)).toBe("2026-11-01T05:00:00.000Z")
  })

  it("falls back to now for an unparseable date string", () => {
    expect(() => new Date(marketDateToUtcIso("not-a-date")).toISOString()).not.toThrow()
  })
})

describe("marketWallTimeToUtc DST boundary disambiguation", () => {
  it("round-trips ordinary wall times in both seasons", () => {
    expect(marketWallTimeToUtc(2026, 7, 14, 9, 30).toISOString()).toBe("2026-07-14T14:30:00.000Z")
    expect(marketWallTimeToUtc(2026, 12, 14, 9, 30).toISOString()).toBe("2026-12-14T15:30:00.000Z")
  })

  it("picks the first occurrence of a repeated autumn hour", () => {
    // 01:30 happens twice on 01 Nov 2026: 06:30Z as CDT, then 07:30Z as CST
    const resolved = marketWallTimeToUtc(2026, 11, 1, 1, 30)
    expect(resolved.toISOString()).toBe("2026-11-01T06:30:00.000Z")
    expect(marketZoneAbbrev(resolved)).toBe("CDT")
  })

  it("shifts a nonexistent spring-forward wall time past the gap", () => {
    // 02:30 does not exist on 08 Mar 2026; clocks jump 02:00 CST -> 03:00 CDT
    const resolved = marketWallTimeToUtc(2026, 3, 8, 2, 30)
    expect(resolved.toISOString()).toBe("2026-03-08T08:30:00.000Z")
    expect(formatMarketTime(resolved, true)).toBe("03:30 CDT")
  })

  it("keeps the hour either side of the spring-forward gap intact", () => {
    expect(formatMarketTime(marketWallTimeToUtc(2026, 3, 8, 1, 30), true)).toBe("01:30 CST")
    expect(formatMarketTime(marketWallTimeToUtc(2026, 3, 8, 3, 30), true)).toBe("03:30 CDT")
  })
})

describe("invalid input handling", () => {
  it("renders an em dash rather than Invalid Date", () => {
    expect(formatMarketDateTime("nonsense")).toBe("—")
    expect(formatMarketTime("nonsense")).toBe("—")
    expect(formatMarketDate("nonsense")).toBe("—")
    expect(formatMarketChartTick("nonsense")).toBe("")
    expect(getMarketDateString("nonsense")).toBe("")
    expect(getMarketHoursMinutes("nonsense")).toEqual({ hours: 0, minutes: 0 })
  })
})

describe("market-date-string calendar arithmetic (bug 5)", () => {
  it("normalizes out-of-range day/month components like Date rollover", () => {
    expect(marketDateStringFromParts(2026, 11, 0)).toBe("2026-10-31") // day 0 = last day of previous month
    expect(marketDateStringFromParts(2026, 13, 1)).toBe("2027-01-01") // month 13 rolls into next year
    expect(marketDateStringFromParts(2026, 2, 29)).toBe("2026-03-01") // 2026 is not a leap year
  })

  it("adds and subtracts whole days across month and year boundaries", () => {
    expect(addMarketDays("2026-11-04", 1)).toBe("2026-11-05")
    expect(addMarketDays("2026-11-04", -4)).toBe("2026-10-31")
    expect(addMarketDays("2026-12-31", 1)).toBe("2027-01-01")
  })

  it("adds and subtracts whole months", () => {
    expect(addMarketMonths("2026-11-04", 1)).toBe("2026-12-04")
    expect(addMarketMonths("2026-01-04", -1)).toBe("2025-12-04")
  })

  it("clamps the day at month end instead of skipping a month", () => {
    expect(addMarketMonths("2026-01-31", 1)).toBe("2026-02-28")
    expect(addMarketMonths("2028-01-31", 1)).toBe("2028-02-29")
    expect(addMarketMonths("2026-03-31", -1)).toBe("2026-02-28")
    expect(addMarketMonths("2026-12-31", 2)).toBe("2027-02-28")
  })

  it("reports day of week with no timezone resolution", () => {
    expect(marketDayOfWeek("2026-11-04")).toBe(3) // Wednesday
    expect(marketDayOfWeek("2026-11-08")).toBe(0) // Sunday
  })

  it("reports days in month, including February in a non-leap year", () => {
    expect(marketDaysInMonth(2026, 2)).toBe(28)
    expect(marketDaysInMonth(2026, 11)).toBe(30)
  })

  it("anchors a label Date so every real UTC offset reads back the same calendar day", () => {
    const anchor = marketDateLabelAnchor("2026-11-04")
    expect(anchor.getUTCFullYear()).toBe(2026)
    expect(anchor.getUTCMonth()).toBe(10) // 0-indexed
    expect(anchor.getUTCDate()).toBe(4)
    // getMarketDateString (America/Chicago, UTC-6 in November) must read back the
    // exact same day the anchor was built for - this is the invariant GoogleCalendarView
    // relies on to avoid the bug 5 off-by-one (a UTC-midnight anchor would fail this).
    expect(getMarketDateString(anchor)).toBe("2026-11-04")
  })
})
