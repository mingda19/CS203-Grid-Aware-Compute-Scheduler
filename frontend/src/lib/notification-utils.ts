import type { FreshnessNotification } from "./api"

export function mergeFreshnessNotifications(
  previous: FreshnessNotification[],
  incoming: FreshnessNotification[],
): FreshnessNotification[] {
  const byId = new Map(previous.map((notification) => [notification.id, notification]))
  incoming.forEach((notification) => {
    const existing = byId.get(notification.id)
    byId.set(notification.id, existing ? { ...notification, read: existing.read } : notification)
  })
  return Array.from(byId.values()).sort((a, b) => b.createdAt.localeCompare(a.createdAt))
}
