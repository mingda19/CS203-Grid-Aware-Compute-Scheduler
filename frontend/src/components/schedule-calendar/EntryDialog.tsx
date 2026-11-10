"use client"

import Link from "next/link"
import { Button } from "@/components/ui/button"
import { Badge } from "@/components/ui/badge"
import { Dialog, DialogContent, DialogDescription, DialogTitle } from "@/components/ui/dialog"
import { MARKET_ZONE_LABEL } from "@/lib/date-utils"
import { formatDayTime, formatLength, hasEnded, runLengthHours, type CalendarEvent } from "./calendar-model"

interface Props {
  event: CalendarEvent | null
  saving: boolean
  onClose: () => void
  onStatus: (event: CalendarEvent, status: CalendarEvent["status"]) => void
}

export function EntryDialog({ event, saving, onClose, onStatus }: Props) {
  const mine = event?.mine === true
  const canChangeStatus = event !== null && mine && !hasEnded(event)

  return (
    <Dialog open={event !== null} onOpenChange={(open) => { if (!open) onClose() }}>
      <DialogContent className="max-h-[90dvh] max-w-md overflow-y-auto">
        {event && (
          <>
            <div className="min-w-0 space-y-1.5 pr-6">
              <DialogTitle className="line-clamp-2 break-words leading-tight" title={event.title}>{event.title}</DialogTitle>
              <DialogDescription asChild>
                <div className="flex flex-wrap items-center gap-2">
                  <span>{mine ? "Your job" : `Submitted by ${event.ownerName ?? "another user"}`}</span>
                  <Badge variant="secondary">{event.status}</Badge>
                </div>
              </DialogDescription>
            </div>

            {/* The run's own start, end and length, not the clicked segment's: a run crossing
                midnight is drawn as one segment per day, so a segment would understate it. */}
            <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
              <dt className="text-muted-foreground">When</dt>
              <dd className="min-w-0">{formatDayTime(event.startUtc)} → {formatDayTime(event.endUtc)} {MARKET_ZONE_LABEL}</dd>
              <dt className="text-muted-foreground">Length</dt>
              <dd className="tabular-nums">{formatLength(runLengthHours(event))}</dd>
              <dt className="text-muted-foreground">Category</dt>
              <dd className="min-w-0 break-words">{event.type}</dd>
              {event.savings && (
                <>
                  <dt className="text-muted-foreground">Savings</dt>
                  <dd className="tabular-nums">{event.savings}</dd>
                </>
              )}
            </dl>

            <div className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2">
              <div className="flex items-center gap-4">
                {event.runId && (
                  <Button asChild variant="link" size="sm" className="h-auto p-0">
                    <Link href={`/approvals/${event.runId}`}>Open plan</Link>
                  </Button>
                )}
                {mine && event.jobId !== null && (
                  <Button asChild variant="link" size="sm" className="h-auto p-0">
                    <Link href={`/jobs?job=${event.jobId}`}>Open job</Link>
                  </Button>
                )}
              </div>
              {canChangeStatus && (
                <Button
                  variant="outline"
                  size="sm"
                  disabled={saving}
                  onClick={() => onStatus(event, event.status === "Running" ? "Scheduled" : "Running")}
                >
                  {event.status === "Running" ? "Mark as scheduled" : "Mark as running"}
                </Button>
              )}
            </div>
          </>
        )}
      </DialogContent>
    </Dialog>
  )
}
