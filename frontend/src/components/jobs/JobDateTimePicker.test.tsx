import * as React from "react"
import { renderToStaticMarkup } from "react-dom/server"
import { describe, it, expect } from "vitest"
import { currentMarketHourInput } from "../../lib/date-utils"
import { JobDateTimePicker } from "./JobDateTimePicker"

/**
 * A job cannot be run in an hour that has already passed, so the picker must never offer one. The
 * floor reaches the DOM as the native input's `min`, which is what these assert.
 *
 * The calendar popover's disabled days, and the hour carried by "Today", are only reachable after a
 * click. These tests render on the server, where the popover is closed, so that part of the floor is
 * covered by `currentMarketHourInput` below plus the backend's own rejection rather than here.
 */
const render = (props: Partial<React.ComponentProps<typeof JobDateTimePicker>> = {}) =>
  renderToStaticMarkup(
    <JobDateTimePicker id="start" value="" onChange={() => {}} {...props} />,
  )

// The date and the time are separate inputs: `min` is the earliest date, `timeMin` the earliest time on that date.
const minAttribute = (html: string) => html.match(/type="date"[^>]*?min="([^"]*)"/)?.[1]
const timeMin = (html: string) => html.match(/type="time"[^>]*?min="([^"]*)"/)?.[1]

describe("current market hour floor", () => {
  it("floors to the hour in market time, in both seasons", () => {
    // 15:30Z is 09:30 CST; 14:30Z is 09:30 CDT. Both floor to 09:00 local.
    expect(currentMarketHourInput("2026-11-04T15:30:00Z")).toBe("2026-11-04T09:00")
    expect(currentMarketHourInput("2026-07-14T14:30:00Z")).toBe("2026-07-14T09:00")
  })

  it("uses the market day, not the UTC day", () => {
    // 04:30Z on 04 Nov is still 22:30 on 03 Nov in Central time
    expect(currentMarketHourInput("2026-11-04T04:30:00Z")).toBe("2026-11-03T22:00")
  })

  it("always yields a datetime-local value the input will accept", () => {
    expect(currentMarketHourInput()).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:00$/)
  })
})

describe("job date-time picker lower bound", () => {
  it("floors to the current market hour when no minimum is given", () => {
    // The start picker passes no minDateTime, so without a floor of its own it offered past dates.
    expect(minAttribute(render())).toBe(currentMarketHourInput().slice(0, 10))
  })

  it("takes a later minimum when one is given", () => {
    // The deadline picker passes the chosen start, which is always at or after the current hour.
    const laterThanNow = "2099-01-01T08:00"
    const html = render({ minDateTime: laterThanNow, mode: "deadline" })
    expect(minAttribute(html)).toBe("2099-01-01")
    expect(timeMin(html)).toBe("08:00")
  })

  it("ignores a minimum that is itself in the past", () => {
    // A deadline picker bounded by an elapsed start must still refuse elapsed hours.
    expect(minAttribute(render({ minDateTime: "2020-01-01T08:00", mode: "deadline" })))
      .toBe(currentMarketHourInput().slice(0, 10))
  })

  it("keeps the floor on the hour, so a start on the hour is always reachable", () => {
    expect(timeMin(render())?.endsWith(":00")).toBe(true)
  })
})
