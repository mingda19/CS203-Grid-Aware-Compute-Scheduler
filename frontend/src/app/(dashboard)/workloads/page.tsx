"use client"

import * as React from "react"
import {
  Calendar,
  Table,
  Plus,
  Layers,
} from "lucide-react"
import { cn } from "@/lib/utils"
import { GoogleCalendarView } from "@/components/schedule-calendar/GoogleCalendarView"
import { Card, CardContent } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { DataStatusBadge } from "@/components/ui/data-status-badge"
import { initialWorkloads, type Workload } from "@/lib/workload-data"
import { WorkloadQueue } from "@/components/workloads/WorkloadQueue"
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
  const [displayMode, setDisplayMode] = React.useState<"table" | "calendar">("table")
  const [registerOpen, setRegisterOpen] = React.useState(false)

  // Registration Form State
  const [newJobName, setNewJobName] = React.useState("")
  const [newJobType, setNewJobType] = React.useState<Workload["type"]>("ML Training")
  const [newJobCluster, setNewJobCluster] = React.useState("64x NVIDIA H100 SXM5")
  const [newJobPower, setNewJobPower] = React.useState("850")
  const [newJobWindow, setNewJobWindow] = React.useState("02:00 – 06:00 UTC")
  const [newJobDeadline, setNewJobDeadline] = React.useState("12:00 PM UTC")

  const handleRegisterWorkload = (e: React.FormEvent) => {
    e.preventDefault()
    if (!newJobName.trim()) return

    const power = Number(newJobPower) || 500
    const newWorkload: Workload = {
      id: `WL-${Math.floor(100 + Math.random() * 900)}`,
      name: newJobName.trim(),
      type: newJobType,
      powerKw: power,
      actualPowerKw: power,
      scheduledWindow: newJobWindow,
      deadline: newJobDeadline,
      savings: "$680 (24%)",
      status: "Scheduled",
      machineCluster: newJobCluster,
      priority: "Normal",
      hardware: `${newJobCluster} · Allocated ${power} kW envelope`,
      precedence: "Operator-submitted batch; standalone dependency chain",
      slaBuffer: "+2.5 hrs buffer before target deadline",
      slaRisk: "Low",
      scheduleRationale: `Slotted into window ${newJobWindow} based on forecast LMP pricing under $22/MWh.`,
      carbonOffsetKg: Math.round(power * 0.4),
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
            Dispatch, throttle, pause, and inspect flexible compute batches mapped to wholesale ERCOT LMP signals.
          </p>
        </div>

        <div className="flex items-center gap-3">
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            <button
              type="button"
              onClick={() => setDisplayMode("table")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 cursor-pointer",
                displayMode === "table"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Table className="h-3.5 w-3.5" />
              Queue Table
            </button>
            <button
              type="button"
              onClick={() => setDisplayMode("calendar")}
              className={cn(
                "px-3 py-1.5 rounded-md font-medium transition-all flex items-center gap-1.5 cursor-pointer",
                displayMode === "calendar"
                  ? "bg-background text-foreground shadow-xs font-semibold"
                  : "text-muted-foreground hover:text-foreground"
              )}
            >
              <Calendar className="h-3.5 w-3.5 text-blue-500" />
              Calendar View
            </button>
          </div>

          <Dialog open={registerOpen} onOpenChange={setRegisterOpen}>
            <DialogTrigger asChild>
              <Button size="sm" className="gap-2 bg-emerald-600 hover:bg-emerald-700 text-white cursor-pointer">
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
        <WorkloadQueue workloads={workloads} onWorkloadsChange={setWorkloads} />
      )}
    </div>
  )
}
