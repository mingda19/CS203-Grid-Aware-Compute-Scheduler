"use client"

import * as React from "react"
import {
  Search,
  Filter,
  ArrowUpDown,
  ArrowUp,
  ArrowDown,
  ChevronDown,
  ChevronRight,
  Play,
  Pause,
  Gauge,
  RotateCcw,
  Info,
  CheckCircle2,
  SlidersHorizontal,
  X,
  Server,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import type { Workload } from "@/lib/workload-data"
import {
  type WorkloadActionType,
  type WorkloadActionImpact,
  getWorkloadActionImpact,
  applyWorkloadAction,
} from "@/lib/workload-actions"
import { WorkloadActionDialog } from "./WorkloadActionDialog"
import { WorkloadRowExpansion } from "./WorkloadRowExpansion"

interface WorkloadQueueProps {
  workloads: Workload[]
  onWorkloadsChange: (updated: Workload[]) => void
}

type SortField = "name" | "type" | "power" | "deadline" | "savings" | "status"
type SortOrder = "asc" | "desc"

export function WorkloadQueue({ workloads, onWorkloadsChange }: WorkloadQueueProps) {
  // Search & Filter State
  const [searchQuery, setSearchQuery] = React.useState("")
  const [clusterFilter, setClusterFilter] = React.useState("ALL")
  const [statusFilter, setStatusFilter] = React.useState<string>("ALL")
  const [typeFilter, setTypeFilter] = React.useState("ALL")

  // Sorting State
  const [sortField, setSortField] = React.useState<SortField>("power")
  const [sortOrder, setSortOrder] = React.useState<SortOrder>("desc")

  // Expansion State
  const [expandedRowIds, setExpandedRowIds] = React.useState<Set<string>>(new Set())

  // Confirmation Modal State
  const [dialogImpact, setDialogImpact] = React.useState<WorkloadActionImpact | null>(null)
  const [dialogOpen, setDialogOpen] = React.useState(false)
  const [targetWorkload, setTargetWorkload] = React.useState<Workload | null>(null)

  // Status Notification Banner
  const [actionNotice, setActionNotice] = React.useState<string | null>(null)

  const clusters = React.useMemo(() => {
    return Array.from(new Set(workloads.map((w) => w.machineCluster)))
  }, [workloads])

  const types = React.useMemo(() => {
    return Array.from(new Set(workloads.map((w) => w.type)))
  }, [workloads])

  // Sorting Helper
  const handleSort = (field: SortField) => {
    if (sortField === field) {
      setSortOrder((prev) => (prev === "asc" ? "desc" : "asc"))
    } else {
      setSortField(field)
      setSortOrder("desc")
    }
  }

  // Row Expansion Toggle
  const toggleRowExpanded = (id: string) => {
    setExpandedRowIds((prev) => {
      const next = new Set(prev)
      if (next.has(id)) {
        next.delete(id)
      } else {
        next.add(id)
      }
      return next
    })
  }

  // Action Trigger (Opens Two-Step Confirmation)
  const triggerAction = (workload: Workload, action: WorkloadActionType) => {
    const impact = getWorkloadActionImpact(workload, action)
    setTargetWorkload(workload)
    setDialogImpact(impact)
    setDialogOpen(true)
  }

  // Action Confirmation Handler
  const handleConfirmAction = () => {
    if (!targetWorkload || !dialogImpact) return

    const updatedList = workloads.map((w) => {
      if (w.id === targetWorkload.id) {
        return applyWorkloadAction(w, dialogImpact.action)
      }
      return w
    })

    onWorkloadsChange(updatedList)

    const notice = `${dialogImpact.workloadId} (${dialogImpact.workloadName}) updated to ${dialogImpact.nextStatus}: ${
      dialogImpact.powerDeltaKw < 0
        ? `${Math.abs(dialogImpact.powerDeltaKw)} kW curbed from load.`
        : dialogImpact.powerDeltaKw > 0
        ? `+${dialogImpact.powerDeltaKw} kW dispatched.`
        : "Operational state synchronized."
    }`
    setActionNotice(notice)

    // Auto-dismiss notice after 6 seconds
    setTimeout(() => {
      setActionNotice((current) => (current === notice ? null : current))
    }, 6000)
  }

  // Parse numeric savings value from string "$1,840 (27%)"
  const parseSavings = (s: string) => {
    const match = s.match(/\$([\d,]+)/)
    if (!match) return 0
    return Number(match[1].replace(/,/g, ""))
  }

  // Filtered & Sorted Workloads
  const processedWorkloads = React.useMemo(() => {
    const filtered = workloads.filter((w) => {
      const q = searchQuery.toLowerCase().trim()
      const matchesSearch =
        q === "" ||
        w.name.toLowerCase().includes(q) ||
        w.id.toLowerCase().includes(q) ||
        w.machineCluster.toLowerCase().includes(q) ||
        w.type.toLowerCase().includes(q) ||
        (w.hardware && w.hardware.toLowerCase().includes(q))

      const matchesCluster = clusterFilter === "ALL" || w.machineCluster === clusterFilter
      const matchesStatus = statusFilter === "ALL" || w.status === statusFilter
      const matchesType = typeFilter === "ALL" || w.type === typeFilter

      return matchesSearch && matchesCluster && matchesStatus && matchesType
    })

    return filtered.sort((a, b) => {
      let cmp = 0
      switch (sortField) {
        case "name":
          cmp = a.name.localeCompare(b.name)
          break
        case "type":
          cmp = a.type.localeCompare(b.type)
          break
        case "power":
          cmp = (a.actualPowerKw ?? a.powerKw) - (b.actualPowerKw ?? b.powerKw)
          break
        case "deadline":
          cmp = a.deadline.localeCompare(b.deadline)
          break
        case "savings":
          cmp = parseSavings(a.savings) - parseSavings(b.savings)
          break
        case "status":
          cmp = a.status.localeCompare(b.status)
          break
      }
      return sortOrder === "asc" ? cmp : -cmp
    })
  }, [workloads, searchQuery, clusterFilter, statusFilter, typeFilter, sortField, sortOrder])

  const renderSortIndicator = (field: SortField) => {
    if (sortField !== field) {
      return <ArrowUpDown className="h-3 w-3 text-muted-foreground/60 inline ml-1" />
    }
    return sortOrder === "asc" ? (
      <ArrowUp className="h-3 w-3 text-emerald-500 inline ml-1 font-bold" />
    ) : (
      <ArrowDown className="h-3 w-3 text-emerald-500 inline ml-1 font-bold" />
    )
  }

  const renderStatusBadge = (status: Workload["status"]) => {
    switch (status) {
      case "Running":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-500 animate-pulse" />
            Running
          </span>
        )
      case "Throttled":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30">
            <span className="h-1.5 w-1.5 rounded-full bg-amber-500" />
            Throttled (50%)
          </span>
        )
      case "Paused":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-rose-500/15 text-rose-600 dark:text-rose-400 border border-rose-500/30">
            <span className="h-1.5 w-1.5 rounded-full bg-rose-500" />
            Paused
          </span>
        )
      case "Completed":
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-slate-500/15 text-slate-600 dark:text-slate-400 border border-slate-500/30">
            <span className="h-1.5 w-1.5 rounded-full bg-slate-500" />
            Completed
          </span>
        )
      default:
        return (
          <span className="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30">
            <span className="h-1.5 w-1.5 rounded-full bg-blue-500" />
            Scheduled
          </span>
        )
    }
  }

  return (
    <div className="space-y-4">
      {/* Action Notification Banner */}
      {actionNotice && (
        <div className="flex items-center justify-between p-3 rounded-xl border border-emerald-500/30 bg-emerald-500/10 text-emerald-800 dark:text-emerald-200 text-xs animate-in slide-in-from-top-2 duration-200">
          <div className="flex items-center gap-2">
            <CheckCircle2 className="h-4 w-4 text-emerald-500 shrink-0" />
            <span className="font-medium">{actionNotice}</span>
          </div>
          <button
            type="button"
            onClick={() => setActionNotice(null)}
            className="text-muted-foreground hover:text-foreground p-1"
          >
            <X className="h-3.5 w-3.5" />
            <span className="sr-only">Dismiss</span>
          </button>
        </div>
      )}

      {/* Filter Toolbar */}
      <div className="p-4 rounded-xl border border-border bg-card/80 space-y-3">
        <div className="flex flex-col sm:flex-row sm:items-center justify-between gap-3">
          <div className="relative flex-1 max-w-md">
            <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
            <Input
              placeholder="Search by job name, cluster, ID, or hardware..."
              value={searchQuery}
              onChange={(e) => setSearchQuery(e.target.value)}
              className="pl-8 h-9 text-xs"
            />
          </div>

          <div className="flex flex-wrap items-center gap-2">
            {/* Cluster Selector */}
            <select
              value={clusterFilter}
              onChange={(e) => setClusterFilter(e.target.value)}
              className="h-9 rounded-md border border-input bg-background px-2.5 py-1 text-xs text-foreground font-medium"
              aria-label="Filter by Cluster"
            >
              <option value="ALL">All Clusters</option>
              {clusters.map((c) => (
                <option key={c} value={c}>
                  {c}
                </option>
              ))}
            </select>

            {/* Type Selector */}
            <select
              value={typeFilter}
              onChange={(e) => setTypeFilter(e.target.value)}
              className="h-9 rounded-md border border-input bg-background px-2.5 py-1 text-xs text-foreground font-medium"
              aria-label="Filter by Type"
            >
              <option value="ALL">All Types</option>
              {types.map((t) => (
                <option key={t} value={t}>
                  {t}
                </option>
              ))}
            </select>

            {(searchQuery || clusterFilter !== "ALL" || statusFilter !== "ALL" || typeFilter !== "ALL") && (
              <Button
                variant="ghost"
                size="sm"
                onClick={() => {
                  setSearchQuery("")
                  setClusterFilter("ALL")
                  setStatusFilter("ALL")
                  setTypeFilter("ALL")
                }}
                className="h-9 text-xs text-muted-foreground hover:text-foreground"
              >
                Reset Filters
              </Button>
            )}
          </div>
        </div>

        {/* Status Filter Pills */}
        <div className="flex flex-wrap items-center gap-1.5 pt-2 border-t border-border/50 text-xs">
          <span className="text-muted-foreground font-medium mr-1 text-[11px]">Status:</span>
          {["ALL", "Running", "Throttled", "Scheduled", "Paused"].map((st) => (
            <button
              key={st}
              type="button"
              onClick={() => setStatusFilter(st)}
              className={cn(
                "px-2.5 py-1 rounded-lg text-xs font-medium transition-colors",
                statusFilter === st
                  ? "bg-primary text-primary-foreground shadow-xs font-semibold"
                  : "bg-muted/50 text-muted-foreground hover:bg-muted hover:text-foreground"
              )}
            >
              {st === "ALL" ? "All Workloads" : st}
            </button>
          ))}
          <div className="ml-auto text-[11px] text-muted-foreground">
            Showing <span className="font-semibold text-foreground">{processedWorkloads.length}</span> of{" "}
            {workloads.length} batches
          </div>
        </div>
      </div>

      {/* Desktop Table View (>= 768px) */}
      <div className="hidden md:block rounded-xl border border-border bg-card overflow-hidden shadow-xs">
        <div className="overflow-x-auto max-h-[640px] overflow-y-auto">
          <table className="w-full text-left text-sm border-collapse">
            <thead className="sticky top-0 bg-card/95 backdrop-blur-xs z-10 border-b border-border shadow-xs">
              <tr className="text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                <th className="py-3 pl-3 pr-2 w-8">
                  <span className="sr-only">Expand</span>
                </th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none"
                  onClick={() => handleSort("name")}
                >
                  Job Name & Cluster {renderSortIndicator("name")}
                </th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none"
                  onClick={() => handleSort("type")}
                >
                  Type {renderSortIndicator("type")}
                </th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none text-right"
                  onClick={() => handleSort("power")}
                >
                  Power Draw {renderSortIndicator("power")}
                </th>
                <th className="py-3 px-3">Scheduled Window</th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none"
                  onClick={() => handleSort("deadline")}
                >
                  Hard Deadline {renderSortIndicator("deadline")}
                </th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none text-right"
                  onClick={() => handleSort("savings")}
                >
                  Est. Savings {renderSortIndicator("savings")}
                </th>
                <th
                  className="py-3 px-3 cursor-pointer hover:text-foreground select-none"
                  onClick={() => handleSort("status")}
                >
                  Status {renderSortIndicator("status")}
                </th>
                <th className="py-3 pr-4 pl-3 text-right">Operational Actions</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-border/60">
              {processedWorkloads.length === 0 ? (
                <tr>
                  <td colSpan={9} className="py-12 text-center text-xs text-muted-foreground">
                    No workloads found matching the current filter criteria.
                  </td>
                </tr>
              ) : (
                processedWorkloads.map((item) => {
                  const isExpanded = expandedRowIds.has(item.id)
                  const currentKw = item.actualPowerKw ?? item.powerKw

                  return (
                    <React.Fragment key={item.id}>
                      <tr
                        className={cn(
                          "hover:bg-muted/40 transition-colors group",
                          isExpanded && "bg-muted/20"
                        )}
                      >
                        {/* Expand Chevron */}
                        <td className="py-3.5 pl-3 pr-1 text-center">
                          <button
                            type="button"
                            onClick={() => toggleRowExpanded(item.id)}
                            className="p-1 rounded-md text-muted-foreground hover:text-foreground hover:bg-muted/60 transition-colors"
                            aria-label={isExpanded ? "Collapse row details" : "Expand row details"}
                          >
                            {isExpanded ? (
                              <ChevronDown className="h-4 w-4 text-emerald-500" />
                            ) : (
                              <ChevronRight className="h-4 w-4" />
                            )}
                          </button>
                        </td>

                        {/* Job Name & Cluster */}
                        <td className="py-3.5 px-3">
                          <div className="flex items-center gap-2">
                            <span className="font-semibold text-foreground text-xs">{item.name}</span>
                            <span className="font-mono text-[10px] px-1.5 py-0.5 rounded-sm bg-muted text-muted-foreground font-semibold">
                              {item.id}
                            </span>
                          </div>
                          <div className="text-[11px] text-muted-foreground font-mono mt-0.5">
                            {item.machineCluster}
                          </div>
                        </td>

                        {/* Type */}
                        <td className="py-3.5 px-3">
                          <span className="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
                            {item.type}
                          </span>
                        </td>

                        {/* Power Draw */}
                        <td className="py-3.5 px-3 text-right font-mono">
                          <span className="font-semibold text-foreground text-xs">{currentKw} kW</span>
                          {item.actualPowerKw !== undefined && item.actualPowerKw !== item.powerKw && (
                            <span className="block text-[10px] text-muted-foreground line-through">
                              {item.powerKw} kW
                            </span>
                          )}
                        </td>

                        {/* Scheduled Window */}
                        <td className="py-3.5 px-3 font-mono text-xs text-foreground">
                          {item.scheduledWindow}
                        </td>

                        {/* Deadline */}
                        <td className="py-3.5 px-3 text-xs text-muted-foreground">
                          {item.deadline}
                        </td>

                        {/* Est Savings */}
                        <td className="py-3.5 px-3 text-right font-mono font-bold text-emerald-600 dark:text-emerald-400 text-xs">
                          {item.savings}
                        </td>

                        {/* Status */}
                        <td className="py-3.5 px-3 whitespace-nowrap">
                          {renderStatusBadge(item.status)}
                        </td>

                        {/* Operational Actions */}
                        <td className="py-3.5 pr-4 pl-3 text-right">
                          <div className="inline-flex items-center justify-end gap-1.5">
                            {item.status === "Running" && (
                              <>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => triggerAction(item, "throttle")}
                                  className="h-7 px-2 text-[11px] gap-1 text-amber-600 dark:text-amber-400 border-amber-500/30 hover:bg-amber-500/10"
                                >
                                  <Gauge className="h-3 w-3" />
                                  Throttle 50%
                                </Button>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => triggerAction(item, "pause")}
                                  className="h-7 px-2 text-[11px] gap-1 text-rose-600 dark:text-rose-400 border-rose-500/30 hover:bg-rose-500/10"
                                >
                                  <Pause className="h-3 w-3" />
                                  Pause
                                </Button>
                              </>
                            )}

                            {item.status === "Throttled" && (
                              <>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => triggerAction(item, "resume")}
                                  className="h-7 px-2 text-[11px] gap-1 text-emerald-600 dark:text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/10"
                                >
                                  <Play className="h-3 w-3" />
                                  Resume Full
                                </Button>
                                <Button
                                  variant="outline"
                                  size="sm"
                                  onClick={() => triggerAction(item, "pause")}
                                  className="h-7 px-2 text-[11px] gap-1 text-rose-600 dark:text-rose-400 border-rose-500/30 hover:bg-rose-500/10"
                                >
                                  <Pause className="h-3 w-3" />
                                  Pause
                                </Button>
                              </>
                            )}

                            {item.status === "Paused" && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => triggerAction(item, "resume")}
                                className="h-7 px-2 text-[11px] gap-1 text-emerald-600 dark:text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/10"
                              >
                                <Play className="h-3 w-3" />
                                Resume
                              </Button>
                            )}

                            {item.status === "Scheduled" && (
                              <Button
                                variant="outline"
                                size="sm"
                                onClick={() => triggerAction(item, "reschedule")}
                                className="h-7 px-2 text-[11px] gap-1 text-blue-600 dark:text-blue-400 border-blue-500/30 hover:bg-blue-500/10"
                              >
                                <RotateCcw className="h-3 w-3" />
                                Re-solve
                              </Button>
                            )}

                            <Button
                              variant="ghost"
                              size="sm"
                              onClick={() => toggleRowExpanded(item.id)}
                              className="h-7 px-2 text-[11px] text-muted-foreground hover:text-foreground"
                            >
                              <Info className="h-3.5 w-3.5" />
                              <span className="sr-only">Details</span>
                            </Button>
                          </div>
                        </td>
                      </tr>

                      {/* Row Expansion Telemetry Panel */}
                      {isExpanded && (
                        <tr>
                          <td colSpan={9} className="p-0">
                            <WorkloadRowExpansion workload={item} />
                          </td>
                        </tr>
                      )}
                    </React.Fragment>
                  )
                })
              )}
            </tbody>
          </table>
        </div>
      </div>

      {/* Mobile Card View (< 768px) */}
      <div className="block md:hidden space-y-3">
        {processedWorkloads.length === 0 ? (
          <div className="p-8 text-center text-xs text-muted-foreground rounded-xl border border-border bg-card">
            No workloads found matching the current filter criteria.
          </div>
        ) : (
          processedWorkloads.map((item) => {
            const isExpanded = expandedRowIds.has(item.id)
            const currentKw = item.actualPowerKw ?? item.powerKw

            return (
              <div
                key={item.id}
                className="rounded-xl border border-border bg-card p-4 space-y-3 shadow-xs"
              >
                {/* Header Row */}
                <div className="flex items-start justify-between gap-2">
                  <div className="space-y-1">
                    <div className="flex items-center gap-1.5 flex-wrap">
                      <span className="font-semibold text-sm text-foreground">{item.name}</span>
                      <span className="font-mono text-[10px] px-1.5 py-0.5 rounded-sm bg-muted text-muted-foreground font-semibold">
                        {item.id}
                      </span>
                    </div>
                    <p className="text-[11px] text-muted-foreground font-mono">{item.machineCluster}</p>
                  </div>
                  <div>{renderStatusBadge(item.status)}</div>
                </div>

                {/* Key Metrics Grid */}
                <div className="grid grid-cols-2 gap-2 p-2.5 rounded-lg bg-muted/40 text-xs">
                  <div>
                    <span className="text-[10px] text-muted-foreground block">Power Draw</span>
                    <span className="font-mono font-semibold text-foreground">{currentKw} kW</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-muted-foreground block">Est. Savings</span>
                    <span className="font-mono font-bold text-emerald-600 dark:text-emerald-400">
                      {item.savings}
                    </span>
                  </div>
                  <div>
                    <span className="text-[10px] text-muted-foreground block">Window</span>
                    <span className="font-mono text-[11px] text-foreground">{item.scheduledWindow}</span>
                  </div>
                  <div>
                    <span className="text-[10px] text-muted-foreground block">Deadline</span>
                    <span className="text-[11px] text-muted-foreground">{item.deadline}</span>
                  </div>
                </div>

                {/* Action Bar */}
                <div className="flex items-center justify-between gap-2 pt-1 border-t border-border/50">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={() => toggleRowExpanded(item.id)}
                    className="h-8 text-xs text-muted-foreground hover:text-foreground gap-1 px-2"
                  >
                    {isExpanded ? (
                      <ChevronDown className="h-3.5 w-3.5 text-emerald-500" />
                    ) : (
                      <ChevronRight className="h-3.5 w-3.5" />
                    )}
                    {isExpanded ? "Hide Details" : "Inspect Details"}
                  </Button>

                  <div className="flex items-center gap-1.5">
                    {item.status === "Running" && (
                      <>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => triggerAction(item, "throttle")}
                          className="h-8 px-2.5 text-xs text-amber-600 dark:text-amber-400 border-amber-500/30 hover:bg-amber-500/10 gap-1"
                        >
                          <Gauge className="h-3.5 w-3.5" />
                          Throttle
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => triggerAction(item, "pause")}
                          className="h-8 px-2.5 text-xs text-rose-600 dark:text-rose-400 border-rose-500/30 hover:bg-rose-500/10 gap-1"
                        >
                          <Pause className="h-3.5 w-3.5" />
                          Pause
                        </Button>
                      </>
                    )}

                    {item.status === "Throttled" && (
                      <>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => triggerAction(item, "resume")}
                          className="h-8 px-2.5 text-xs text-emerald-600 dark:text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/10 gap-1"
                        >
                          <Play className="h-3.5 w-3.5" />
                          Resume
                        </Button>
                        <Button
                          variant="outline"
                          size="sm"
                          onClick={() => triggerAction(item, "pause")}
                          className="h-8 px-2.5 text-xs text-rose-600 dark:text-rose-400 border-rose-500/30 hover:bg-rose-500/10 gap-1"
                        >
                          <Pause className="h-3.5 w-3.5" />
                          Pause
                        </Button>
                      </>
                    )}

                    {item.status === "Paused" && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => triggerAction(item, "resume")}
                        className="h-8 px-2.5 text-xs text-emerald-600 dark:text-emerald-400 border-emerald-500/30 hover:bg-emerald-500/10 gap-1"
                      >
                        <Play className="h-3.5 w-3.5" />
                        Resume
                      </Button>
                    )}

                    {item.status === "Scheduled" && (
                      <Button
                        variant="outline"
                        size="sm"
                        onClick={() => triggerAction(item, "reschedule")}
                        className="h-8 px-2.5 text-xs text-blue-600 dark:text-blue-400 border-blue-500/30 hover:bg-blue-500/10 gap-1"
                      >
                        <RotateCcw className="h-3.5 w-3.5" />
                        Re-solve
                      </Button>
                    )}
                  </div>
                </div>

                {/* Mobile Expansion View */}
                {isExpanded && (
                  <div className="pt-2">
                    <WorkloadRowExpansion workload={item} />
                  </div>
                )}
              </div>
            )
          })
        )}
      </div>

      {/* Two-Step Action Impact Confirmation Modal */}
      <WorkloadActionDialog
        impact={dialogImpact}
        open={dialogOpen}
        onOpenChange={setDialogOpen}
        onConfirm={handleConfirmAction}
      />
    </div>
  )
}
