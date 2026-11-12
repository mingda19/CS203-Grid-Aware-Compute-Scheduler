"use client"

import * as React from "react"
import {
  Search,
  Download,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
} from "lucide-react"
import { Alert, AlertDescription } from "@/components/ui/alert"
import { Card, CardContent, CardHeader } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Skeleton } from "@/components/ui/skeleton"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { ScheduleExportButtons } from "@/components/schedule-export/schedule-export-buttons"
import {
  auditApi,
  getStoredUser,
  type AuditActionType,
  type AuditEvent,
  type AuditEventDetail,
} from "@/lib/api"
import {
  AUDIT_ACTION_LABELS,
  AUDIT_ACTION_TYPES,
  EMPTY_AUDIT_FILTERS,
  auditEventsToCsv,
  buildAuditQuery,
  decisionMetadata,
  formatAuditTimestamp,
  hasActiveFilters,
  isInvertedRange,
  outcomeTone,
  parseJobId,
  scheduleSnapshot,
  storedScheduleNote,
  type AuditFilters,
} from "@/lib/audit-utils"

const PAGE_SIZE = 20
const EXPORT_LIMIT = 2000
const TEXT_FILTER_DEBOUNCE_MS = 350
const FIELD_LABEL = "flex min-w-0 flex-col gap-2 text-xs leading-4 text-muted-foreground"

const toneClasses = {
  positive: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30",
  negative: "bg-red-500/15 text-red-600 dark:text-red-400 border border-red-500/30",
  neutral: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30",
}

function OutcomeBadge({ action }: { action: AuditActionType }) {
  const tone = outcomeTone(action)
  return (
    <span className={`inline-flex shrink-0 items-center gap-1 whitespace-nowrap px-2 py-0.5 rounded-full text-xs font-medium ${toneClasses[tone]}`}>
      {tone === "positive" ? (
        <CheckCircle2 className="h-3 w-3 text-emerald-500" />
      ) : tone === "negative" ? (
        <XCircle className="h-3 w-3 text-red-500" />
      ) : (
        <AlertTriangle className="h-3 w-3 text-amber-500" />
      )}
      {action}
    </span>
  )
}

function JsonBlock({ title, value }: { title: string; value: unknown }) {
  return (
    <div className="space-y-1">
      <h3 className="text-sm font-semibold leading-5">{title}</h3>
      <pre className="max-h-72 overflow-auto whitespace-pre-wrap break-all rounded-md bg-muted p-3 font-mono text-xs leading-4 text-foreground">
        {JSON.stringify(value, null, 2)}
      </pre>
    </div>
  )
}

