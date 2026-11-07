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

/** How old one dataset is, for example "3h ago". Says why when there is no age. Never a bare "Stale". */
export function datasetAgeText(dataset: DatasetFreshness, now = Date.now()): string {
  if (dataset.status === "MISSING") return "no data"
  if (dataset.status === "DATABASE_UNAVAILABLE") return "source unreachable"
  if (dataset.latestRecordTimestamp) return formatRelativeAgo(dataset.latestRecordTimestamp, now)
  return "update time unknown"
}

/** A full sentence for alerts, for example "Daily ERCOT price updated 3h ago". */
export function datasetFreshnessText(dataset: DatasetFreshness, now = Date.now()): string {
  const age = datasetAgeText(dataset, now)
  const hasAge = dataset.status !== "MISSING" && dataset.status !== "DATABASE_UNAVAILABLE" && dataset.latestRecordTimestamp
  return hasAge ? `${dataset.label} updated ${age}` : `${dataset.label}: ${age}`
}
