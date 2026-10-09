export interface Workload {
  id: string
  name: string
  type: "ML Training" | "Crypto Mining" | "HPC Batch" | "HVAC Pre-Cool"
  powerKw: number
  scheduledWindow: string
  deadline: string
  savings: string
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  machineCluster: string
}

export const initialWorkloads: Workload[] = [
  {
    id: "WL-409",
    name: "Llama-3-70B Fine-Tuning Run #4",
    type: "ML Training",
    powerKw: 1200,
    scheduledWindow: "01:30 – 05:30 UTC",
    deadline: "10:00 AM UTC",
    savings: "$1,840 (27%)",
    status: "Scheduled",
    machineCluster: "64x NVIDIA H100 SXM5",
  },
  {
    id: "WL-108",
    name: "ASIC Pod Alpha - Dynamic Mining",
    type: "Crypto Mining",
    powerKw: 1800,
    scheduledWindow: "00:00 – 16:30 UTC",
    deadline: "Flexible Throughput",
    savings: "$1,450 (31%)",
    status: "Running",
    machineCluster: "Antminer S19 Pro+ Pod 2",
  },
  {
    id: "WL-812",
    name: "Monte Carlo Risk Analysis Batch",
    type: "HPC Batch",
    powerKw: 450,
    scheduledWindow: "02:00 – 04:30 UTC",
    deadline: "08:00 AM UTC",
    savings: "$520 (19%)",
    status: "Scheduled",
    machineCluster: "Slurm HPC Cluster (96 Nodes)",
  },
  {
    id: "WL-022",
    name: "Facility Thermal Chiller Pre-Cool",
    type: "HVAC Pre-Cool",
    powerKw: 320,
    scheduledWindow: "13:30 – 16:00 UTC",
    deadline: "Peak Window (17:00)",
    savings: "$310 (16%)",
    status: "Running",
    machineCluster: "Trane Centrifugal Chiller Bank",
  },
]
