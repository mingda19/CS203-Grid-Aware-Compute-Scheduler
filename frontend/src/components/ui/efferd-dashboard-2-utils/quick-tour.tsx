"use client"

import * as React from "react"
import * as PopoverPrimitive from "@radix-ui/react-popover"
import { usePathname, useRouter } from "next/navigation"
import { ChevronRight, Sparkles } from "lucide-react"
import { Button } from "@/components/ui/button"

type TourStep = {
  title: string
  description: string
  /** The `data-tour` value of the element to highlight. */
  target: string
  /** The page to open first. Left out = stay on the current page. */
  href?: string
  /** Where the card sits. Radix flips it when there is no room. */
  side?: "top" | "right" | "bottom" | "left"
}

/** Each description must make sense without the highlight: a hidden target shows the card centred. */
export const TOUR_STEPS: TourStep[] = [
  { href: "/home", target: "nav", side: "right", title: "Menu", description: "Use the menu to move between areas. On a small screen, open it with the menu button at the top left." },
  { href: "/home", target: "needs-approval", title: "Plans to approve", description: "Shows how many plans wait for a decision and what the newest one saves. Review opens the newest plan." },
  { href: "/home", target: "home-figures", title: "Price and use", description: "Price now is the latest ERCOT price. CPU and GPU scheduled use come from the approved plans." },
  { href: "/home", target: "home-forecast", title: "Price forecast", description: "The expected price for the coming hours. Details opens the Price Forecast page." },
  { href: "/home", target: "upcoming-jobs", title: "Upcoming jobs", description: "The approved jobs that run next." },
  { href: "/jobs", target: "jobs-actions", title: "Add a job", description: "New job opens the job form. The system then makes a plan for the job. Recompute shows when a job still needs a plan, and plans it again." },
  { href: "/jobs", target: "jobs-table", title: "Your jobs", description: "Click a job to see its details. Use the ⋯ menu on a row for more actions. Tick several rows to delete them together." },
  { href: "/approvals", target: "plan-list", title: "Approve plans", description: "Plans waiting for a decision are under Pending. Open one to approve it or ask for changes. You cannot approve your own plan." },
  { href: "/workloads", target: "calendar-toolbar", title: "See the schedule", description: "A calendar of everyone's approved jobs. Move with Today and the arrows, switch between the calendar and the list at the top left, pick Week or Month, search, or show only your jobs." },
  { href: "/forecasts", target: "forecast-switches", title: "Model and window", description: "Pick the forecast model (LEAR makes the plans, LSTM is the fallback) and how many past days the checks use." },
  { href: "/forecasts", target: "key-figures", title: "Key figures", description: "How far off the forecast was on average, which way it leans, and how often it found the cheap hours." },
  { href: "/forecasts", target: "forecast-checks", title: "Forecast checks", description: "This card and the cards below compare the forecast with real prices, so you can see how much to trust it." },
  // The app shell opens the data status panel while this step shows.
  { target: "data-status", side: "left", title: "Compute cluster and data", description: "The top of this panel is the compute cluster: its ERCOT zone and the most CPU and GPU hours it runs per hour. Below, see when each data source last updated. Open it with the status badge in the top bar." },
  { target: "assistant", side: "top", title: "Ask the assistant", description: "Ask how to use GACS. It cannot see your jobs, plans or account, so check important answers in the app." },
  { target: "help", title: "You’re ready", description: "Open this guide again anytime with this button." },
]

/** How long a step waits for its target before it shows the card centred. */
const WAIT_MS = 1500
/** The spotlight's space around the target. */
const PAD = 8

/** The first visible match. This skips the hidden copy of the sidebar and targets hidden on a phone. */
function findTarget(name: string) {
  for (const el of document.querySelectorAll<HTMLElement>(`[data-tour="${name}"]`)) {
    const r = el.getBoundingClientRect()
    if (r.width > 0 && r.height > 0) return el
  }
  return null
}

interface QuickTourProps {
  /** -1 is the welcome card. */
  step: number
  onStep: (step: number) => void
  onClose: (completed: boolean) => void
  firstName?: string
}

