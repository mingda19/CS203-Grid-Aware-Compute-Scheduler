import type { DataFreshnessResponse, DatasetFreshness } from "./api"
import { formatRelativeAgo } from "./date-utils"

export function freshnessSummary(response: DataFreshnessResponse | null) {
  const datasets = response?.datasets ?? []
  if (!datasets.length || datasets.some(dataset => dataset.status === "DATABASE_UNAVAILABLE" || dataset.status === "MISSING")) {
    return { status: "unavailable" as const, label: "Unavailable" }
  }
  if (datasets.some(dataset => dataset.status === "STALE")) return { status: "cached" as const, label: "Stale" }
  if (datasets.every(dataset => dataset.status === "HEALTHY")) return { status: "live" as const, label: "Healthy" }
  return { status: "unavailable" as const, label: "Unavailable" }
}

/** The badge state of one dataset: the same mapping `freshnessSummary` applies to a whole response. */
export function datasetStatusType(status: DatasetFreshness["status"]) {
  if (status === "HEALTHY") return "live" as const
  if (status === "STALE") return "cached" as const
  return "unavailable" as const
}

/** What is old and by how much, for example "Daily ERCOT price updated 3h ago". Never a bare "Stale". */
export function datasetFreshnessText(dataset: DatasetFreshness, now = Date.now()): string {
  if (dataset.status === "MISSING") return `${dataset.label}: no data`
  if (dataset.status === "DATABASE_UNAVAILABLE") return `${dataset.label}: source unreachable`
  if (dataset.latestRecordTimestamp) {
    return `${dataset.label} updated ${formatRelativeAgo(dataset.latestRecordTimestamp, now)}`
  }
  return `${dataset.label}: update time unknown`
}
