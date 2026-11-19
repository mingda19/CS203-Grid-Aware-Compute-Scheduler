"use client"

import { useParams } from "next/navigation"
import { PlanPage } from "@/components/approvals/PlanPage"

export default function ApprovalPlanPage() {
  const { runId } = useParams<{ runId: string }>()
  return <PlanPage runId={runId} />
}
