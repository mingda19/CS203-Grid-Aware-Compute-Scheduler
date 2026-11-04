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

const minAttribute = (html: string) => html.match(/min="([^"]*)"/)?.[1]

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
    expect(minAttribute(render())).toBe(currentMarketHourInput())
  })

  it("takes a later minimum when one is given", () => {
    // The deadline picker passes the chosen start, which is always at or after the current hour.
    const laterThanNow = "2099-01-01T08:00"
    expect(minAttribute(render({ minDateTime: laterThanNow, mode: "deadline" }))).toBe(laterThanNow)
  })

  it("ignores a minimum that is itself in the past", () => {
    // A deadline picker bounded by an elapsed start must still refuse elapsed hours.
    expect(minAttribute(render({ minDateTime: "2020-01-01T08:00", mode: "deadline" })))
      .toBe(currentMarketHourInput())
  })

  it("keeps the floor on the hour, so a start on the hour is always reachable", () => {
    expect(minAttribute(render())?.endsWith(":00")).toBe(true)
  })
})
