export type ThemeChoice = "light" | "dark" | "system"

const THEME_KEY = "gacs-theme"
const THEME_EVENT = "gacs-theme-change"

/** The saved choice for this device. Dark when nothing is saved or storage is blocked. */
export function readTheme(): ThemeChoice {
  try {
    const saved = window.localStorage.getItem(THEME_KEY)
    if (saved === "light" || saved === "dark" || saved === "system") return saved
  } catch {
    // Storage blocked or no window: use the default.
  }
  return "dark"
}

export function resolveTheme(choice: ThemeChoice): "light" | "dark" {
  if (choice !== "system") return choice
  return window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light"
}

/** Saves the choice and tells every subscriber (the shell and the Settings page). */
export function saveTheme(choice: ThemeChoice) {
  try {
    window.localStorage.setItem(THEME_KEY, choice)
  } catch {
    // Storage blocked: the choice lasts until reload.
  }
  window.dispatchEvent(new Event(THEME_EVENT))
}

/** For useSyncExternalStore. Also picks up changes from other tabs. */
export function subscribeTheme(onChange: () => void) {
  window.addEventListener(THEME_EVENT, onChange)
  window.addEventListener("storage", onChange)
  return () => {
    window.removeEventListener(THEME_EVENT, onChange)
    window.removeEventListener("storage", onChange)
  }
}
