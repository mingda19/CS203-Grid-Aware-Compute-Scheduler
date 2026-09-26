import { describe, expect, it } from "vitest"

import { mergeFreshnessNotifications } from "./notification-utils"

const staleNotification = {
  id: 1,
  dataset: "fuel_mix_hourly",
  message: "Hourly fuel mix has not received a new record for 180 minutes.",
  createdAt: "2026-09-27T05:00:00Z",
  read: false,
}

describe("mergeFreshnessNotifications", () => {
  it("does not duplicate the same outage across polling cycles", () => {
    const first = mergeFreshnessNotifications([], [staleNotification])
    const second = mergeFreshnessNotifications(first, [staleNotification])

    expect(second).toHaveLength(1)
    expect(second[0].id).toBe(1)
  })

  it("preserves a notification after it has been read locally", () => {
    const read = { ...staleNotification, read: true }

    expect(mergeFreshnessNotifications([read], [staleNotification])).toEqual([read])
  })

  it("keeps distinct dataset outages as separate notifications", () => {
    const secondNotification = { ...staleNotification, id: 2, dataset: "solar" }

    expect(mergeFreshnessNotifications([], [staleNotification, secondNotification])).toHaveLength(2)
  })
})
