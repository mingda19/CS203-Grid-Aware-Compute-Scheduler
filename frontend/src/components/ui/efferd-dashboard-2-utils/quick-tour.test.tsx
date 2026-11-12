// @vitest-environment jsdom
import * as React from "react"
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react"
import { afterEach, beforeEach, expect, it, vi } from "vitest"
import { QuickTour, TOUR_STEPS } from "./quick-tour"

// jsdom gives every element a 0 x 0 rect, so no target is ever found and each step falls back to the
// centred card after the wait. That is enough to test the step flow and the page changes.

let path = "/jobs"
const push = vi.fn((href: string) => { path = href })
vi.mock("next/navigation", () => ({ useRouter: () => ({ push }), usePathname: () => path }))

beforeEach(() => {
  vi.useFakeTimers()
  path = "/jobs"
  push.mockClear()
})
afterEach(() => {
  cleanup()
  vi.useRealTimers()
})

function Harness({ onClose }: { onClose: (completed: boolean) => void }) {
  const [step, setStep] = React.useState(-1)
  return <QuickTour step={step} onStep={setStep} onClose={onClose} firstName="Ada" />
}

function click(name: string) {
  fireEvent.click(screen.getByRole("button", { name }))
  act(() => { vi.advanceTimersByTime(1500) })
}

it("walks every step, opens each page once, and finishes", () => {
  const onClose = vi.fn()
  render(<Harness onClose={onClose} />)
  expect(screen.getByRole("heading", { name: "Welcome, Ada!" })).toBeTruthy()

  click("Take the tour")
  expect(push.mock.calls).toEqual([["/home"]])
  expect(screen.getByText(`Step 1 of ${TOUR_STEPS.length}`)).toBeTruthy()

  for (let i = 1; i < TOUR_STEPS.length; i++) click("Next")
  expect(push.mock.calls.map(([href]) => href)).toEqual(["/home", "/jobs", "/approvals", "/workloads", "/forecasts"])
  expect(screen.getByRole("heading", { name: TOUR_STEPS.at(-1)!.title })).toBeTruthy()

  click("Finish")
  expect(onClose).toHaveBeenCalledWith(true)
})

it("closes with Esc", () => {
  const onClose = vi.fn()
  render(<Harness onClose={onClose} />)
  fireEvent.keyDown(window, { key: "Escape" })
  expect(onClose).toHaveBeenCalledWith(true)
})
