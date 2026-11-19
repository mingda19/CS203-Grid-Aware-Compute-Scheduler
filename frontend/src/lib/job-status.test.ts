import { describe, expect, it } from "vitest"
import { canRecompute, entryStatus, isPlanning, jobStatus, planStatus } from "./job-status"

const jobs = (...status: string[]) => status.map((s) => ({ status: s }))

describe("job status", () => {
  it("shows Recompute only for jobs waiting on the system or turned away", () => {
    expect(canRecompute(jobs("SCHEDULED", "AWAITING_APPROVAL", "COMPLETED", "CANCELLED", "RUNNING"))).toBe(false)
    for (const s of ["SCHEDULING", "NEEDS_ATTENTION", "REJECTED"]) expect(canRecompute(jobs("SCHEDULED", s))).toBe(true)
  })
  it("polls only while a job is Scheduling", () => {
    expect(isPlanning(jobs("SCHEDULING"))).toBe(true)
    expect(isPlanning(jobs("NEEDS_ATTENTION", "AWAITING_APPROVAL"))).toBe(false)
  })
  it("names every status in words and falls back to the raw value", () => {
    expect(jobStatus("AWAITING_APPROVAL").label).toBe("Awaiting approval")
    expect(jobStatus("NEEDS_ATTENTION").label).toBe("Needs attention")
    expect(jobStatus("SOMETHING_NEW").label).toBe("SOMETHING_NEW")
  })
  it("gives plans and calendar entries a label, an icon and one of four tones", () => {
    expect(planStatus("PENDING")).toMatchObject({ label: "Pending review", tone: "warning" })
    expect(planStatus("APPROVED").tone).toBe("primary")
    expect(planStatus("STALE").tone).toBe("destructive")
    expect(entryStatus("Running")).toMatchObject({ label: "Running", tone: "primary" })
    expect(entryStatus("Completed").tone).toBe("neutral")
    expect(jobStatus("RUNNING").tone).toBe(entryStatus("Running").tone)
  })
})
