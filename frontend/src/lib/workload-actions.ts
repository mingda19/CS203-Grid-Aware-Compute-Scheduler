import type { Workload } from "./workload-data"

export type WorkloadActionType = "pause" | "resume" | "throttle" | "reschedule"

export interface WorkloadActionImpact {
  action: WorkloadActionType
  title: string
  workloadId: string
  workloadName: string
  currentStatus: Workload["status"]
  nextStatus: Workload["status"]
  currentPowerKw: number
  nextPowerKw: number
  powerDeltaKw: number
  powerDeltaPercent: number
  slaRisk: "Zero" | "Low" | "Moderate" | "High"
  slaNotice: string
  costImpactNotice: string
  confirmButtonLabel: string
  confirmButtonVariant: "destructive" | "default" | "secondary"
}

/**
 * Computes deterministic operational and electrical impact for a given operator action on a workload.
 */
export function getWorkloadActionImpact(
  workload: Workload,
  action: WorkloadActionType
): WorkloadActionImpact {
  const basePower = workload.powerKw
  const currentActualPower =
    workload.actualPowerKw ??
    (workload.status === "Paused"
      ? 0
      : workload.status === "Throttled"
      ? Math.round(basePower * 0.5)
      : basePower)

  switch (action) {
    case "pause": {
      const nextPower = 0
      const delta = nextPower - currentActualPower
      const isCritical = workload.priority === "Critical"
      return {
        action: "pause",
        title: `Pause Workload · ${workload.id}`,
        workloadId: workload.id,
        workloadName: workload.name,
        currentStatus: workload.status,
        nextStatus: "Paused",
        currentPowerKw: currentActualPower,
        nextPowerKw: nextPower,
        powerDeltaKw: delta,
        powerDeltaPercent: currentActualPower > 0 ? -100 : 0,
        slaRisk: isCritical ? "High" : workload.priority === "High" ? "Moderate" : "Low",
        slaNotice:
          workload.priority === "Interruptible"
            ? "Interruptible workload: Halting job carries zero contractual SLA risk."
            : `Warning: Suspending compute may consume ${workload.slaBuffer || "the deadline safety buffer"} and risk SLA penalty.`,
        costImpactNotice:
          "Pausing immediately cuts active power draw. However, if delayed into high LMP hours, re-running later will incur higher $/MWh costs.",
        confirmButtonLabel: "Confirm Immediate Pause",
        confirmButtonVariant: "destructive",
      }
    }

    case "throttle": {
      const nextPower = Math.round(basePower * 0.5)
      const delta = nextPower - currentActualPower
      return {
        action: "throttle",
        title: `Throttle Power to 50% · ${workload.id}`,
        workloadId: workload.id,
        workloadName: workload.name,
        currentStatus: workload.status,
        nextStatus: "Throttled",
        currentPowerKw: currentActualPower,
        nextPowerKw: nextPower,
        powerDeltaKw: delta,
        powerDeltaPercent:
          currentActualPower > 0
            ? Math.round((delta / currentActualPower) * 100)
            : -50,
        slaRisk: workload.priority === "Critical" ? "Moderate" : "Low",
        slaNotice:
          "Throttling slows execution rate by ~50%. Verify estimated completion time remains within hard deadline constraint.",
        costImpactNotice:
          `Curbs peak power demand by ${Math.abs(delta)} kW. Useful for shaving load during impending ERCOT 4CP or high-price intervals.`,
        confirmButtonLabel: `Confirm Throttle to ${nextPower} kW`,
        confirmButtonVariant: "default",
      }
    }

    case "resume": {
      const nextPower = basePower
      const delta = nextPower - currentActualPower
      return {
        action: "resume",
        title: `Resume Full Power Dispatch · ${workload.id}`,
        workloadId: workload.id,
        workloadName: workload.name,
        currentStatus: workload.status,
        nextStatus: "Running",
        currentPowerKw: currentActualPower,
        nextPowerKw: nextPower,
        powerDeltaKw: delta,
        powerDeltaPercent:
          currentActualPower > 0
            ? Math.round((delta / currentActualPower) * 100)
            : 100,
        slaRisk: "Zero",
        slaNotice:
          "Resuming full throughput restores maximum processing speed and recovers SLA safety margin.",
        costImpactNotice:
          `Facility load will increase by +${delta} kW. Ensure current nodal price is within economic dispatch budget.`,
        confirmButtonLabel: `Confirm Resume (${nextPower} kW)`,
        confirmButtonVariant: "default",
      }
    }

    case "reschedule": {
      return {
        action: "reschedule",
        title: `Reschedule Execution Window · ${workload.id}`,
        workloadId: workload.id,
        workloadName: workload.name,
        currentStatus: workload.status,
        nextStatus: "Scheduled",
        currentPowerKw: currentActualPower,
        nextPowerKw: 0,
        powerDeltaKw: -currentActualPower,
        powerDeltaPercent: currentActualPower > 0 ? -100 : 0,
        slaRisk: "Low",
        slaNotice:
          "Re-evaluates constraint solver to assign the lowest-cost clean energy window prior to the deadline.",
        costImpactNotice:
          "Rescheduling submits job requirements back to the XGBoost-MILP optimization solver for automated re-slotting.",
        confirmButtonLabel: "Submit to Constraint Solver",
        confirmButtonVariant: "default",
      }
    }
  }
}

/**
 * Applies the operator action to produce an updated Workload instance.
 */
export function applyWorkloadAction(
  workload: Workload,
  action: WorkloadActionType
): Workload {
  const impact = getWorkloadActionImpact(workload, action)
  return {
    ...workload,
    status: impact.nextStatus,
    actualPowerKw: impact.nextPowerKw,
  }
}