/** The spotlight tour: a dim layer with a hole over the step's target, and a card beside it. */
export function QuickTour({ step, onStep, onClose, firstName }: QuickTourProps) {
  const router = useRouter()
  const pathname = usePathname()
  const titleId = React.useId()
  const descriptionId = React.useId()
  const current = step >= 0 ? TOUR_STEPS[step] : undefined
  const target = current?.target

  // Both are keyed by step, so a new step starts clean without a reset.
  const [found, setFound] = React.useState<{ step: number; rect: DOMRect } | null>(null)
  const [lostStep, setLostStep] = React.useState<number | null>(null)
  const rect = found?.step === step ? found.rect : null

  const go = (next: number) => {
    const href = TOUR_STEPS[next]?.href
    if (href && pathname !== href) router.push(href)
    setLostStep(null)
    onStep(next)
  }

  // One frame loop follows the target through scrolling, resizing and cards that grow after they load.
  React.useEffect(() => {
    if (!target) return
    let frame = 0
    let scrolled = false
    let last = ""
    const tick = () => {
      const el = findTarget(target)
      if (el) {
        const r = el.getBoundingClientRect()
        if (!scrolled) {
          scrolled = true
          // Only when part of it is off screen. A target in the sticky header or the fixed sidebar is always on screen.
          if (r.top < 0 || r.bottom > window.innerHeight) {
            const reduce = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches
            el.scrollIntoView({ block: "center", behavior: reduce ? "auto" : "smooth" })
          }
        }
        const key = `${r.top},${r.left},${r.width},${r.height}`
        if (key !== last) {
          last = key
          setFound({ step, rect: r })
        }
      } else if (last) {
        last = ""
        setFound(null)
      }
      frame = requestAnimationFrame(tick)
    }
    frame = requestAnimationFrame(tick)
    const timer = window.setTimeout(() => setLostStep(step), WAIT_MS)
    return () => {
      cancelAnimationFrame(frame)
      window.clearTimeout(timer)
    }
  }, [step, target])

  // Esc closes at every step. A window listener, because at the cluster step the data status panel holds Radix's Esc.
  React.useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose(true)
    }
    window.addEventListener("keydown", onKey)
    return () => window.removeEventListener("keydown", onKey)
  }, [onClose])

  // Give the focus back to what had it before the tour opened.
  React.useEffect(() => {
    const before = document.activeElement
    return () => {
      if (before instanceof HTMLElement) before.focus()
    }
  }, [])

  // Stable, so it runs only when a card element mounts. The key below remounts the card on each step,
  // so a screen reader reads the new title and description.
  const focusCard = React.useCallback((el: HTMLElement | null) => el?.focus({ preventScroll: true }), [])

  const last = step === TOUR_STEPS.length - 1
  const anchored = rect != null
  const showCard = step < 0 || anchored || lostStep === step

  const cardProps = {
    ref: focusCard,
    role: "dialog",
    "aria-modal": true,
    "aria-labelledby": titleId,
    "aria-describedby": descriptionId,
    tabIndex: -1,
    className: "w-[min(24rem,calc(100vw-2rem))] rounded-2xl border border-border bg-card p-5 text-card-foreground shadow-2xl outline-none",
  } as const

  const body = (
    <>
      <div className="mb-3 flex items-center gap-2 text-sm font-semibold text-primary">
        <Sparkles className="h-4 w-4" />
        {current ? `Step ${step + 1} of ${TOUR_STEPS.length}` : "A quick tour"}
      </div>
      <h2 id={titleId} className="text-lg font-semibold leading-7">
        {current ? current.title : `Welcome${firstName ? `, ${firstName}` : ""}!`}
      </h2>
      <p id={descriptionId} className="mt-2 text-sm leading-6 text-muted-foreground">
        {current
          ? current.description
          : "GACS helps you understand energy conditions and plan compute schedules. This short guide shows you where to find the key information."}
      </p>
      <div className="mt-5 flex items-center justify-between gap-3">
        <Button variant="ghost" onClick={() => onClose(true)}>
          {current ? "Skip tour" : "Explore on my own"}
        </Button>
        <div className="flex items-center gap-2">
          {step > 0 && <Button variant="outline" onClick={() => go(step - 1)}>Back</Button>}
          <Button onClick={() => (last ? onClose(true) : go(step + 1))}>
            {current ? (last ? "Finish" : "Next") : "Take the tour"}
            <ChevronRight className="ml-1 h-4 w-4" />
          </Button>
        </div>
      </div>
      {current && (
        <div className="mt-4 flex justify-center gap-1.5" aria-label={`Step ${step + 1} of ${TOUR_STEPS.length}`}>
          {TOUR_STEPS.map((s, index) => (
            <span key={s.target} className={`h-1.5 w-3 rounded-full ${index === step ? "bg-primary" : "bg-muted"}`} />
          ))}
        </div>
      )}
    </>
  )

  return (
    <>
      {/* Stops clicks on the page. It dims the page itself only when there is no spotlight. */}
      <div className={`fixed inset-0 z-[60] ${anchored ? "" : "bg-black/60"}`} role="presentation" />

      {rect && (
        <div
          aria-hidden="true"
          className="pointer-events-none fixed z-[61] rounded-lg ring-2 ring-primary"
          style={{
            top: rect.top - PAD,
            left: rect.left - PAD,
            width: rect.width + PAD * 2,
            height: rect.height + PAD * 2,
            boxShadow: "0 0 0 9999px rgb(0 0 0 / 0.6)",
          }}
        />
      )}

      {showCard && (anchored ? (
        <PopoverPrimitive.Root open>
          <PopoverPrimitive.Anchor virtualRef={{ current: { getBoundingClientRect: () => rect } }} />
          <PopoverPrimitive.Portal>
            <PopoverPrimitive.Content
              key={step}
              {...cardProps}
              side={current?.side ?? "bottom"}
              sideOffset={16}
              collisionPadding={16}
              updatePositionStrategy="always"
              onInteractOutside={(e) => e.preventDefault()}
              onEscapeKeyDown={(e) => e.preventDefault()}
              onOpenAutoFocus={(e) => e.preventDefault()}
              className={`z-[62] ${cardProps.className}`}
            >
              {body}
            </PopoverPrimitive.Content>
          </PopoverPrimitive.Portal>
        </PopoverPrimitive.Root>
      ) : (
        <div className="pointer-events-none fixed inset-0 z-[62] flex items-center justify-center p-4">
          <section key={step} {...cardProps} className={`pointer-events-auto ${cardProps.className}`}>
            {body}
          </section>
        </div>
      ))}
    </>
  )
}
