"use client"

import * as React from "react"
import {
  FileText,
  Search,
  Download,
  CheckCircle2,
  AlertTriangle,
  XCircle,
  Eye,
  RefreshCw,
  ChevronLeft,
  ChevronRight,
  FilterX,
} from "lucide-react"
import { Card, CardContent, CardHeader } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
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

const toneClasses = {
  positive: "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30",
  negative: "bg-red-500/15 text-red-600 dark:text-red-400 border border-red-500/30",
  neutral: "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30",
}

function OutcomeBadge({ action }: { action: AuditActionType }) {
  const tone = outcomeTone(action)
  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold ${toneClasses[tone]}`}>
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
      <span className="font-semibold text-foreground">{title}</span>
      <pre className="p-3 rounded-lg bg-muted/60 border border-border text-[11px] font-mono overflow-x-auto max-h-72 whitespace-pre-wrap break-all text-foreground">
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

  const clearFilters = () => {
    setFilters(EMPTY_AUDIT_FILTERS)
    setAppliedFilters(EMPTY_AUDIT_FILTERS)
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

      {/* Toolbar */}
      <div className="flex flex-wrap items-center justify-end gap-2">
        <Button
          onClick={() => setReloadKey((k) => k + 1)}
          size="sm"
          variant="outline"
          className="gap-2"
          disabled={isLoading}
        >
          <RefreshCw className={`h-4 w-4 ${isLoading ? "animate-spin" : ""}`} /> Refresh
        </Button>
        <Button
          onClick={exportCsv}
          size="sm"
          variant="outline"
          className="gap-2"
          disabled={isExporting || totalElements === 0}
          title={totalElements > EXPORT_LIMIT ? `Exports the newest ${EXPORT_LIMIT} matching records` : undefined}
        >
          <Download className="h-4 w-4" /> {isExporting ? "Exporting…" : "Export CSV"}
        </Button>
      </div>

      {/* Filter and Table Card */}
      <Card>
        <CardHeader className="space-y-3">
          <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-[repeat(6,minmax(0,1fr))_auto] items-end">
            <label className="space-y-1 text-[11px] font-medium text-muted-foreground">
              From (UTC)
              <Input
                type="date"
                value={filters.fromDate}
                max={filters.toDate || undefined}
                onChange={(e) => updateImmediateFilter({ fromDate: e.target.value })}
                className="h-9 text-xs"
                aria-invalid={rangeInverted}
              />
            </label>
            <label className="space-y-1 text-[11px] font-medium text-muted-foreground">
              To (UTC, inclusive)
              <Input
                type="date"
                value={filters.toDate}
                min={filters.fromDate || undefined}
                onChange={(e) => updateImmediateFilter({ toDate: e.target.value })}
                className="h-9 text-xs"
                aria-invalid={rangeInverted}
              />
            </label>
            <label className="space-y-1 text-[11px] font-medium text-muted-foreground">
              Decision outcome
              <select
                value={filters.actionType}
                onChange={(e) => updateImmediateFilter({ actionType: e.target.value as AuditFilters["actionType"] })}
                className="h-9 w-full rounded-md border border-input bg-background px-3 py-1 text-xs text-foreground"
              >
                <option value="ALL">All outcomes</option>
                {AUDIT_ACTION_TYPES.map((action) => (
                  <option key={action} value={action}>
                    {AUDIT_ACTION_LABELS[action]}
                  </option>
                ))}
              </select>
            </label>
            <label className="space-y-1 text-[11px] font-medium text-muted-foreground">
              Target run ID
              <div className="relative">
                <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
                <Input
                  placeholder="e.g. 7f3c1e52"
                  value={filters.runId}
                  onChange={(e) => setFilters((prev) => ({ ...prev, runId: e.target.value }))}
                  className="pl-8 h-9 text-xs font-mono"
                />
              </div>
            </label>
            <label className="space-y-1 text-[11px] font-medium text-muted-foreground">
              Target job ID
              <div className="relative">
                <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
                <Input
                  placeholder="e.g. 101"
                  inputMode="numeric"
                  value={filters.jobId}
                  onChange={(e) => setFilters((prev) => ({ ...prev, jobId: e.target.value }))}
                  className="pl-8 h-9 text-xs font-mono"
                  aria-invalid={filters.jobId.trim() !== "" && parseJobId(filters.jobId) === undefined}
                />
              </div>
            </label>
            <Button
              variant="ghost"
              size="sm"
              className="h-9 gap-1 text-xs"
              onClick={clearFilters}
              disabled={!hasActiveFilters(filters)}
            >
              <FilterX className="h-3.5 w-3.5" /> Clear
            </Button>
          </div>
          <div className="flex items-center justify-between text-xs text-muted-foreground">
            {rangeInverted ? (
              <span className="text-red-600 dark:text-red-400">The end date must be on or after the start date.</span>
            ) : (
              <span>
                Showing <span className="font-semibold text-foreground">{firstRow}–{lastRow}</span> of{" "}
                <span className="font-semibold text-foreground">{totalElements}</span> records
              </span>
            )}
          </div>
        </CardHeader>
        <CardContent>
          {error && (
            <div className="mb-4 flex items-center justify-between gap-3 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-xs text-red-600 dark:text-red-400">
              <span className="flex items-center gap-2">
                <XCircle className="h-4 w-4" /> {error}
              </span>
              <Button variant="outline" size="sm" className="h-7 text-xs" onClick={() => setReloadKey((k) => k + 1)}>
                Retry
              </Button>
            </div>
          )}
          <div className="overflow-x-auto">
            <table className="w-full text-left text-sm">
              <thead>
                <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
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
                    <td className="py-3 pl-2 font-mono text-xs font-semibold text-foreground">
                      AUD-{log.id}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono whitespace-nowrap">
                      {formatAuditTimestamp(log.timestamp)}
                    </td>
                    <td className="py-3">
                      <OutcomeBadge action={log.actionType} />
                    </td>
                    <td className="py-3 text-xs text-muted-foreground font-mono" title={log.runId}>
                      {log.runId.length > 13 ? `${log.runId.slice(0, 13)}…` : log.runId}
                    </td>
                    <td className="py-3 text-xs text-muted-foreground max-w-xs truncate" title={log.operatorRationale}>
                      {log.operatorRationale}
                    </td>
                  </tr>
                ))}
                {!isLoading && !error && events.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-10 text-center text-xs text-muted-foreground">
                      {hasActiveFilters(appliedFilters)
                        ? "No audit events match these filters."
                        : "No audit events have been recorded yet."}
                    </td>
                  </tr>
                )}
                {isLoading && events.length === 0 && (
                  <tr>
                    <td colSpan={5} className="py-10 text-center text-xs text-muted-foreground">
                      <RefreshCw className="mx-auto mb-2 h-4 w-4 animate-spin text-emerald-500" />
                      Loading audit events…
                    </td>
                  </tr>
                )}
              </tbody>
            </table>
          </div>
          {totalPages > 1 && (
            <div className="mt-4 flex items-center justify-end gap-2 text-xs text-muted-foreground">
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
            </div>
          )}
        </CardContent>
      </Card>

      {/* Event Details Inspection Dialog */}
      <Dialog open={!!selectedEvent} onOpenChange={(open) => !open && setSelectedEvent(null)}>
        <DialogContent className="sm:max-w-2xl max-h-[90vh] overflow-y-auto">
          <DialogHeader>
            <DialogTitle className="flex items-center gap-2">
              <FileText className="h-4 w-4 text-emerald-500" />
              Audit Event Inspection: AUD-{selectedEvent?.id}
            </DialogTitle>
            <DialogDescription>
              Decision metadata and the schedule snapshot the decision was made on.
            </DialogDescription>
          </DialogHeader>
          {selectedEvent && (
            <div className="space-y-3 py-2 text-xs">
              <div className="grid grid-cols-2 gap-2 p-3 rounded-lg bg-muted/30 border border-border">
                <div>
                  <span className="text-muted-foreground">Operator:</span>{" "}
                  <span className="font-semibold text-foreground">{selectedEvent.actor}</span>
                </div>
                <div className="flex items-center gap-1">
                  <span className="text-muted-foreground">Decision:</span> <OutcomeBadge action={selectedEvent.actionType} />
                </div>
                <div>
                  <span className="text-muted-foreground">Timestamp:</span>{" "}
                  <span className="font-mono text-foreground">{formatAuditTimestamp(selectedEvent.timestamp)}</span>
                </div>
                <div className="truncate" title={selectedEvent.runId}>
                  <span className="text-muted-foreground">Run:</span>{" "}
                  <span className="font-mono text-foreground">{selectedEvent.runId}</span>
                </div>
              </div>

              <JsonBlock title="Decision Metadata" value={decisionMetadata(detail?.event ?? selectedEvent)} />

              {detailError ? (
                <div className="rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-red-600 dark:text-red-400">
                  Could not load the schedule snapshot: {detailError}
                </div>
              ) : !detail ? (
                <div className="flex items-center gap-2 text-muted-foreground">
                  <RefreshCw className="h-3.5 w-3.5 animate-spin" /> Loading schedule snapshot…
                </div>
              ) : (
                <>
                  <JsonBlock title="Schedule Snapshot" value={scheduleSnapshot(detail)} />
                  {!detail.run && (
                    <p className="text-muted-foreground">
                      No optimiser run record is stored for this run ID; only the schedule hash is available.
                    </p>
                  )}
                  {detail.scheduleHashMatches === false && (
                    <div role="alert" className="flex items-center gap-2 rounded-lg border border-red-500/30 bg-red-500/10 px-3 py-2 text-red-600 dark:text-red-400">
                      <AlertTriangle className="h-4 w-4 shrink-0" />
                      The stored schedule no longer matches the hash recorded with this decision.
                    </div>
                  )}
                  {detail.schedule ? (
                    <JsonBlock title="Stored Schedule" value={detail.schedule} />
                  ) : (
                    <p className="text-muted-foreground">{storedScheduleNote(detail)}</p>
                  )}
                </>
              )}

              {selectedEvent.actionType === "APPROVE" && (
                <div className="space-y-1">
                  <span className="font-semibold text-foreground">Approved Schedule Export</span>
                  <div className="flex flex-col gap-2 p-3 rounded-lg bg-muted/30 border border-border sm:flex-row sm:items-start sm:justify-between">
                    <p className="text-muted-foreground">
                      Downloads this run&apos;s schedule as CSV or JSON. The server releases it only while the
                      approval still stands: not withdrawn, not superseded, and still matching its jobs.
                    </p>
                    <ScheduleExportButtons
                      key={selectedEvent.id}
                      runId={selectedEvent.runId}
                      className="shrink-0"
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
