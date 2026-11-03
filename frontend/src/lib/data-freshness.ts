import type { DataFreshnessResponse } from "./api"

export function freshnessSummary(response: DataFreshnessResponse | null) {
  const datasets = response?.datasets ?? []
  if (!datasets.length || datasets.some(dataset => dataset.status === "DATABASE_UNAVAILABLE" || dataset.status === "MISSING")) {
    return { status: "unavailable" as const, label: "Unavailable" }
  }
  if (datasets.some(dataset => dataset.status === "STALE")) return { status: "cached" as const, label: "Stale" }
  if (datasets.every(dataset => dataset.status === "HEALTHY")) return { status: "live" as const, label: "Healthy" }
  return { status: "unavailable" as const, label: "Unavailable" }
}
