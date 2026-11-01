"use client"

import * as React from "react"
import { Download } from "lucide-react"
import { Button } from "@/components/ui/button"
import { scheduleExportApi, type ScheduleExportFormat } from "@/lib/api"
import { saveBlob } from "@/lib/download-utils"

const FORMATS: ScheduleExportFormat[] = ["csv", "json"]

interface ScheduleExportButtonsProps {
  runId: string
  /** Why export is unavailable; disables the buttons and becomes their tooltip. */
  unavailableReason?: string
  className?: string
}

/**
 * CSV and JSON download buttons for an approved schedule run. The server's approval gate decides whether the
 * file is released; a refusal (not approved, stale or invalidated) is shown under the buttons.
 */
export function ScheduleExportButtons({ runId, unavailableReason, className }: ScheduleExportButtonsProps) {
  const [pending, setPending] = React.useState<ScheduleExportFormat | null>(null)
  const [error, setError] = React.useState<string | null>(null)

  const download = async (format: ScheduleExportFormat) => {
    setPending(format)
    setError(null)
    try {
      const file = await scheduleExportApi.download(runId, format)
      saveBlob(file.blob, file.filename)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Export failed")
    } finally {
      setPending(null)
    }
  }

  return (
    <div className={className}>
      <div className="flex items-center justify-end gap-1">
        {FORMATS.map((format) => (
          <Button
            key={format}
            type="button"
            variant="outline"
            size="sm"
            className="h-7 gap-1 px-2 text-[11px]"
            disabled={!!unavailableReason || pending !== null}
            title={unavailableReason ?? `Download schedule-run-${runId}.${format}`}
            onClick={() => download(format)}
          >
            <Download className={`h-3 w-3 ${pending === format ? "animate-pulse" : ""}`} />
            {format.toUpperCase()}
          </Button>
        ))}
      </div>
      {error && (
        <p role="alert" className="mt-1 max-w-xs text-right text-[11px] leading-snug text-red-600 dark:text-red-400">
          {error}
        </p>
      )}
    </div>
  )
}
