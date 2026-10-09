"use client"

import * as React from "react"
import {
  Server,
  Calendar,
  Table,
  Play,
  Pause,
  Filter,
  Search,
  Plus,
  Cpu,
  Zap,
  TrendingDown,
  Clock,
  Layers,
  Sparkles,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { GoogleCalendarView } from "@/components/schedule-calendar/GoogleCalendarView"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { initialWorkloads, type Workload } from "@/lib/workload-data"
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
  DialogTrigger,
} from "@/components/ui/dialog"

export default function WorkloadsPage() {
  const [workloads, setWorkloads] = React.useState<Workload[]>(initialWorkloads)
  const [displayMode, setDisplayMode] = React.useState<"table" | "calendar">("calendar")
  const [searchFilter, setSearchFilter] = React.useState("")
  const [clusterFilter, setClusterFilter] = React.useState<string>("ALL")
  const [registerOpen, setRegisterOpen] = React.useState(false)

  // Registration Form State
  const [newJobName, setNewJobName] = React.useState("")
  const [newJobType, setNewJobType] = React.useState<Workload["type"]>("ML Training")
  const [newJobCluster, setNewJobCluster] = React.useState("64x NVIDIA H100 SXM5")
  const [newJobPower, setNewJobPower] = React.useState("850")
  const [newJobWindow, setNewJobWindow] = React.useState("02:00 – 06:00 UTC")
  const [newJobDeadline, setNewJobDeadline] = React.useState("12:00 PM UTC")

  const clusters = React.useMemo(() => {
    return Array.from(new Set(workloads.map((w) => w.machineCluster)))
  }, [workloads])

  const filteredWorkloads = React.useMemo(() => {
    return workloads.filter((w) => {
      const matchesSearch =
        searchFilter === "" ||
        w.name.toLowerCase().includes(searchFilter.toLowerCase()) ||
        w.id.toLowerCase().includes(searchFilter.toLowerCase()) ||
        w.type.toLowerCase().includes(searchFilter.toLowerCase())
      const matchesCluster = clusterFilter === "ALL" || w.machineCluster === clusterFilter
      return matchesSearch && matchesCluster
    })
  }, [workloads, searchFilter, clusterFilter])

  const handleToggleWorkload = (id: string) => {
    setWorkloads((prev) =>
      prev.map((w) => {
        if (w.id === id) {
          return {
            ...w,
            status:
              w.status === "Running" ? "Throttled" : w.status === "Throttled" ? "Running" : "Running",
          }
        }
        return w
      })
    )
  }

  const handleRegisterWorkload = (e: React.FormEvent) => {
    e.preventDefault()
    if (!newJobName.trim()) return

    const newWorkload: Workload = {
      id: `WL-${Math.floor(100 + Math.random() * 900)}`,
      name: newJobName.trim(),
      type: newJobType,
      powerKw: Number(newJobPower) || 500,
      scheduledWindow: newJobWindow,
      deadline: newJobDeadline,
      savings: "$680 (24%)",
      status: "Scheduled",
      machineCluster: newJobCluster,
    }

    setWorkloads((prev) => [newWorkload, ...prev])
    setNewJobName("")
    setRegisterOpen(false)
  }

  return (
    <div id="workloads-root" className="space-y-6">
      {/* Page Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Workload Scheduler & Queue
            </h1>
            <DataStatusBadge
              size="sm"
              status="demo"
              source="Simulated Slurm/K8s Compute Adapter"
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Dispatch, reschedule, and throttle flexible compute batches according to ERCOT wholesale price windows.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            <button
              type="button"
              onClick={() => setDisplayMode("table")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5",
                displayMode === "table"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Table className="h-3.5 w-3.5" />
              Table View
            </button>
            <button
              type="button"
              onClick={() => setDisplayMode("calendar")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5",
                displayMode === "calendar"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Calendar className="h-3.5 w-3.5 text-blue-500" />
              Google Calendar
            </button>
          </div>

          <Dialog open={registerOpen} onOpenChange={setRegisterOpen}>
            <DialogTrigger asChild>
              <Button size="sm" className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white">
                <Plus className="h-4 w-4" /> Register Workload
              </Button>
            </DialogTrigger>
            <DialogContent className="sm:max-w-md">
              <form onSubmit={handleRegisterWorkload}>
                <DialogHeader>
                  <DialogTitle>Register New Workload</DialogTitle>
                  <DialogDescription>
                    Add a compute batch with power draw limits, deadline, and cluster assignment.
                  </DialogDescription>
                </DialogHeader>
                <div className="space-y-4 py-4 text-xs">
                  <div className="space-y-1.5">
                    <label className="font-semibold text-foreground">Job Name</label>
                    <Input
                      placeholder="e.g. DeepSeek-R1 Distillation Batch"
                      value={newJobName}
                      onChange={(e) => setNewJobName(e.target.value)}
                      required
                    />
                  </div>
                  <div className="grid grid-cols-2 gap-3">
                    <div className="space-y-1.5">
                      <label className="font-semibold text-foreground">Workload Type</label>
                      <select
                        value={newJobType}
                        onChange={(e) => setNewJobType(e.target.value as Workload["type"])}
                        className="w-full h-9 rounded-md border border-input bg-background px-3 py-1 text-xs"
                      >
                        <option value="ML Training">ML Training</option>
                        <option value="Crypto Mining">Crypto Mining</option>
                        <option value="HPC Batch">HPC Batch</option>
                        <option value="HVAC Pre-Cool">HVAC Pre-Cool</option>
                      </select>
                    </div>
                    <div className="space-y-1.5">
                      <label className="font-semibold text-foreground">Cluster</label>
                      <select
                        value={newJobCluster}
                        onChange={(e) => setNewJobCluster(e.target.value)}
                        className="w-full h-9 rounded-md border border-input bg-background px-3 py-1 text-xs"
                      >
                        <option value="64x NVIDIA H100 SXM5">64x NVIDIA H100 SXM5</option>
                        <option value="Antminer S19 Pro+ Pod 2">Antminer S19 Pro+ Pod 2</option>
                        <option value="Slurm HPC Cluster (96 Nodes)">Slurm HPC Cluster (96 Nodes)</option>
                        <option value="Trane Centrifugal Chiller Bank">Trane Centrifugal Chiller Bank</option>
                      </select>
                    </div>
                  </div>
                  <div className="grid grid-cols-3 gap-3">
                    <div className="space-y-1.5">
                      <label className="font-semibold text-foreground">Power (kW)</label>
                      <Input
                        type="number"
                        value={newJobPower}
                        onChange={(e) => setNewJobPower(e.target.value)}
                        required
                      />
                    </div>
                    <div className="space-y-1.5">
                      <label className="font-semibold text-foreground">Window</label>
                      <Input
                        value={newJobWindow}
                        onChange={(e) => setNewJobWindow(e.target.value)}
                        required
                      />
                    </div>
                    <div className="space-y-1.5">
                      <label className="font-semibold text-foreground">Hard Deadline</label>
                      <Input
                        value={newJobDeadline}
                        onChange={(e) => setNewJobDeadline(e.target.value)}
                        required
                      />
                    </div>
                  </div>
                </div>
                <DialogFooter>
                  <Button type="button" variant="outline" onClick={() => setRegisterOpen(false)}>
                    Cancel
                  </Button>
                  <Button type="submit" className="bg-emerald-600 hover:bg-emerald-700 text-white">
                    Submit to Solver
                  </Button>
                </DialogFooter>
              </form>
            </DialogContent>
          </Dialog>
        </div>
      </div>

      {/* Main View Area */}
      {displayMode === "calendar" ? (
        <Card>
          <CardContent className="p-4 sm:p-6">
            <GoogleCalendarView />
          </CardContent>
        </Card>
      ) : (
        <Card>
          <CardHeader className="flex flex-col sm:flex-row sm:items-center sm:justify-between gap-4">
            <div className="flex items-center gap-3 flex-1 max-w-lg">
              <div className="relative flex-1">
                <Search className="absolute left-2.5 top-2.5 h-3.5 w-3.5 text-muted-foreground" />
                <Input
                  placeholder="Filter by job name or ID..."
                  value={searchFilter}
                  onChange={(e) => setSearchFilter(e.target.value)}
                  className="pl-8 h-9 text-xs"
                />
              </div>
              <select
                value={clusterFilter}
                onChange={(e) => setClusterFilter(e.target.value)}
                className="h-9 rounded-md border border-input bg-background px-3 py-1 text-xs text-muted-foreground"
              >
                <option value="ALL">All Clusters</option>
                {clusters.map((c) => (
                  <option key={c} value={c}>
                    {c}
                  </option>
                ))}
              </select>
            </div>
            <div className="text-xs text-muted-foreground">
              Showing <span className="font-semibold text-foreground">{filteredWorkloads.length}</span> of{" "}
              {workloads.length} workloads
            </div>
          </CardHeader>
          <CardContent>
            <div className="overflow-x-auto">
              <table className="w-full text-left text-sm">
                <thead>
                  <tr className="border-b border-border text-xs font-semibold text-muted-foreground uppercase tracking-wider">
                    <th className="pb-3 pl-2">Job Name & Cluster</th>
                    <th className="pb-3">Type</th>
                    <th className="pb-3">Power</th>
                    <th className="pb-3">Scheduled Window</th>
                    <th className="pb-3">Hard Deadline</th>
                    <th className="pb-3">Est. Savings</th>
                    <th className="pb-3">Status</th>
                    <th className="pb-3 pr-2 text-right">Actions</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-border/60">
                  {filteredWorkloads.map((item) => (
                    <tr key={item.id} className="hover:bg-muted/40 transition-colors">
                      <td className="py-3 pl-2">
                        <div className="font-semibold text-foreground">{item.name}</div>
                        <div className="text-[11px] text-muted-foreground font-mono">{item.machineCluster}</div>
                      </td>
                      <td className="py-3">
                        <span className="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs font-medium text-foreground">
                          {item.type}
                        </span>
                      </td>
                      <td className="py-3 font-mono font-medium">
                        {item.powerKw} kW
                      </td>
                      <td className="py-3 font-mono text-xs">
                        {item.scheduledWindow}
                      </td>
                      <td className="py-3 text-xs text-muted-foreground">
                        {item.deadline}
                      </td>
                      <td className="py-3 font-mono font-bold text-emerald-600 dark:text-emerald-400 text-xs">
                        {item.savings}
                      </td>
                      <td className="py-3">
                        <span
                          className={`inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-[11px] font-semibold ${
                            item.status === "Running"
                              ? "bg-emerald-500/15 text-emerald-600 dark:text-emerald-400 border border-emerald-500/30"
                              : item.status === "Throttled"
                              ? "bg-amber-500/15 text-amber-600 dark:text-amber-400 border border-amber-500/30"
                              : "bg-blue-500/15 text-blue-600 dark:text-blue-400 border border-blue-500/30"
                          }`}
                        >
                          <span
                            className={`h-1.5 w-1.5 rounded-full ${
                              item.status === "Running"
                                ? "bg-emerald-500 animate-pulse"
                                : item.status === "Throttled"
                                ? "bg-amber-500"
                                : "bg-blue-500"
                            }`}
                          />
                          {item.status}
                        </span>
                      </td>
                      <td className="py-3 pr-2 text-right">
                        <Button
                          variant="ghost"
                          size="sm"
                          className="h-8 text-xs text-muted-foreground hover:text-foreground"
                          onClick={() => handleToggleWorkload(item.id)}
                        >
                          {item.status === "Running" ? (
                            <Pause className="h-3.5 w-3.5 text-amber-500" />
                          ) : (
                            <Play className="h-3.5 w-3.5 text-emerald-500" />
                          )}
                          <span className="sr-only">Toggle</span>
                        </Button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </CardContent>
        </Card>
      )}
    </div>
  )
}
