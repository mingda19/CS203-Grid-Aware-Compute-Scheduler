import type { LucideIcon } from "lucide-react"
import { cn } from "@/lib/utils"

/** Four tones, all from theme tokens. The icon and the text carry the meaning, never the colour alone. */
export type StatusTone = "neutral" | "primary" | "warning" | "destructive"

const TONES: Record<StatusTone, string> = {
  neutral: "bg-muted text-muted-foreground",
  primary: "bg-primary/10 text-primary",
  warning: "bg-warning/15 text-warning-foreground",
  destructive: "bg-destructive/10 text-destructive-foreground",
}

export interface StatusInfo {
  label: string
  icon: LucideIcon
  tone: StatusTone
}

/** The one status pill for jobs, plans and calendar entries. It never wraps and never shrinks. */
export function StatusBadge({ label, icon: Icon, tone, className }: StatusInfo & { className?: string }) {
  return (
    <span className={cn("inline-flex shrink-0 items-center gap-1 whitespace-nowrap rounded-full px-2 py-0.5 text-xs font-medium", TONES[tone], className)}>
      <Icon className="size-3 shrink-0" aria-hidden="true" />
      {label}
    </span>
  )
}
