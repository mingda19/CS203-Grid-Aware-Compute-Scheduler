import { useSyncExternalStore } from "react"

const mediaQuery = "(max-width: 767px)"
const subscribe = (onChange: () => void) => {
  const media = window.matchMedia(mediaQuery)
  media.addEventListener("change", onChange)
  return () => media.removeEventListener("change", onChange)
}
const snapshot = () => window.matchMedia(mediaQuery).matches
const serverSnapshot = () => false

export function useIsMobile() {
  return useSyncExternalStore(subscribe, snapshot, serverSnapshot)
}
