"use client"

import { Suspense } from "react"
import { PlanList } from "@/components/approvals/PlanList"

export default function ApprovalsPage() {
  return (
    <div id="approvals-root">
      <Suspense>
        <PlanList />
      </Suspense>
    </div>
  )
}
