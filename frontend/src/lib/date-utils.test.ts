import { describe, it, expect } from "vitest"
import {
  formatSgtDateTime,
  formatSgtTime,
  formatSgtDate,
  formatSgtChartTick,
  getSgtHoursMinutes,
  getSgtDateString,
  sgtDateToUtcIso,
  formatSgtRelativeAgo,
  formatSgtWindow,
} from "./date-utils"

describe("Singapore Time (SGT / UTC+8) Utilities", () => {
  it("formats SGT datetime accurately with +8 hour offset", () => {
    // 2026-10-10T01:30:00Z UTC -> 2026-10-10 09:30 SGT
    const utcIso = "2026-10-10T01:30:00Z"
    const formatted = formatSgtDateTime(utcIso)
    expect(formatted).toContain("09:30 SGT")
    expect(formatted).toContain("10 Oct 2026")
  })

  it("handles midnight rollover from UTC to next day SGT", () => {
    // 2026-10-09T18:00:00Z UTC -> 2026-10-10 02:00 SGT
    const utcIso = "2026-10-09T18:00:00Z"
    const dateStr = formatSgtDate(utcIso)
    const timeStr = formatSgtTime(utcIso, true)
    expect(dateStr).toBe("10 Oct 2026")
    expect(timeStr).toBe("02:00 SGT")
  })

  it("formats chart tick accurately in SGT", () => {
    const utcIso = "2026-10-10T04:00:00Z"
    const tickTimeOnly = formatSgtChartTick(utcIso, false)
    expect(tickTimeOnly).toBe("12:00")

    const tickWithDate = formatSgtChartTick(utcIso, true)
    expect(tickWithDate).toContain("12:00")
    expect(tickWithDate).toContain("10 Oct")
  })

  it("extracts SGT hours and minutes correctly for calendar positioning", () => {
    // 2026-10-10T01:30:00Z is 09:30 SGT
    const { hours, minutes } = getSgtHoursMinutes("2026-10-10T01:30:00Z")
    expect(hours).toBe(9)
    expect(minutes).toBe(30)

    // 2026-10-09T17:00:00Z is 01:00 SGT
    const late = getSgtHoursMinutes("2026-10-09T17:00:00Z")
    expect(late.hours).toBe(1)
    expect(late.minutes).toBe(0)
  })

  it("gets SGT date string YYYY-MM-DD", () => {
    const dateStr = getSgtDateString("2026-10-09T17:00:00Z")
    // 17:00 UTC + 8h = 01:00 on 10 Oct
    expect(dateStr).toBe("2026-10-10")
  })

  it("converts SGT date string to UTC ISO boundaries", () => {
    const startUtc = sgtDateToUtcIso("2026-10-10", false)
    // 2026-10-10 00:00:00 SGT is 2026-10-09 16:00:00 UTC
    expect(new Date(startUtc).toISOString()).toBe("2026-10-09T16:00:00.000Z")

    const endUtc = sgtDateToUtcIso("2026-10-10", true)
    // 2026-10-10 23:59:59 SGT is 2026-10-10 15:59:59 UTC
    expect(new Date(endUtc).toISOString()).toBe("2026-10-10T15:59:59.000Z")
  })

  it("formats window range in SGT", () => {
    // 01:30 UTC -> 09:30 SGT, 05:30 UTC -> 13:30 SGT
    const windowStr = formatSgtWindow("2026-10-10T01:30:00Z", "2026-10-10T05:30:00Z")
    expect(windowStr).toBe("09:30 – 13:30 SGT")
  })

  it("handles relative time gracefully", () => {
    const nowIso = new Date().toISOString()
    expect(formatSgtRelativeAgo(nowIso)).toBe("just now")
  })
})
