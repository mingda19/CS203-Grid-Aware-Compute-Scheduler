"use client"

import * as React from "react"
import { ChevronDown } from "lucide-react"
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
  /** True while the quick tour holds the panel open. */
  open?: boolean
}

/**
 * The top-bar data status badge. It opens the shared facility (read only, an admin changes it on
 * the Admin page) and a list of all datasets with what is old and by how much.
 */
export function DataStatusPopover({ status, response, open = false }: DataStatusPopoverProps) {
  const [now, setNow] = React.useState(() => Date.now())
  // Fetched on each open, so a change an admin just made shows up. Null = not loaded or failed.
  const [facility, setFacility] = React.useState<FacilityConfig | null>(null)
  const [userOpen, setUserOpen] = React.useState(false)

  // State is set only in the callbacks, so the tour's effect below may call it.
  const load = React.useCallback(() => {
    facilityApi.get()
      .then((r) => setFacility(r.data ?? null))
      .catch(() => setFacility(null))
      .finally(() => setNow(Date.now()))
  }, [])

  // Radix calls onOpenChange only for the user's clicks, so an open by the tour loads here.
  // tourHeld stops Radix from moving the focus to the badge when the tour closes the panel.
  const tourHeld = React.useRef(false)
  React.useEffect(() => {
    if (!open) return
    tourHeld.current = true
    load()
  }, [open, load])

  if (status === null) return <Skeleton className="h-7 w-28 shrink-0 rounded-full" />

  const onOpenChange = (next: boolean) => {
    setUserOpen(next)
    if (next) load()
  }

  return (
    <Popover open={open || userOpen} onOpenChange={onOpenChange}>
      <PopoverTrigger asChild>
        <button
          type="button"
          aria-label="Facility and data status"
          title="Facility and data status"
          className="group shrink-0 rounded-full focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2 ring-offset-background"
        >
          <DataStatusBadge
            status={status}
            updatedAt={response?.checkedAt}
            showTooltip={false}
            // Below sm the badge is an icon: the label and age stay for screen readers.
            // The arrow and the darker border on hover and while open show that it opens a panel.
            className="cursor-pointer transition-colors group-hover:border-current group-data-[state=open]:border-current max-sm:px-1.5 [&>span]:max-sm:sr-only"
          >
            <ChevronDown className="h-3.5 w-3.5 shrink-0 transition-transform group-data-[state=open]:rotate-180" aria-hidden="true" />
          </DataStatusBadge>
        </button>
      </PopoverTrigger>
      <PopoverContent
        data-tour="data-status"
        align="end"
        collisionPadding={16}
        // The tour card keeps the focus while the tour holds the panel open.
        onOpenAutoFocus={open ? (e) => e.preventDefault() : undefined}
        onCloseAutoFocus={(e) => {
          if (!tourHeld.current) return
          tourHeld.current = false
          e.preventDefault()
        }}
        className="w-[26rem] max-w-[calc(100vw-2rem)] p-3"
      >
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
