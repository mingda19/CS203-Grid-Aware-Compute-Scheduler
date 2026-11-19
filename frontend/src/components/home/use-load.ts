"use client"

import * as React from "react"
import type { ApiResponse } from "@/lib/api"

/** Unwraps an API response, or throws so `useLoad` can show its error state. */
export async function unwrap<T>(request: Promise<ApiResponse<T>>, fallback: string): Promise<T> {
  const response = await request
  if (!response.success || response.data === undefined) throw new Error(response.message || fallback)
  return response.data
}

interface LoadState<T> { data: T | null; error: boolean; loading: boolean; /** ms of the last successful load; a failed quiet refresh leaves it, so callers can tell the data is old. */ updatedAt: number | null }

/**
 * Loads one block of data for the Home page. Every block has a loading, error and ready state.
 * `key` is null while the block waits for something else (for example the facility). A new `key` reloads.
 * `pollMs` refreshes the data quietly: no skeleton, and an old value stays if a refresh fails.
 */
export function useLoad<T>(key: string | null, load: () => Promise<T>, pollMs?: number) {
  const [state, setState] = React.useState<LoadState<T>>({ data: null, error: false, loading: true, updatedAt: null })
  const [attempt, setAttempt] = React.useState(0)
  const loadRef = React.useRef(load)
  React.useEffect(() => { loadRef.current = load })

  React.useEffect(() => {
    if (key === null) return
    let active = true
    const run = (quiet: boolean) => {
      loadRef.current().then(
        (data) => { if (active) setState({ data, error: false, loading: false, updatedAt: Date.now() }) },
        () => { if (active && !quiet) setState({ data: null, error: true, loading: false, updatedAt: null }) },
      )
    }
    run(false)
    const timer = pollMs ? window.setInterval(() => run(true), pollMs) : undefined
    return () => { active = false; if (timer) window.clearInterval(timer) }
  }, [key, attempt, pollMs])

  const reload = React.useCallback(() => {
    setState({ data: null, error: false, loading: true, updatedAt: null })
    setAttempt((n) => n + 1)
  }, [])

  return { ...state, reload }
}
