"use client"

import * as React from "react"
import { Check, Server } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { facilityApi, type FacilityConfig } from "@/lib/api"

const LOCATIONS = [
  ["LZ_NORTH", "LZ_NORTH (North Hub)"],
  ["LZ_HOUSTON", "LZ_HOUSTON (Houston)"],
  ["LZ_SOUTH", "LZ_SOUTH (South Hub)"],
  ["LZ_WEST", "LZ_WEST (West Texas Wind)"],
] as const

/** The shared facility. Everyone reads it, only an admin changes it. */
export function FacilityConfigCard() {
  const [facility, setFacility] = React.useState<FacilityConfig | null>(null)
  const [error, setError] = React.useState("")
  const [saving, setSaving] = React.useState(false)
  const [saved, setSaved] = React.useState(false)

  React.useEffect(() => {
    let active = true
    facilityApi.get()
      .then((r) => {
        if (!active) return
        if (!r.success || !r.data) throw new Error(r.message || "Could not load the facility")
        setFacility(r.data)
      })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : "Could not load the facility") })
    return () => { active = false }
  }, [])

  const edit = (patch: Partial<FacilityConfig>) => {
    setSaved(false)
    setFacility((f) => (f ? { ...f, ...patch } : f))
  }

  const save = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!facility) return
    setSaving(true)
    setError("")
    try {
      const r = await facilityApi.update({
        ...facility,
        maxCpuHours: Number(facility.maxCpuHours) || 0,
        maxGpuHours: Number(facility.maxGpuHours) || 0,
      })
      if (!r.success || !r.data) throw new Error(r.message || "Could not save the facility")
      setFacility(r.data)
      setSaved(true)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the facility")
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="border border-border shadow-sm">
      <CardHeader className="pb-3 border-b border-border/60">
        <CardTitle className="text-base font-bold flex items-center gap-2">
          <Server className="h-4 w-4 text-teal-500" />
          Facility
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-4">
        {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
        {facility ? (
          <form onSubmit={save} className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <label className="space-y-2 text-sm">
              <span className="block font-medium">Name</span>
              <Input value={facility.name} required onChange={(e) => edit({ name: e.target.value })} />
            </label>
            <label className="space-y-2 text-sm">
              <span className="block font-medium">CPU hours per hour</span>
              <Input type="number" min="0" step="any" required value={facility.maxCpuHours}
                onChange={(e) => edit({ maxCpuHours: Number(e.target.value) })} />
            </label>
            <label className="space-y-2 text-sm">
              <span className="block font-medium">GPU hours per hour</span>
              <Input type="number" min="0" step="any" required value={facility.maxGpuHours}
                onChange={(e) => edit({ maxGpuHours: Number(e.target.value) })} />
            </label>
            <label className="space-y-2 text-sm">
              <span className="block font-medium">ERCOT zone</span>
              <select
                value={facility.location}
                onChange={(e) => edit({ location: e.target.value })}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                {LOCATIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
            <div className="flex items-center justify-end gap-3 sm:col-span-2 lg:col-span-4">
              {saved && (
                <span role="status" className="inline-flex items-center gap-1.5 text-sm text-emerald-600 dark:text-emerald-400">
                  <Check className="h-4 w-4" />
                  Saved
                </span>
              )}
              <Button type="submit" disabled={saving}>{saving ? "Saving…" : "Save facility"}</Button>
            </div>
          </form>
        ) : !error ? (
          <p className="text-sm text-muted-foreground">Loading…</p>
        ) : null}
      </CardContent>
    </Card>
  )
}
