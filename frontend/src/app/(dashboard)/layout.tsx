import * as React from "react"
import { AppShell } from "@/components/ui/efferd-dashboard-2-utils/app-shell"

export default function DashboardLayout({
  children,
}: {
  children: React.ReactNode
}) {
  return (
    <div
      className="bg-background text-foreground"
      data-efferd-dashboard-2="true"
    >
      <AppShell>{children}</AppShell>
    </div>
  )
}