export default function AuditPage() {
  const [filters, setFilters] = React.useState<AuditFilters>(EMPTY_AUDIT_FILTERS)
  // Text filters are applied after a pause in typing so each keystroke does not hit the API.
  const [appliedFilters, setAppliedFilters] = React.useState<AuditFilters>(EMPTY_AUDIT_FILTERS)
  const [page, setPage] = React.useState(0)
  const [events, setEvents] = React.useState<AuditEvent[]>([])
  const [totalElements, setTotalElements] = React.useState(0)
  const [totalPages, setTotalPages] = React.useState(0)
  // Key of the query whose response is currently shown; loading while it differs from the requested one.
  const [loadedKey, setLoadedKey] = React.useState<string | null>(null)
  const [error, setError] = React.useState<string | null>(null)
  const [isExporting, setIsExporting] = React.useState(false)
  const [reloadKey, setReloadKey] = React.useState(0)

  const [selectedEvent, setSelectedEvent] = React.useState<AuditEvent | null>(null)
  const [detail, setDetail] = React.useState<AuditEventDetail | null>(null)
  const [detailError, setDetailError] = React.useState<string | null>(null)
  const [viewerEmail, setViewerEmail] = React.useState<string | null>(null)

  const rangeInverted = isInvertedRange(filters)
  const appliedRangeInverted = isInvertedRange(appliedFilters)
  const queryKey = JSON.stringify([appliedFilters, page, reloadKey])
  const isLoading = !appliedRangeInverted && loadedKey !== queryKey

  React.useEffect(() => {
    const timer = setTimeout(() => {
      setAppliedFilters((prev) =>
        prev.operator === filters.operator && prev.runId === filters.runId && prev.jobId === filters.jobId
          ? prev
          : { ...prev, operator: filters.operator, runId: filters.runId, jobId: filters.jobId },
      )
      setPage(0)
    }, TEXT_FILTER_DEBOUNCE_MS)
    return () => clearTimeout(timer)
  }, [filters.operator, filters.runId, filters.jobId])

  const updateImmediateFilter = (patch: Partial<Pick<AuditFilters, "fromDate" | "toDate" | "actionType">>) => {
    setFilters((prev) => ({ ...prev, ...patch }))
    setAppliedFilters((prev) => ({ ...prev, ...patch }))
    setPage(0)
  }

  React.useEffect(() => {
    if (appliedRangeInverted) return
    let cancelled = false
    auditApi
      .list(buildAuditQuery(appliedFilters, page, PAGE_SIZE))
      .then((res) => {
        if (cancelled) return
        const data = res.data
        setEvents(data?.content ?? [])
        setTotalElements(data?.totalElements ?? 0)
        setTotalPages(data?.totalPages ?? 0)
        setError(null)
      })
      .catch((err: unknown) => {
        if (cancelled) return
        setEvents([])
        setTotalElements(0)
        setTotalPages(0)
        setError(err instanceof Error ? err.message : "Failed to load audit events")
      })
      .finally(() => {
        if (!cancelled) setLoadedKey(queryKey)
      })
    return () => {
      cancelled = true
    }
  }, [appliedFilters, appliedRangeInverted, page, queryKey])

  const openEvent = (event: AuditEvent) => {
    setViewerEmail(getStoredUser()?.email ?? null)
    setDetail(null)
    setDetailError(null)
    setSelectedEvent(event)
  }

  React.useEffect(() => {
    if (!selectedEvent) return
    let cancelled = false
    auditApi
      .get(selectedEvent.id)
      .then((res) => {
        if (!cancelled) setDetail(res.data ?? null)
      })
      .catch((err: unknown) => {
        if (!cancelled) setDetailError(err instanceof Error ? err.message : "Failed to load event detail")
      })
    return () => {
      cancelled = true
    }
  }, [selectedEvent])

  const exportCsv = async () => {
    setIsExporting(true)
    try {
      const res = await auditApi.list(buildAuditQuery(appliedFilters, 0, EXPORT_LIMIT))
      const csv = auditEventsToCsv(res.data?.content ?? [])
      const blob = new Blob([csv], { type: "text/csv;charset=utf-8" })
      const url = URL.createObjectURL(blob)
      const link = document.createElement("a")
      link.href = url
      link.download = `gacs_audit_log_${new Date().toISOString().slice(0, 10)}.csv`
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    } catch (err) {
      setError(err instanceof Error ? `Export failed: ${err.message}` : "Export failed")
    } finally {
      setIsExporting(false)
    }
  }

  const firstRow = totalElements === 0 ? 0 : page * PAGE_SIZE + 1
  const lastRow = page * PAGE_SIZE + events.length

  return (
    <div id="audit-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Audit</h1>

      {/* Toolbar: actions only. The filters live in the card, above the list they filter. */}
      <div className="flex items-center justify-end gap-2">
        <Button
          onClick={exportCsv}
          size="sm"
          variant="outline"
          disabled={isExporting || totalElements === 0}
          title={totalElements > EXPORT_LIMIT ? `Exports the newest ${EXPORT_LIMIT} matching records` : undefined}
        >
          <Download className="mr-2 size-4" /> {isExporting ? "Exporting…" : "Export CSV"}
        </Button>
        <Button
          variant="ghost"
          size="icon"
          className="size-9"
          aria-label="Refresh audit events"
          title="Refresh audit events"
          onClick={() => setReloadKey((k) => k + 1)}
          disabled={isLoading}
        >
          <RefreshCw className={`size-4 ${isLoading ? "animate-spin" : ""}`} />
        </Button>
      </div>

      {error && (
        <Alert variant="destructive" className="flex items-center justify-between gap-3">
          <AlertDescription className="min-w-0 break-words">{error}</AlertDescription>
          <Button variant="outline" size="sm" className="shrink-0" onClick={() => setReloadKey((k) => k + 1)}>
            Retry
          </Button>
        </Alert>
      )}

      {/* Filter and Table Card */}
      <Card>
        <CardHeader className="space-y-3 p-4 sm:p-5">
          <div className="grid items-end gap-3 sm:grid-cols-2 lg:grid-cols-5">
            <label className={FIELD_LABEL}>
              From (UTC)
              <Input
                type="date"
                value={filters.fromDate}
                max={filters.toDate || undefined}
                onChange={(e) => updateImmediateFilter({ fromDate: e.target.value })}
                className="h-9"
                aria-invalid={rangeInverted}
              />
            </label>
            <label className={FIELD_LABEL}>
              To (UTC, inclusive)
              <Input
                type="date"
                value={filters.toDate}
                min={filters.fromDate || undefined}
                onChange={(e) => updateImmediateFilter({ toDate: e.target.value })}
                className="h-9"
                aria-invalid={rangeInverted}
              />
            </label>
            <label className={FIELD_LABEL}>
              Decision
              <select
                value={filters.actionType}
                onChange={(e) => updateImmediateFilter({ actionType: e.target.value as AuditFilters["actionType"] })}
                className="flex h-9 w-full rounded-md border border-input bg-background px-3 text-sm text-foreground ring-offset-background focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring focus-visible:ring-offset-2"
              >
                <option value="ALL">All outcomes</option>
                {AUDIT_ACTION_TYPES.map((action) => (
                  <option key={action} value={action}>
                    {AUDIT_ACTION_LABELS[action]}
                  </option>
                ))}
              </select>
            </label>
            <label className={FIELD_LABEL}>
              Run ID
              <div className="relative">
                <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" aria-hidden="true" />
                <Input
                  placeholder="e.g. 7f3c1e52"
                  value={filters.runId}
                  onChange={(e) => setFilters((prev) => ({ ...prev, runId: e.target.value }))}
                  className="h-9 pl-8 font-mono"
                />
              </div>
            </label>
            <label className={FIELD_LABEL}>
              Job ID
              <div className="relative">
                <Search className="absolute left-2.5 top-2.5 size-4 text-muted-foreground" aria-hidden="true" />
                <Input
                  placeholder="e.g. 101"
                  inputMode="numeric"
                  value={filters.jobId}
                  onChange={(e) => setFilters((prev) => ({ ...prev, jobId: e.target.value }))}
                  className="h-9 pl-8 font-mono"
                  aria-invalid={filters.jobId.trim() !== "" && parseJobId(filters.jobId) === undefined}
                />
              </div>
            </label>
          </div>
          {rangeInverted && (
            <p className="flex items-center gap-2 text-xs leading-4 text-destructive-foreground">
              <AlertTriangle className="size-4 shrink-0" aria-hidden="true" />
              The end date must be on or after the start date.
            </p>
          )}
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-medium text-muted-foreground">
                  <th className="pb-3 pl-2">Event ID</th>
                  <th className="pb-3">Timestamp (UTC)</th>
                  <th className="pb-3">Decision</th>
                  <th className="pb-3">Target Run</th>
                  <th className="pb-3">Rationale</th>
                </tr>
              </thead>
              <tbody className={`divide-y divide-border/60 ${isLoading ? "opacity-50" : ""}`}>
                {events.map((log) => (
                  <tr key={log.id} className="hover:bg-muted/40 transition-colors">
                    <td className="py-3 pl-2 font-mono text-xs text-muted-foreground">
                      AUD-{log.id}
                    </td>
                    <td className="py-3 text-sm tabular-nums whitespace-nowrap">
                      {formatAuditTimestamp(log.timestamp)}
                    </td>
                    <td className="py-3">
                      <OutcomeBadge action={log.actionType} />
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono" title={log.runId}>
                      {log.runId.length > 13 ? `${log.runId.slice(0, 13)}…` : log.runId}
                    </td>
                    <td className="py-3 text-sm max-w-xs truncate" title={log.operatorRationale}>
                      {log.operatorRationale}
                    </td>
                  </tr>
                ))}
                {!isLoading && !error && events.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                      {hasActiveFilters(appliedFilters)
                        ? "No audit events match these filters."
                        : "No audit events have been recorded yet."}
                    </td>
                  </tr>
                )}
                {isLoading && events.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-10 text-center text-sm text-muted-foreground">
                      <RefreshCw className="mx-auto mb-2 h-4 w-4 animate-spin text-emerald-500" />
                      Loading audit events…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="mt-4 flex items-center gap-2 text-xs text-muted-foreground">
            <span className="mr-auto tabular-nums">
              {firstRow}–{lastRow} of {totalElements} records
            </span>
            {totalPages > 1 && (
              <>
              <span>
                Page {page + 1} of {totalPages}
              </span>
              <Button
                variant="outline"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={() => setPage((p) => Math.max(0, p - 1))}
                disabled={page === 0 || isLoading}
                aria-label="Previous page"
              >
                <ChevronLeft className="h-4 w-4" />
              </Button>
              <Button
                variant="outline"
                size="sm"
                className="h-8 w-8 p-0"
                onClick={() => setPage((p) => Math.min(totalPages - 1, p + 1))}
                disabled={page >= totalPages - 1 || isLoading}
                aria-label="Next page"
              >
                <ChevronRight className="h-4 w-4" />
              </Button>
              </>
            )}
          </div>
        </CardContent>
      </Card>

      {/* Event Details Inspection Dialog */}
      <Dialog open={!!selectedEvent} onOpenChange={(open) => !open && setSelectedEvent(null)}>
        <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-md">
          <DialogHeader>
            <DialogTitle className="leading-7">Audit event</DialogTitle>
            <DialogDescription>
              Decision metadata and the schedule snapshot the decision was made on.
            </DialogDescription>
          </DialogHeader>
          {selectedEvent && (
            <div className="space-y-4 text-sm">
              <dl className="grid grid-cols-[auto_1fr] gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">Decision</dt>
                <dd className="min-w-0"><OutcomeBadge action={selectedEvent.actionType} /></dd>
                <dt className="text-muted-foreground">Operator</dt>
                <dd className="min-w-0 break-words">{selectedEvent.actor}</dd>
                <dt className="text-muted-foreground">Time (UTC)</dt>
                <dd className="min-w-0 tabular-nums">{formatAuditTimestamp(selectedEvent.timestamp)}</dd>
                <dt className="text-muted-foreground">Event ID</dt>
                <dd className="min-w-0 break-all font-mono text-xs leading-5 text-muted-foreground">AUD-{selectedEvent.id}</dd>
                <dt className="text-muted-foreground">Run ID</dt>
                <dd className="min-w-0 break-all font-mono text-xs leading-5 text-muted-foreground">{selectedEvent.runId}</dd>
              </dl>

              <JsonBlock title="Decision metadata" value={decisionMetadata(detail?.event ?? selectedEvent)} />

              {detailError ? (
                <p className="text-muted-foreground">Could not load the schedule snapshot: {detailError}</p>
              ) : !detail ? (
                <div className="space-y-2" role="status" aria-label="Loading schedule snapshot">
                  <Skeleton className="h-5 w-32" />
                  <Skeleton className="h-24 w-full" />
                </div>
              ) : (
                <>
                  <JsonBlock title="Schedule snapshot" value={scheduleSnapshot(detail)} />
                  {!detail.run && (
                    <p className="text-muted-foreground">
                      No optimiser run record is stored for this run ID; only the schedule hash is available.
                    </p>
                  )}
                  {detail.scheduleHashMatches === false && (
                    <Alert variant="destructive">
                      <AlertTriangle className="size-4" />
                      <AlertDescription>
                        The stored schedule no longer matches the hash recorded with this decision.
                      </AlertDescription>
                    </Alert>
                  )}
                  {detail.schedule ? (
                    <JsonBlock title="Stored schedule" value={detail.schedule} />
                  ) : (
                    <p className="text-muted-foreground">{storedScheduleNote(detail)}</p>
                  )}
                </>
              )}

              {selectedEvent.actionType === "APPROVE" && (
                <div className="space-y-2 border-t pt-4">
                  <h3 className="text-sm font-semibold leading-5">Approved schedule export</h3>
                  <div className="flex flex-col gap-2">
                    <p className="text-xs leading-4 text-muted-foreground">
                      Downloads this run&apos;s schedule as CSV or JSON. The server releases it only while the
                      approval still stands: not withdrawn, not superseded, and still matching its jobs.
                    </p>
                    <ScheduleExportButtons
                      key={selectedEvent.id}
                      runId={selectedEvent.runId}
                      className="self-start"
                      unavailableReason={
                        viewerEmail && viewerEmail.toLowerCase() === selectedEvent.actor.toLowerCase()
                          ? undefined
                          : "Only the operator who owns this run can export it"
                      }
                    />
                  </div>
                </div>
              )}
            </div>
          )}
        </DialogContent>
      </Dialog>
    </div>
  )
}
