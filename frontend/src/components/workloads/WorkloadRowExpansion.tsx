"use client"

import * as React from "react"
import {
  Cpu,
  GitBranch,
  Clock,
  Sparkles,
  ShieldCheck,
  ShieldAlert,
  Leaf,
  Layers,
} from "lucide-react"
import type { Workload } from "@/lib/workload-data"

interface WorkloadRowExpansionProps {
  workload: Workload
}

export function WorkloadRowExpansion({ workload }: WorkloadRowExpansionProps) {
  const isSlaSafe = !workload.slaRisk || workload.slaRisk === "Zero" || workload.slaRisk === "Low"

  return (
    <div className="p-4 sm:p-5 bg-muted/20 border-t border-border/80 text-xs animate-in fade-in-50 duration-200">
      <div className="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-4 gap-4">
        {/* Hardware & Cluster Specs */}
        <div className="p-3.5 rounded-xl border border-border/70 bg-card/60 space-y-1.5">
          <div className="flex items-center gap-1.5 text-foreground font-semibold">
            <Cpu className="h-4 w-4 text-emerald-500" />
            <span>Hardware & Topology</span>
          </div>
          <p className="text-[11px] text-foreground font-mono leading-relaxed">
            {workload.hardware || `${workload.machineCluster} (${workload.powerKw} kW Max Draw)`}
          </p>
          <div className="flex items-center gap-2 pt-1 text-[10px] text-muted-foreground">
            <span className="font-mono">Rated: {workload.powerKw} kW</span>
            <span>·</span>
            <span className="font-mono">
              Active: {workload.actualPowerKw ?? workload.powerKw} kW
            </span>
          </div>
        </div>

        {/* Precedence & Dependencies */}
        <div className="p-3.5 rounded-xl border border-border/70 bg-card/60 space-y-1.5">
          <div className="flex items-center gap-1.5 text-foreground font-semibold">
            <GitBranch className="h-4 w-4 text-teal-500" />
            <span>Precedence & Constraints</span>
          </div>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            {workload.precedence || "Standalone compute batch with zero inter-job blocking dependencies."}
          </p>
          {workload.priority && (
            <div className="pt-1">
              <span className="inline-flex items-center px-1.5 py-0.5 rounded text-[10px] font-medium bg-muted text-foreground">
                Priority: {workload.priority}
              </span>
            </div>
          )}
        </div>

        {/* SLA & Margin Buffer */}
        <div className="p-3.5 rounded-xl border border-border/70 bg-card/60 space-y-1.5">
          <div className="flex items-center gap-1.5 text-foreground font-semibold">
            {isSlaSafe ? (
              <ShieldCheck className="h-4 w-4 text-emerald-500" />
            ) : (
              <ShieldAlert className="h-4 w-4 text-amber-500" />
            )}
            <span>SLA & Completion Margin</span>
          </div>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            {workload.slaBuffer || "Within nominal dispatch margin."}
          </p>
          <div className="flex items-center gap-2 pt-1">
            <span
              className={`inline-flex items-center gap-1 px-1.5 py-0.5 rounded text-[10px] font-semibold ${
                isSlaSafe
                  ? "bg-emerald-500/10 text-emerald-600 dark:text-emerald-400 border border-emerald-500/20"
                  : "bg-amber-500/10 text-amber-600 dark:text-amber-400 border border-amber-500/20"
              }`}
            >
              Risk: {workload.slaRisk || "Zero"}
            </span>
            <span className="text-[10px] text-muted-foreground">
              Deadline: <span className="font-mono text-foreground">{workload.deadline}</span>
            </span>
          </div>
        </div>

        {/* Scheduler Rationale */}
        <div className="p-3.5 rounded-xl border border-border/70 bg-card/60 space-y-1.5">
          <div className="flex items-center gap-1.5 text-foreground font-semibold">
            <Sparkles className="h-4 w-4 text-blue-500" />
            <span>Dispatch Rationale</span>
          </div>
          <p className="text-[11px] text-muted-foreground leading-relaxed">
            {workload.scheduleRationale ||
              `Scheduled into window ${workload.scheduledWindow} to minimize wholesale LMP cost and leverage clean grid mix.`}
          </p>
          {workload.carbonOffsetKg && (
            <div className="flex items-center gap-1 pt-1 text-[10px] text-emerald-600 dark:text-emerald-400 font-medium">
              <Leaf className="h-3 w-3" />
              <span>Est. {workload.carbonOffsetKg} kg CO2 avoided</span>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
