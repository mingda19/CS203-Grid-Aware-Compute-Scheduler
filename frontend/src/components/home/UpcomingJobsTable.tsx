"use client"

import { jobStatus } from "@/lib/job-status"
import * as React from "react"
import Link from "next/link"
import { Badge } from "@/components/ui/badge"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table"
import { jobsApi, type ComputeJob } from "@/lib/api"
import { MARKET_ZONE_LABEL, formatMarketDateTime, parseIsoDate } from "@/lib/date-utils"
import { unwrap, useLoad } from "./use-load"

const ROWS = 5

/** Jobs whose deadline has not passed, soonest requested start first. */
function upcoming(jobs: ComputeJob[], now: number) {
  return jobs
    .filter((job) => parseIsoDate(job.deadline).getTime() >= now)
    .sort((a, b) => parseIsoDate(a.originalStartTime).getTime() - parseIsoDate(b.originalStartTime).getTime())
}


export function UpcomingJobsTable() {
  const jobs = useLoad("jobs", () => unwrap(jobsApi.list(), "Could not load jobs"))
  // The list is computed when the data arrives, so a rendered row never depends on the clock during render.
  const [now] = React.useState(() => Date.now())
  const rows = React.useMemo(() => (jobs.data ? upcoming(jobs.data, now) : []), [jobs.data, now])

  return (
    <Card className="min-w-0">
      <CardHeader className="flex flex-row items-center justify-between gap-3 space-y-0 p-4 pb-2 sm:p-5 sm:pb-2">
        <CardTitle className="min-w-0 truncate text-sm font-semibold leading-5">Upcoming jobs</CardTitle>
        <Button asChild variant="ghost" size="sm" className="h-8 shrink-0 px-2 text-xs">
          <Link href="/jobs">View all</Link>
        </Button>
      </CardHeader>
      <CardContent className="min-w-0 p-4 pt-0 sm:p-5 sm:pt-0">
        {jobs.loading ? (
          <div className="space-y-2" role="status" aria-label="Loading">
            {Array.from({ length: 3 }, (_, i) => <Skeleton key={i} className="h-8 w-full" />)}
          </div>
        ) : jobs.error ? (
          <div className="flex items-center gap-3">
            <span className="min-w-0 truncate text-sm leading-5 text-muted-foreground">Could not load jobs</span>
            <Button variant="outline" size="sm" className="h-7 shrink-0 px-2 text-xs" onClick={jobs.reload}>Retry</Button>
          </div>
        ) : rows.length === 0 ? (
          <div className="flex flex-col items-start gap-3">
            <p className="text-sm leading-5 text-muted-foreground">No upcoming jobs.</p>
            <Button asChild size="sm"><Link href="/jobs?new=1">Create job</Link></Button>
          </div>
        ) : (
          // The Table component scrolls sideways inside its own box, so the page never does.
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead className="h-10 w-2/5 px-3 text-xs leading-4">Job</TableHead>
                <TableHead className="h-10 whitespace-nowrap px-3 text-xs leading-4">Requested start ({MARKET_ZONE_LABEL})</TableHead>
                <TableHead className="h-10 whitespace-nowrap px-3 text-xs leading-4">Deadline ({MARKET_ZONE_LABEL})</TableHead>
                <TableHead className="h-10 px-3 text-xs leading-4">Status</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {rows.slice(0, ROWS).map((job) => (
                <TableRow key={job.id}>
                  <TableCell className="max-w-0 truncate px-3 py-2.5 text-sm leading-5" title={job.name}>{job.name}</TableCell>
                  <TableCell className="whitespace-nowrap px-3 py-2.5 text-sm leading-5 tabular-nums">{formatMarketDateTime(job.originalStartTime, false)}</TableCell>
                  <TableCell className="whitespace-nowrap px-3 py-2.5 text-sm leading-5 tabular-nums">{formatMarketDateTime(job.deadline, false)}</TableCell>
                  <TableCell className="px-3 py-2.5">
                    <Badge variant="secondary" className="whitespace-nowrap">{jobStatus(job.status).label}</Badge>
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        )}
      </CardContent>
    </Card>
  )
}
