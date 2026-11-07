"use client"

import * as React from "react"
import type { DataFreshnessResponse } from "@/lib/api"
import { formatMarketDateTime } from "@/lib/date-utils"
import { datasetFreshnessText, datasetStatusType } from "@/lib/data-freshness"
import { DataStatusBadge, type DataStatusType } from "@/components/ui/data-status-badge"
import { Popover, PopoverContent, PopoverTrigger } from "@/components/ui/popover"
import { Skeleton } from "@/components/ui/skeleton"

interface DataStatusPopoverProps {
  /** Null until the first check finishes. */
  status: DataStatusType | null
  response: DataFreshnessResponse | null
}

/** The top-bar data status badge. It opens a list of all datasets with what is old and by how much. */
export function DataStatusPopover({ status, response }: DataStatusPopoverProps) {
  const [now, setNow] = React.useState(() => Date.now())

  if (status === null) return <Skeleton className="h-7 w-28 shrink-0 rounded-full" />

  return (
    <Popover onOpenChange={(open) => { if (open) setNow(Date.now()) }}>
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
      <PopoverContent align="end" collisionPadding={16} className="w-96 max-w-[calc(100vw-2rem)] p-3">
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
            {response.datasets.map((dataset) => {
              const text = datasetFreshnessText(dataset, now)
              return (
                <li key={dataset.dataset} className="flex items-center justify-between gap-3">
                  <span className="min-w-0 truncate text-sm leading-5" title={text}>{text}</span>
                  <DataStatusBadge size="sm" status={datasetStatusType(dataset.status)} showTooltip={false} />
                </li>
              )
            })}
          </ul>
        )}
      </PopoverContent>
    </Popover>
  )
}
