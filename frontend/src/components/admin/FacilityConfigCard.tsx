"use client"

import * as React from "react"
import { CheckCircle2 } from "lucide-react"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
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
  const [reload, setReload] = React.useState(0)

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
  }, [reload])

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

  const label = "block text-xs leading-4 text-muted-foreground"

  return (
    <Card className="min-w-0">
      <CardHeader className="space-y-1 p-4 pb-0 sm:p-5 sm:pb-0">
        <CardTitle className="text-sm font-semibold leading-5">Facility</CardTitle>
        <CardDescription className="text-xs leading-4">The compute capacity and price zone that every plan uses.</CardDescription>
      </CardHeader>
      <CardContent className="p-4 sm:p-5">
        {!facility && error ? (
          <div className="flex min-w-0 items-center gap-3">
            <span className="min-w-0 truncate text-sm leading-5 text-muted-foreground" title={error}>Could not load the facility</span>
            <Button variant="outline" size="sm" className="shrink-0" onClick={() => { setError(""); setReload((n) => n + 1) }}>Retry</Button>
          </div>
        ) : !facility ? (
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4" role="status" aria-label="Loading facility">
            {Array.from({ length: 4 }, (_, i) => <Skeleton key={i} className="h-16 w-full" />)}
          </div>
        ) : (
          <form onSubmit={save} className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-4">
            <label className="min-w-0 space-y-2">
              <span className={label}>Name</span>
              <Input value={facility.name} required onChange={(e) => edit({ name: e.target.value })} />
            </label>
            <label className="min-w-0 space-y-2">
              <span className={label}>CPU hours per hour</span>
              <Input type="number" min="0" step="any" required value={facility.maxCpuHours} className="tabular-nums"
                onChange={(e) => edit({ maxCpuHours: Number(e.target.value) })} />
            </label>
            <label className="min-w-0 space-y-2">
              <span className={label}>GPU hours per hour</span>
              <Input type="number" min="0" step="any" required value={facility.maxGpuHours} className="tabular-nums"
                onChange={(e) => edit({ maxGpuHours: Number(e.target.value) })} />
            </label>
            <label className="min-w-0 space-y-2">
              <span className={label}>ERCOT zone</span>
              <select
                value={facility.location}
                onChange={(e) => edit({ location: e.target.value })}
                className="flex h-10 w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                {LOCATIONS.map(([value, label]) => <option key={value} value={value}>{label}</option>)}
              </select>
            </label>
            <div className="flex min-w-0 flex-wrap items-center justify-end gap-3 sm:col-span-2 lg:col-span-4">
              {error && <p role="alert" className="min-w-0 break-words text-sm leading-5 text-destructive-foreground">{error}</p>}
              {saved && (
                <span role="status" className="inline-flex items-center gap-1.5 text-sm leading-5 text-success-foreground">
                  <CheckCircle2 className="size-4 shrink-0" aria-hidden="true" />
                  Saved
                </span>
              )}
              <Button type="submit" className="shrink-0" disabled={saving}>{saving ? "Saving…" : "Save facility"}</Button>
            </div>
          </form>
        )}
      </CardContent>
    </Card>
  )
}
