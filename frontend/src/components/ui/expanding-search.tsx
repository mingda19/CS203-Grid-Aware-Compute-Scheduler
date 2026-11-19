"use client"

import * as React from "react"
import { Search } from "lucide-react"
import { cn } from "@/lib/utils"
import { Input } from "@/components/ui/input"
import { Toggle } from "@/components/ui/toggle"

interface ExpandingSearchProps {
  value: string
  onChange: (value: string) => void
  /** Names the box for screen readers and is its placeholder, for example "Search jobs". */
  label: string
}

/** The magnifier that slides a search box out to its left, as on the Schedule page. Closing it clears the search. */
export function ExpandingSearch({ value, onChange, label }: ExpandingSearchProps) {
  const [open, setOpen] = React.useState(false)
  const inputRef = React.useRef<HTMLInputElement>(null)
  React.useEffect(() => { if (open) inputRef.current?.focus() }, [open])
  const close = () => { setOpen(false); onChange("") }

  return (
    <div className="flex min-w-0 items-center gap-2">
      <div
        aria-hidden={!open}
        className={cn(
          "min-w-0 overflow-hidden transition-[width,opacity] duration-300 ease-out motion-reduce:transition-none",
          open ? "w-44 opacity-100 sm:w-64" : "w-0 opacity-0",
        )}
      >
        <Input
          ref={inputRef}
          type="search"
          tabIndex={open ? 0 : -1}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onKeyDown={(e) => { if (e.key === "Escape") close() }}
          placeholder={label}
          aria-label={label}
          className="h-9 focus-visible:ring-inset focus-visible:ring-offset-0"
        />
      </div>
      <Toggle
        size="sm"
        pressed={open}
        onPressedChange={(next) => (next ? setOpen(true) : close())}
        aria-label="Search"
        title="Search"
        className="shrink-0 px-0 data-[state=on]:bg-primary/15 data-[state=on]:text-foreground"
      >
        <Search />
      </Toggle>
    </div>
  )
}
