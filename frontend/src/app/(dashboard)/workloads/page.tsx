"use client"

import * as React from "react"
import Link from "next/link"
import { Calendar, Table, RefreshCw, BriefcaseBusiness, Trash2 } from "lucide-react"
import { GoogleCalendarView } from "@/components/schedule-calendar/GoogleCalendarView"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog"
import { workloadScheduleApi, type WorkloadSchedule } from "@/lib/api"
import { formatSgtDateTime } from "@/lib/date-utils"

export default function WorkloadsPage() {
  const [entries, setEntries] = React.useState<WorkloadSchedule[]>([])
  const [loading, setLoading] = React.useState(true)
  const [error, setError] = React.useState<string | null>(null)
  const [displayMode, setDisplayMode] = React.useState<"table" | "calendar">("table")
  const [refreshKey, setRefreshKey] = React.useState(0)
  const [scheduleToDelete, setScheduleToDelete] = React.useState<WorkloadSchedule | null>(null)
  const [deleting, setDeleting] = React.useState(false)

  React.useEffect(() => {
    let active = true
    workloadScheduleApi.list().then(response => {
      if (!active) return
      if (!response.success || !response.data) throw new Error(response.message || "Could not load schedules")
      setEntries(response.data)
      setError(null)
    }).catch(error => {
      if (active) setError(error instanceof Error ? error.message : "Could not load schedules")
    }).finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [refreshKey])

  const refresh = () => { setLoading(true); setError(null); setRefreshKey(key => key + 1) }

  const handleConfirmDelete = async () => {
    if (!scheduleToDelete) return
    setDeleting(true)
    setError(null)
    try {
      const response = await workloadScheduleApi.remove(scheduleToDelete.id)
      if (!response.success) throw new Error(response.message || "Unable to delete schedule")
      setEntries(prev => prev.filter(item => item.id !== scheduleToDelete.id))
      setRefreshKey(k => k + 1)
      setScheduleToDelete(null)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Unable to delete schedule")
    } finally {
      setDeleting(false)
    }
  }

  return <div id="workloads-root" className="space-y-6">
    <div className="flex flex-col gap-4 rounded-2xl border bg-card p-6 md:flex-row md:items-center md:justify-between">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Workload schedules</h1>
        <p className="mt-1 text-sm text-muted-foreground">Saved execution windows from approved recommendations. Generate a recommendation in Jobs, then approve and add it to the calendar.</p>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button variant="outline" onClick={() => setDisplayMode(mode => mode === "calendar" ? "table" : "calendar")}>
          {displayMode === "calendar" ? <Table className="mr-2 h-4 w-4" /> : <Calendar className="mr-2 h-4 w-4" />}
          {displayMode === "calendar" ? "Table view" : "Calendar view"}
        </Button>
        <Button variant="outline" onClick={refresh} disabled={loading}><RefreshCw className="mr-2 h-4 w-4" />Refresh</Button>
        <Button asChild variant="outline"><Link href="/jobs"><BriefcaseBusiness className="mr-2 h-4 w-4" />Compute jobs</Link></Button>
        <Button asChild><Link href="/approvals">Review approvals</Link></Button>
      </div>
    </div>
    {displayMode === "calendar" ? <GoogleCalendarView key={refreshKey} /> : <Card><CardContent className="pt-6">
      {loading ? <p role="status" className="py-12 text-center text-muted-foreground">Loading schedules…</p>
        : error ? <p role="alert" className="py-12 text-center text-destructive">{error} Use Refresh to retry.</p>
        : !entries.length ? <div className="space-y-3 py-12 text-center"><p>No workload schedules have been saved.</p><Button asChild><Link href="/approvals">Review and apply a recommendation</Link></Button></div>
        : <div className="overflow-x-auto"><table className="w-full text-left text-sm">
          <caption className="sr-only">Saved workload schedules in Singapore time</caption>
          <thead><tr>{["Job", "Cluster", "Start (SGT)", "End (SGT)", "Recorded status", "Projected savings", "Actions"].map(label => <th key={label} className="p-3">{label}</th>)}</tr></thead>
          <tbody>{entries.map(entry => <tr key={entry.id} className="border-t">
            <td className="p-3 font-medium">{entry.title}</td>
            <td className="p-3">{entry.cluster}</td>
            <td className="p-3">{formatSgtDateTime(entry.startUtc)}</td>
            <td className="p-3">{formatSgtDateTime(entry.endUtc)}</td>
            <td className="p-3">{entry.status}</td>
            <td className="p-3">{entry.savings ?? "Unavailable"}</td>
            <td className="p-3">
              <Button
                variant="ghost"
                size="icon"
                className="h-8 w-8 text-muted-foreground hover:text-destructive"
                aria-label={`Delete schedule for ${entry.title}`}
                onClick={() => setScheduleToDelete(entry)}
              >
                <Trash2 className="h-4 w-4 text-destructive" />
              </Button>
            </td>
          </tr>)}</tbody>
        </table></div>}
    </CardContent></Card>}

    {/* Confirmation Dialog Card */}
    <Dialog open={!!scheduleToDelete} onOpenChange={(open) => { if (!open && !deleting) setScheduleToDelete(null) }}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>Delete workload schedule</DialogTitle>
          <DialogDescription>
            Are you sure you want to remove the scheduled execution window for{" "}
            <strong className="text-foreground">{scheduleToDelete?.title}</strong>? This action cannot be undone.
          </DialogDescription>
        </DialogHeader>

        {scheduleToDelete && (
          <div className="rounded-lg border bg-muted/40 p-3 text-xs space-y-1.5 text-muted-foreground">
            <div className="flex justify-between">
              <span>Cluster:</span>
              <span className="font-medium text-foreground">{scheduleToDelete.cluster}</span>
            </div>
            <div className="flex justify-between">
              <span>Start (SGT):</span>
              <span className="font-medium text-foreground">{formatSgtDateTime(scheduleToDelete.startUtc)}</span>
            </div>
            <div className="flex justify-between">
              <span>End (SGT):</span>
              <span className="font-medium text-foreground">{formatSgtDateTime(scheduleToDelete.endUtc)}</span>
            </div>
          </div>
        )}

        <DialogFooter className="gap-2 sm:gap-0">
          <Button
            variant="outline"
            onClick={() => setScheduleToDelete(null)}
            disabled={deleting}
          >
            Cancel
          </Button>
          <Button
            variant="destructive"
            disabled={deleting}
            onClick={() => void handleConfirmDelete()}
          >
            {deleting ? "Deleting…" : "Delete schedule"}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  </div>
}
