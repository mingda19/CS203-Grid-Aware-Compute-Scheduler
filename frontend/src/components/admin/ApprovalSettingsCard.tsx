"use client"

import * as React from "react"
import { Checkbox } from "@/components/ui/checkbox"
import { Button } from "@/components/ui/button"
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card"
import { Skeleton } from "@/components/ui/skeleton"
import { settingsApi } from "@/lib/api"

/** Whether a submitter may approve their own plan. Admins always can. Saves when toggled. */
export function ApprovalSettingsCard() {
  const [allowSelfApproval, setAllowSelfApproval] = React.useState<boolean | null>(null)
  const [error, setError] = React.useState("")
  const [saving, setSaving] = React.useState(false)
  const [reload, setReload] = React.useState(0)

  React.useEffect(() => {
    let active = true
    settingsApi.get()
      .then((r) => {
        if (!active) return
        if (!r.success || !r.data) throw new Error(r.message || "Could not load the settings")
        setAllowSelfApproval(r.data.allowSelfApproval)
      })
      .catch((e) => { if (active) setError(e instanceof Error ? e.message : "Could not load the settings") })
    return () => { active = false }
  }, [reload])

  const toggle = async (next: boolean) => {
    setSaving(true)
    setError("")
    try {
      const r = await settingsApi.update({ allowSelfApproval: next })
      if (!r.success || !r.data) throw new Error(r.message || "Could not save the settings")
      setAllowSelfApproval(r.data.allowSelfApproval)
    } catch (err) {
      setError(err instanceof Error ? err.message : "Could not save the settings")
    } finally {
      setSaving(false)
    }
  }

  return (
    <Card className="min-w-0">
      <CardHeader className="space-y-1 p-4 pb-0 sm:p-5 sm:pb-0">
        <CardTitle className="text-sm font-semibold leading-5">Approvals</CardTitle>
        <CardDescription className="text-xs leading-4">Who can approve a plan. Administrators can always approve.</CardDescription>
      </CardHeader>
      <CardContent className="p-4 sm:p-5">
        {allowSelfApproval === null && error ? (
          <div className="flex min-w-0 items-center gap-3">
            <span className="min-w-0 truncate text-sm leading-5 text-muted-foreground" title={error}>Could not load the settings</span>
            <Button variant="outline" size="sm" className="shrink-0" onClick={() => { setError(""); setReload((n) => n + 1) }}>Retry</Button>
          </div>
        ) : allowSelfApproval === null ? (
          <Skeleton className="h-5 w-64 max-w-full" role="status" aria-label="Loading settings" />
        ) : (
          <div className="space-y-2">
            <label className="flex items-center gap-3 text-sm leading-5">
              <Checkbox
                checked={allowSelfApproval}
                disabled={saving}
                onCheckedChange={(checked) => void toggle(checked)}
              />
              <span className="font-medium">Users can approve their own plans</span>
            </label>
            {error && <p role="alert" className="break-words text-sm leading-5 text-destructive-foreground">{error}</p>}
          </div>
        )}
      </CardContent>
    </Card>
  )
}
