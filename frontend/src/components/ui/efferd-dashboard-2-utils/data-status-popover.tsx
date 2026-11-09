"use client"

import * as React from "react"
import { facilityApi, type DataFreshnessResponse, type FacilityConfig } from "@/lib/api"
import { formatMarketDateTime } from "@/lib/date-utils"
import { datasetAgeText, datasetStatusType } from "@/lib/data-freshness"
import { DataStatusBadge, type DataStatusType } from "@/components/ui/data-status-badge"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"

interface DataStatusPopoverProps {
  /** Null until the first check finishes. */
  status: DataStatusType | null
  response: DataFreshnessResponse | null
}

/**
 * The top-bar data status badge. It opens the shared facility (read only, an admin changes it on
 * the Admin page) and a list of all datasets with what is old and by how much.
 */
export function DataStatusPopover({ status, response }: DataStatusPopoverProps) {
  const [now, setNow] = React.useState(() => Date.now())
  // Fetched on each open, so a change an admin just made shows up. Null = not loaded or failed.
  const [facility, setFacility] = React.useState<FacilityConfig | null>(null)

  if (status === null) return <Skeleton className="h-7 w-28 shrink-0 rounded-full" />

  const onOpenChange = (open: boolean) => {
    if (!open) return
    setNow(Date.now())
    facilityApi.get().then((r) => setFacility(r.data ?? null)).catch(() => setFacility(null))
  }

  return (
    <Popover onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Data status details"
          className="shrink-0 rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 ring-offset-background"
        >
          <DataStatusBadge
            status={status}
            updatedAt={response?.checkedAt}
            showTooltip={false}
            // Below sm the badge is an icon: the label and age stay for screen readers.
            className="cursor-pointer max-sm:px-1.5 [&>span]:max-sm:sr-only"
          />
        </button>
      </PopoverTrigger>
      <PopoverContent align="end" collisionPadding={16} className="w-[26rem] max-w-[calc(100vw-2rem)] p-3">
        {facility && (
          <section className="mb-3 border-b pb-3">
            <h2 className="mb-2 truncate text-sm font-semibold leading-5" title={facility.name}>{facility.name}</h2>
            <dl className="grid grid-cols-3 gap-3 text-xs leading-4">
              {([
                ["ERCOT zone", facility.location],
                ["Max CPU per hour", `${facility.maxCpuHours} CPU-h`],
                ["Max GPU per hour", `${facility.maxGpuHours} GPU-h`],
              ] as const).map(([label, value]) => (
                <div key={label} className="min-w-0">
                  <dt className="truncate text-muted-foreground">{label}</dt>
                  <dd className="truncate font-mono font-semibold">{value}</dd>
                </div>
              ))}
            </dl>
          </section>
        )}
        <div className="mb-3 flex items-center justify-between gap-3">
          <h2 className="min-w-0 truncate text-sm font-semibold leading-5">Data status</h2>
          {response && (
            <span className="min-w-0 truncate text-xs leading-4 text-muted-foreground">
              Checked {formatMarketDateTime(response.checkedAt)}
            </span>
          )}
        </div>
        {!response?.datasets.length ? (
          <p role="status" className="text-sm leading-5 text-muted-foreground">Could not load data status.</p>
        ) : (
          <ul className="space-y-2">
            {response.datasets.map((dataset) => (
              <li key={dataset.dataset} className="grid grid-cols-[minmax(0,1fr)_6.5rem_auto] items-center gap-3">
                <span className="truncate text-sm leading-5" title={dataset.label}>{dataset.label}</span>
                <span className="truncate text-right text-xs leading-4 text-muted-foreground tabular-nums">
                  {datasetAgeText(dataset, now)}
                </span>
                <DataStatusBadge size="sm" status={datasetStatusType(dataset.status)} showTooltip={false} />
              </li>
            ))}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}
