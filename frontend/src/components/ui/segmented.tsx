"use client"

import { cn } from "@/lib/utils"
import { ToggleGroup, ToggleGroupItem } from "@/components/ui/toggle-group"

interface SegmentedProps<T extends string> {
  value: T
  onChange: (value: T) => void
  options: readonly { value: T; label: string }[]
  /** Names the group for screen readers, for example "Plans to show". */
  label: string
  className?: string
}

// The old Home chart's model switch: a soft bar, the chosen option a raised tab.
// Light mode needs a darker bar and a white tab with a real shadow, or the chosen option barely shows.
const BAR =
  "inline-flex gap-0 rounded-lg border border-muted-foreground/20 bg-muted-foreground/15 p-0.5 " +
  "dark:border-border dark:bg-muted/40"
const ITEM =
  "h-auto min-w-0 rounded-md px-2.5 py-1 text-xs font-medium text-muted-foreground transition-colors hover:bg-transparent hover:text-foreground " +
  "data-[state=on]:bg-card data-[state=on]:font-semibold data-[state=on]:text-foreground data-[state=on]:shadow-sm " +
  "dark:data-[state=on]:bg-background dark:data-[state=on]:shadow-xs"

/** The one segmented control: a soft bar, the chosen option a raised tab. */
export function Segmented<T extends string>({ value, onChange, options, label, className }: SegmentedProps<T>) {
  return (
    <ToggleGroup
      type="single"
      variant="default"
      size="sm"
      className={cn(BAR, className)}
      value={value}
      // Clicking the chosen item again would clear a single-select group. A segmented control always has a value.
      onValueChange={(next) => { if (next) onChange(next as T) }}
      aria-label={label}
    >
      {options.map((option) => (
        <ToggleGroupItem
          key={option.value}
          value={option.value}
          className={ITEM}
        >
          {option.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
