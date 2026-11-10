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

/** The one segmented control: joined outline buttons, the chosen one tinted with the accent. */
export function Segmented<T extends string>({ value, onChange, options, label, className }: SegmentedProps<T>) {
  return (
    <ToggleGroup
      type="single"
      variant="outline"
      size="sm"
      className={cn("gap-0", className)}
      value={value}
      // Clicking the chosen item again would clear a single-select group. A segmented control always has a value.
      onValueChange={(next) => { if (next) onChange(next as T) }}
      aria-label={label}
    >
      {options.map((option) => (
        <ToggleGroupItem
          key={option.value}
          value={option.value}
          className="rounded-none first:rounded-l-md last:rounded-r-md [&:not(:first-child)]:-ml-px data-[state=on]:bg-primary/15 data-[state=on]:text-foreground"
        >
          {option.label}
        </ToggleGroupItem>
      ))}
    </ToggleGroup>
  )
}
