"use client"

import { GoogleCalendarView } from "@/components/schedule-calendar/GoogleCalendarView"

export default function SchedulePage() {
  return (
    <div id="workloads-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Schedule</h1>
      <GoogleCalendarView />
    </div>
  )
}
