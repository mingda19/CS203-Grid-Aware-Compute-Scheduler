"use client"

import * as React from "react"
import { BriefcaseBusiness, Eye, Pencil, Plus, RefreshCw, Trash2 } from "lucide-react"
import { jobsApi, type ComputeJob, type ComputeJobPayload } from "@/lib/api"
import { formatSgtDateTime, SGT_TIMEZONE } from "@/lib/date-utils"
import { Button } from "@/components/ui/button"
import { Input } from "@/components/ui/input"
import { Card, CardContent, CardHeader, CardTitle } from "@/components/ui/card"
import { Checkbox } from "@/components/ui/checkbox"
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from "@/components/ui/dialog"

type FormState = { name: string; cpuHours: string; gpuHours: string; originalStartTime: string; deadline: string; flexibilityWindowHours: string; isExempt: boolean }
const blankForm = (): FormState => ({ name: "", cpuHours: "0", gpuHours: "0", originalStartTime: "", deadline: "", flexibilityWindowHours: "0", isExempt: false })
// The API uses timezone-free LocalDateTime strings that represent UTC. Convert
// them explicitly so datetime-local inputs always show Singapore wall time.
const toInputDate = (value?: string) => {
  if (!value) return ""
  const iso = value.endsWith("Z") || value.includes("+") ? value : `${value}Z`
  const parts = new Intl.DateTimeFormat("en-CA", {
    timeZone: SGT_TIMEZONE, year: "numeric", month: "2-digit", day: "2-digit",
    hour: "2-digit", minute: "2-digit", hourCycle: "h23",
  }).formatToParts(new Date(iso))
  const part = (type: string) => parts.find(item => item.type === type)?.value ?? ""
  return `${part("year")}-${part("month")}-${part("day")}T${part("hour")}:${part("minute")}`
}
const toApiUtcDate = (value: string) => {
  const [date, time] = value.split("T")
  const [year, month, day] = date.split("-").map(Number)
  const [hour, minute] = time.split(":").map(Number)
  const utc = new Date(Date.UTC(year, month - 1, day, hour - 8, minute))
  return utc.toISOString().slice(0, 19)
}
const displayDate = (value?: string) => value ? formatSgtDateTime(value) : "—"

