"use client"

import * as React from "react"
import { ClipboardCheck } from "lucide-react"
import { Checkbox } from "@/components/ui/checkbox"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { settingsApi } from "@/lib/api"

/** Whether a submitter may approve their own plan. Admins always can. Saves when toggled. */
export function ApprovalSettingsCard() {
  const [allowSelfApproval, setAllowSelfApproval] = React.useState<boolean | null>(null)
  const [error, setError] = React.useState("")
  const [saving, setSaving] = React.useState(false)

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
  }, [])

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
    <Card className="border border-border shadow-sm">
      <CardHeader className="pb-3 border-b border-border/60">
        <CardTitle className="text-base font-bold flex items-center gap-2">
          <ClipboardCheck className="h-4 w-4 text-teal-500" />
          Approvals
        </CardTitle>
      </CardHeader>
      <CardContent className="pt-4">
        {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
        {allowSelfApproval === null ? (
          !error && <p className="text-sm text-muted-foreground">Loading…</p>
        ) : (
          <label className="flex items-center gap-3 text-sm">
            <Checkbox
              checked={allowSelfApproval}
              disabled={saving}
              onCheckedChange={(checked) => void toggle(checked)}
            />
            <span className="font-medium">Users can approve their own plans</span>
          </label>
        )}
      </CardContent>
    </Card>
  )
}
