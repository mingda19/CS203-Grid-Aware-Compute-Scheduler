"use client"

import { ScheduleCalendar } from "@/components/schedule-calendar/ScheduleCalendar"

export default function SchedulePage() {
  return (
    <div id="workloads-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Schedule</h1>
      <ScheduleCalendar />
    </div>
  )
}