export default function JobsPage() {
  const [jobs, setJobs] = React.useState<ComputeJob[]>([])
  const [loading, setLoading] = React.useState(true)
  const [saving, setSaving] = React.useState(false)
  const [error, setError] = React.useState("")
  const [formOpen, setFormOpen] = React.useState(false)
  const [viewOpen, setViewOpen] = React.useState(false)
  const [editing, setEditing] = React.useState<ComputeJob | null>(null)
  const [viewing, setViewing] = React.useState<ComputeJob | null>(null)
  const [form, setForm] = React.useState<FormState>(blankForm)

  const loadJobs = React.useCallback(async () => {
    setLoading(true); setError("")
    try { const response = await jobsApi.list(); setJobs(response.data ?? []) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load jobs") }
    finally { setLoading(false) }
  }, [])
  React.useEffect(() => { void loadJobs() }, [loadJobs])

  const openCreate = () => { setEditing(null); setForm(blankForm()); setError(""); setFormOpen(true) }
  const openEdit = (job: ComputeJob) => {
    setEditing(job); setError("")
    setForm({ name: job.name, cpuHours: String(job.cpuHours), gpuHours: String(job.gpuHours), originalStartTime: toInputDate(job.originalStartTime), deadline: toInputDate(job.deadline), flexibilityWindowHours: String(job.flexibilityWindowHours), isExempt: job.isExempt })
    setFormOpen(true)
  }
  const openView = async (job: ComputeJob) => {
    setError("")
    try { const response = await jobsApi.get(job.id); setViewing(response.data ?? job); setViewOpen(true) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not load job details") }
  }
  const submit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault(); setError("")
    const cpuHours = Number(form.cpuHours), gpuHours = Number(form.gpuHours), flexibilityWindowHours = Number(form.flexibilityWindowHours)
    if (cpuHours === 0 && gpuHours === 0) { setError("Enter CPU hours, GPU hours, or both."); return }
    if (form.deadline < form.originalStartTime) { setError("Deadline must be at or after the original start time."); return }
    const payload: ComputeJobPayload = { name: form.name.trim(), cpuHours, gpuHours, originalStartTime: toApiUtcDate(form.originalStartTime), deadline: toApiUtcDate(form.deadline), flexibilityWindowHours, isExempt: form.isExempt }
    setSaving(true)
    try {
      if (editing) await jobsApi.update(editing.id, payload)
      else await jobsApi.create(payload)
      setFormOpen(false); await loadJobs()
    } catch (e) { setError(e instanceof Error ? e.message : "Could not save job") }
    finally { setSaving(false) }
  }
  const remove = async (job: ComputeJob) => {
    if (!window.confirm(`Delete “${job.name}”? This cannot be undone.`)) return
    setError("")
    try { await jobsApi.remove(job.id); setJobs(current => current.filter(item => item.id !== job.id)) }
    catch (e) { setError(e instanceof Error ? e.message : "Could not delete job") }
  }

  return <div className="space-y-6" id="jobs-root">
    <div className="flex flex-col gap-4 rounded-2xl border border-border bg-gradient-to-r from-card to-card/60 p-6 shadow-xs sm:flex-row sm:items-center sm:justify-between">
      <div><div className="flex items-center gap-2"><BriefcaseBusiness className="h-5 w-5 text-emerald-500"/><h1 className="text-2xl font-bold tracking-tight">Compute Jobs</h1></div><p className="mt-1 text-sm text-muted-foreground">Create and manage the jobs submitted to the scheduler.</p></div>
      <div className="flex gap-2"><Button variant="outline" onClick={() => void loadJobs()} disabled={loading} aria-label="Refresh jobs"><RefreshCw className="mr-2 h-4 w-4"/>Refresh</Button><Button onClick={openCreate} className="bg-emerald-600 text-white hover:bg-emerald-700"><Plus className="mr-2 h-4 w-4"/>New job</Button></div>
    </div>
    {error && <div role="alert" className="rounded-md border border-destructive/40 bg-destructive/10 px-4 py-3 text-sm text-destructive">{error}</div>}
    <Card><CardHeader><CardTitle className="text-lg">Your jobs</CardTitle></CardHeader><CardContent>
      {loading ? <p className="py-8 text-center text-sm text-muted-foreground">Loading jobs…</p> : jobs.length === 0 ? <div className="py-10 text-center"><p className="font-medium">No compute jobs yet</p><p className="mt-1 text-sm text-muted-foreground">Create a job to see it here.</p></div> : <div className="overflow-x-auto"><table className="w-full min-w-[760px] text-left text-sm"><thead><tr className="border-b text-xs text-muted-foreground"><th className="px-3 py-3">Name</th><th className="px-3 py-3">CPU / GPU hours</th><th className="px-3 py-3">Original start (SGT)</th><th className="px-3 py-3">Deadline (SGT)</th><th className="px-3 py-3">Flexibility</th><th className="px-3 py-3">Status</th><th className="px-3 py-3">Actions</th></tr></thead><tbody>{jobs.map(job => <tr key={job.id} className="border-b last:border-0"><td className="px-3 py-3 font-medium">{job.name}</td><td className="px-3 py-3">{job.cpuHours} CPU · {job.gpuHours} GPU</td><td className="px-3 py-3">{displayDate(job.originalStartTime)}</td><td className="px-3 py-3">{displayDate(job.deadline)}</td><td className="px-3 py-3">{job.isExempt ? "Exempt" : `${job.flexibilityWindowHours}h`}</td><td className="px-3 py-3"><span className="rounded-full bg-muted px-2 py-1 text-xs">{job.status}</span></td><td className="px-3 py-3"><div className="flex gap-1"><Button size="icon" variant="ghost" aria-label={`View ${job.name}`} onClick={() => void openView(job)}><Eye className="h-4 w-4"/></Button><Button size="icon" variant="ghost" aria-label={`Edit ${job.name}`} onClick={() => openEdit(job)}><Pencil className="h-4 w-4"/></Button><Button size="icon" variant="ghost" aria-label={`Delete ${job.name}`} onClick={() => void remove(job)}><Trash2 className="h-4 w-4 text-destructive"/></Button></div></td></tr>)}</tbody></table></div>}
    </CardContent></Card>

    <Dialog open={formOpen} onOpenChange={setFormOpen}><DialogContent className="sm:max-w-xl"><form onSubmit={submit}><DialogHeader><DialogTitle>{editing ? "Edit compute job" : "Create compute job"}</DialogTitle><DialogDescription>Enter resource requirements and the allowed scheduling window. Times are shown in Singapore time (SGT).</DialogDescription></DialogHeader>
      <div className="grid gap-4 py-5 sm:grid-cols-2">
        <Field label="Job name"><Input value={form.name} maxLength={255} required onChange={e => setForm({ ...form, name: e.target.value })} placeholder="e.g. Model fine-tuning run"/></Field>
        <div className="grid grid-cols-2 gap-3"><Field label="CPU hours"><Input type="number" min="0" step="any" required value={form.cpuHours} onChange={e => setForm({ ...form, cpuHours: e.target.value })}/></Field><Field label="GPU hours"><Input type="number" min="0" step="any" required value={form.gpuHours} onChange={e => setForm({ ...form, gpuHours: e.target.value })}/></Field></div>
        <Field label="Original start time (SGT)"><Input type="datetime-local" required value={form.originalStartTime} onChange={e => setForm({ ...form, originalStartTime: e.target.value })}/></Field>
        <Field label="Deadline (SGT)"><Input type="datetime-local" required value={form.deadline} onChange={e => setForm({ ...form, deadline: e.target.value })}/></Field>
        <Field label="Flexibility window (hours)"><Input type="number" min="0" step="1" required value={form.flexibilityWindowHours} onChange={e => setForm({ ...form, flexibilityWindowHours: e.target.value })}/></Field>
        <label className="flex items-center gap-3 self-end rounded-md border p-3 text-sm"><Checkbox checked={form.isExempt} onCheckedChange={isExempt => setForm({ ...form, isExempt })}/><span><span className="block font-medium">Exempt from rescheduling</span><span className="text-xs text-muted-foreground">Keep this job at its original start time.</span></span></label>
      </div>
      {error && <p role="alert" className="mb-3 text-sm text-destructive">{error}</p>}
      <DialogFooter><Button type="button" variant="outline" onClick={() => setFormOpen(false)}>Cancel</Button><Button type="submit" disabled={saving} className="bg-emerald-600 text-white hover:bg-emerald-700">{saving ? "Saving…" : editing ? "Save changes" : "Create job"}</Button></DialogFooter>
    </form></DialogContent></Dialog>

    <Dialog open={viewOpen} onOpenChange={setViewOpen}><DialogContent><DialogHeader><DialogTitle>{viewing?.name ?? "Job details"}</DialogTitle><DialogDescription>Compute job details returned by the API.</DialogDescription></DialogHeader>{viewing && <dl className="grid grid-cols-2 gap-x-4 gap-y-3 py-3 text-sm"><Detail label="Job ID" value={String(viewing.id)}/><Detail label="Status" value={viewing.status}/><Detail label="CPU hours" value={String(viewing.cpuHours)}/><Detail label="GPU hours" value={String(viewing.gpuHours)}/><Detail label="Original start" value={displayDate(viewing.originalStartTime)}/><Detail label="Deadline" value={displayDate(viewing.deadline)}/><Detail label="Flexibility" value={`${viewing.flexibilityWindowHours} hours`}/><Detail label="Exempt" value={viewing.isExempt ? "Yes" : "No"}/></dl>}<DialogFooter><Button onClick={() => setViewOpen(false)}>Close</Button></DialogFooter></DialogContent></Dialog>
  </div>
}

function Field({ label, children }: { label: string; children: React.ReactNode }) { return <label className="space-y-1.5 text-sm"><span className="block font-medium">{label}</span>{children}</label> }
function Detail({ label, value }: { label: string; value: string }) { return <div><dt className="text-xs text-muted-foreground">{label}</dt><dd className="mt-1 font-medium">{value}</dd></div> }
