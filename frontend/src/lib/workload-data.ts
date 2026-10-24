export interface Workload {
  id: string
  name: string
  type: "ML Training" | "Crypto Mining" | "HPC Batch" | "HVAC Pre-Cool"
  powerKw: number
  scheduledWindow: string
  deadline: string
  savings: string
  status: "Scheduled" | "Running" | "Throttled" | "Paused" | "Completed"
  machineCluster: string
  priority?: "Critical" | "High" | "Normal" | "Interruptible"
  hardware?: string
  precedence?: string
  slaBuffer?: string
  slaRisk?: "Zero" | "Low" | "Moderate" | "High"
  scheduleRationale?: string
  carbonOffsetKg?: number
  actualPowerKw?: number
}

export const initialWorkloads: Workload[] = []
