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

export const initialWorkloads: Workload[] = [
  {
    id: "WL-409",
    name: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    powerKw: 1200,
    actualPowerKw: 1200,
    scheduledWindow: "01:30 – 05:30 UTC",
    deadline: "10:00 AM UTC",
    savings: "$1,840 (27%)",
    status: "Scheduled",
    machineCluster: "64x NVIDIA H100 SXM5",
    priority: "High",
    hardware: "64x NVIDIA H100 SXM5 80GB · 8x NDR 400G InfiniBand · 4TB Host RAM",
    precedence: "Requires checkpoint sync from Job #408; standalone execution once dispatched",
    slaBuffer: "+4.5 hrs buffer before SLA violation",
    slaRisk: "Zero",
    scheduleRationale: "Shifted to 01:30–05:30 UTC when LZ_NORTH price forecast is <$20.50/MWh with 78% regional wind generation.",
    carbonOffsetKg: 520,
  },
  {
    id: "WL-108",
    name: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    powerKw: 1800,
    actualPowerKw: 1800,
    scheduledWindow: "00:00 – 16:30 UTC",
    deadline: "Flexible Throughput",
    savings: "$1,450 (31%)",
    status: "Running",
    machineCluster: "Antminer S19 Pro+ Pod 2",
    priority: "Interruptible",
    hardware: "256x Antminer S19 Pro+ (Hydro-Cooled) · 3-Phase 480V Industrial Bus",
    precedence: "Zero upstream dependencies; fully curtailable within 60 seconds",
    slaBuffer: "Continuous curtailment eligible; no hard deadline SLA penalty",
    slaRisk: "Zero",
    scheduleRationale: "Operating during off-peak and moderate price hours. Programmed to auto-throttle or suspend if LMP spikes above $65/MWh.",
    carbonOffsetKg: 380,
  },
  {
    id: "WL-812",
    name: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    powerKw: 450,
    actualPowerKw: 450,
    scheduledWindow: "02:00 – 04:30 UTC",
    deadline: "08:00 AM UTC",
    savings: "$520 (19%)",
    status: "Scheduled",
    machineCluster: "Slurm HPC Cluster (96 Nodes)",
    priority: "Normal",
    hardware: "96x AMD EPYC 9654 Nodes (18,432 vCPUs) · Dual 100GbE RoCE Interconnect",
    precedence: "Requires upstream financial market close ingestion batch #799",
    slaBuffer: "+3.5 hrs buffer before morning trading desk SLA",
    slaRisk: "Low",
    scheduleRationale: "Optimized for the overnight solar/wind surplus trough; avoids ERCOT morning ramp pricing.",
    carbonOffsetKg: 190,
  },
  {
    id: "WL-022",
    name: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    powerKw: 320,
    actualPowerKw: 320,
    scheduledWindow: "13:30 – 16:00 UTC",
    deadline: "Peak Window (17:00)",
    savings: "$310 (16%)",
    status: "Running",
    machineCluster: "Trane Centrifugal Chiller Bank",
    priority: "Critical",
    hardware: "2x 500-ton Trane CenTraVac Variable-Speed Chillers · Thermal Ice Storage Buffer",
    precedence: "Thermal inertia loop; must complete 60 min before peak demand event",
    slaBuffer: "+1.0 hr buffer before data center temperature threshold constraint",
    slaRisk: "Low",
    scheduleRationale: "Pre-cooling chilled water loop down to 38°F using low afternoon grid rates prior to the 17:00 ERCOT peak.",
    carbonOffsetKg: 140,
  },
]
