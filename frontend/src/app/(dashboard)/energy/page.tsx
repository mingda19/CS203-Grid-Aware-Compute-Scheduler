"use client"

import * as React from "react"
import {
  Battery,
  BatteryCharging,
  Zap,
  Wind,
  Sun,
  Flame,
  Atom,
  SlidersHorizontal,
  ShieldCheck,
  Activity,
  ArrowUpRight,
  TrendingDown,
  Info,
  CheckCircle2,
  Gauge,
} from "lucide-react"
import { Card, CardContent, CardDescription, CardHeader, CardTitle, CardFooter } from "@/components/ui/card"
import { Button } from "@/components/ui/button"
import { DataStatusBadge } from "@/components/ui/data-status-badge"

export default function EnergyPage() {
  const [bessMode, setBessMode] = React.useState<"Auto Arbitrage" | "Peak Shave" | "Hold">("Auto Arbitrage")

  return (
    <div id="energy-root" className="space-y-6">
      {/* Header */}
      <div className="flex flex-col md:flex-row md:items-center md:justify-between gap-4 bg-gradient-to-r from-card to-card/60 p-6 rounded-2xl border border-border shadow-xs">
        <div>
          <div className="flex items-center gap-2">
            <h1 className="text-2xl font-bold tracking-tight text-foreground">
              Battery & Energy Mix
            </h1>
            <DataStatusBadge
              size="sm"
              status="demo"
              source="Facility BESS Adapter & ERCOT Fuel Mix Model"
            />
          </div>
          <p className="text-sm text-muted-foreground mt-1">
            Real-time battery energy storage telemetry, clean generation mix, and datacenter facility electrical constraints.
          </p>
        </div>

        <div className="flex items-center gap-2">
          <span className="text-xs text-muted-foreground">BESS Dispatch Mode:</span>
          <div className="inline-flex rounded-lg border border-border bg-muted/40 p-0.5 text-xs">
            {(["Auto Arbitrage", "Peak Shave", "Hold"] as const).map((mode) => (
              <button
                key={mode}
                type="button"
                onClick={() => setBessMode(mode)}
                className={`px-2.5 py-1 rounded-md font-medium transition-all ${
                  bessMode === mode
                    ? "bg-background text-foreground shadow-xs font-semibold"
                    : "text-muted-foreground hover:text-foreground"
                }`}
              >
                {mode}
              </button>
            ))}
          </div>
        </div>
      </div>

      {/* Top 3 Metric Overview Cards */}
      <div className="grid grid-cols-1 md:grid-cols-3 gap-4">
        {/* BESS SoC */}
        <Card className="hover:border-emerald-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Battery Storage (BESS)
            </CardTitle>
            <BatteryCharging className="h-4 w-4 text-emerald-500" />
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold font-mono text-emerald-600 dark:text-emerald-400">
              78% <span className="text-xs font-normal text-muted-foreground">SoC</span>
            </div>
            <div className="mt-2 text-xs text-muted-foreground flex items-center justify-between">
              <span>Usable Capacity</span>
              <span className="font-mono font-semibold text-foreground">1.95 MWh / 2.5 MWh</span>
            </div>
            <div className="mt-1 text-xs text-muted-foreground flex items-center justify-between">
              <span>Round-Trip Efficiency</span>
              <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400">91.4%</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Status</span>
            <span className="text-emerald-600 dark:text-emerald-400 font-semibold">Ready to Discharge</span>
          </CardFooter>
        </Card>

        {/* Renewable Clean Grid Mix */}
        <Card className="hover:border-teal-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Clean Energy Availability
            </CardTitle>
            <Wind className="h-4 w-4 text-teal-500" />
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold font-mono text-teal-600 dark:text-teal-400">
              58.6% <span className="text-xs font-normal text-muted-foreground">Green</span>
            </div>
            <div className="mt-2 text-xs text-muted-foreground flex items-center justify-between">
              <span>Wind Generation</span>
              <span className="font-mono font-semibold text-teal-600 dark:text-teal-400">41.2% (West TX)</span>
            </div>
            <div className="mt-1 text-xs text-muted-foreground flex items-center justify-between">
              <span>Solar Photovoltaic</span>
              <span className="font-mono font-semibold text-amber-500">17.4% (Active)</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Carbon Intensity</span>
            <span className="text-foreground font-semibold">298 gCO2/kWh</span>
          </CardFooter>
        </Card>

        {/* Datacenter Transformer Load */}
        <Card className="hover:border-blue-500/50 transition-colors">
          <CardHeader className="flex flex-row items-center justify-between pb-2">
            <CardTitle className="text-xs font-medium text-muted-foreground uppercase">
              Substation Capacity
            </CardTitle>
            <Gauge className="h-4 w-4 text-blue-500" />
          </CardHeader>
          <CardContent>
            <div className="text-3xl font-bold font-mono text-foreground">
              3.45 MW <span className="text-xs font-normal text-muted-foreground">/ 5.0 MW</span>
            </div>
            <div className="mt-2 text-xs text-muted-foreground flex items-center justify-between">
              <span>Feeder Utilization</span>
              <span className="font-mono font-semibold text-blue-600 dark:text-blue-400">69.0% (Safe)</span>
            </div>
            <div className="mt-1 text-xs text-muted-foreground flex items-center justify-between">
              <span>Available Headroom</span>
              <span className="font-mono font-semibold text-emerald-600 dark:text-emerald-400">1.55 MW</span>
            </div>
          </CardContent>
          <CardFooter className="pt-0 text-[11px] text-muted-foreground border-t border-border/40 mt-3 flex justify-between">
            <span>Transformer Thermal Limit</span>
            <span className="text-foreground font-semibold">Normal (48°C)</span>
          </CardFooter>
        </Card>
      </div>

      {/* Deep Breakdown Sections */}
      <div className="grid grid-cols-1 lg:grid-cols-2 gap-6">
        {/* Generation Fuel Mix Detail */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base font-semibold flex items-center gap-2">
              <Zap className="h-4 w-4 text-emerald-500" />
              ERCOT Fuel Mix Breakdown
            </CardTitle>
            <CardDescription className="text-xs">
              Instantaneous generation source composition across the ERCOT interconnection.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-4">
            <div className="space-y-3">
              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="flex items-center gap-1.5 font-medium text-teal-600 dark:text-teal-400">
                    <Wind className="h-3.5 w-3.5" /> Wind
                  </span>
                  <span className="font-mono font-semibold">41.2% (18,400 MW)</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-teal-500 rounded-full" style={{ width: "41.2%" }} />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="flex items-center gap-1.5 font-medium text-amber-500">
                    <Sun className="h-3.5 w-3.5" /> Solar PV
                  </span>
                  <span className="font-mono font-semibold">17.4% (7,800 MW)</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-amber-500 rounded-full" style={{ width: "17.4%" }} />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="flex items-center gap-1.5 font-medium text-blue-500">
                    <Flame className="h-3.5 w-3.5" /> Natural Gas (Combined Cycle)
                  </span>
                  <span className="font-mono font-semibold">28.5% (12,700 MW)</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-blue-500 rounded-full" style={{ width: "28.5%" }} />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="flex items-center gap-1.5 font-medium text-purple-500">
                    <Atom className="h-3.5 w-3.5" /> Nuclear
                  </span>
                  <span className="font-mono font-semibold">10.2% (4,550 MW)</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-purple-500 rounded-full" style={{ width: "10.2%" }} />
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-xs mb-1">
                  <span className="flex items-center gap-1.5 font-medium text-muted-foreground">
                    Coal & Other
                  </span>
                  <span className="font-mono font-semibold">2.7% (1,200 MW)</span>
                </div>
                <div className="h-2 rounded-full bg-muted overflow-hidden">
                  <div className="h-full bg-muted-foreground/60 rounded-full" style={{ width: "2.7%" }} />
                </div>
              </div>
            </div>
          </CardContent>
        </Card>

        {/* Facility Thermal & Electrical Constraints */}
        <Card>
          <CardHeader>
            <CardTitle className="text-base font-semibold flex items-center gap-2">
              <SlidersHorizontal className="h-4 w-4 text-emerald-500" />
              Datacenter Electrical & Thermal Constraints
            </CardTitle>
            <CardDescription className="text-xs">
              Physical boundary constraints enforced on the GACS solver during workload optimization.
            </CardDescription>
          </CardHeader>
          <CardContent className="space-y-3">
            <div className="p-3 rounded-xl border border-border/70 bg-card space-y-1">
              <div className="flex items-center justify-between text-xs">
                <span className="font-semibold text-foreground">Peak Demand Limit</span>
                <span className="font-mono font-bold text-emerald-600 dark:text-emerald-400">5.00 MW Max</span>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Exceeding this value incurs ERCOT 4CP demand ratchets and substation penalty tariffs.
              </p>
            </div>

            <div className="p-3 rounded-xl border border-border/70 bg-card space-y-1">
              <div className="flex items-center justify-between text-xs">
                <span className="font-semibold text-foreground">HVAC Thermal Storage Pre-Cooling</span>
                <span className="font-mono font-bold text-blue-500">Active (85% Buffer)</span>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Centrifugal chillers run during low-price hours to cool thermal storage tanks, reducing chiller draw during peak LMP.
              </p>
            </div>

            <div className="p-3 rounded-xl border border-border/70 bg-card space-y-1">
              <div className="flex items-center justify-between text-xs">
                <span className="font-semibold text-foreground">Inverter Slew Rate Limit</span>
                <span className="font-mono font-bold text-foreground">250 kW / min</span>
              </div>
              <p className="text-[11px] text-muted-foreground">
                Prevents sudden voltage transients and grid step disturbances when ramping ASIC pods or GPU clusters.
              </p>
            </div>
          </CardContent>
        </Card>
      </div>
    </div>
  )
}
