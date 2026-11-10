"use client"

import { DecisionPanel } from "@/components/ui/efferd-dashboard-2-utils/decision-panel"

export default function ApprovalsPage() {
  return (
    <div id="approvals-root" className="space-y-6">
      {/* No visible title. The heading stays for screen readers. */}
      <h1 className="sr-only">Approvals</h1>
      {/* The decision history and the approved-schedule downloads are on the Audit page. */}
      <DecisionPanel />
    </div>
  )
}
