"use client"

import * as React from "react"

const VIDEO = "/intro/gacs_intro_green.mp4"
const END_CARD = "/intro/gacs_poster_endcard_green.jpg"
const END_CARD_AT = 40.5 // seconds: end card fully on screen
const FADE_MS = 400

const glass =
  "rounded-full border border-white/25 bg-black/40 backdrop-blur-md text-white transition-colors hover:bg-white/10 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-white/70 cursor-pointer"

export function Intro({ onDone }: { onDone: () => void }) {
  const videoRef = React.useRef<HTMLVideoElement>(null)
  const [reducedMotion] = React.useState(
    () => typeof window !== "undefined" && window.matchMedia("(prefers-reduced-motion: reduce)").matches,
  )
  const [showLogin, setShowLogin] = React.useState(reducedMotion)
  const [muted, setMuted] = React.useState(false)
  const [leaving, setLeaving] = React.useState(false)

  // Try to play with sound; browsers block unmuted autoplay without a prior click, so fall back to muted.
  React.useEffect(() => {
    const v = videoRef.current
    if (!v) return
    v.play().catch(() => {
      v.muted = true
      setMuted(true)
      v.play().catch(() => setShowLogin(true))
    })
  }, [])

  const finish = () => {
    if (leaving) return
    setLeaving(true)
    videoRef.current?.pause()
    setTimeout(onDone, FADE_MS)
  }

  const toggleSound = () => {
    const v = videoRef.current
    if (!v) return
    v.muted = !v.muted
    setMuted(v.muted)
  }

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black transition-opacity ease-out"
      style={{ opacity: leaving ? 0 : 1, transitionDuration: `${FADE_MS}ms` }}
    >
      {/* 16:9 box matching the video's rendered area, so the button can sit just under the tagline */}
      <div className="relative aspect-video" style={{ width: "min(100vw, calc(100vh * 16 / 9))" }}>
        {reducedMotion ? (
          // eslint-disable-next-line @next/next/no-img-element
          <img src={END_CARD} alt="GACS: Same work. Same deadlines. Cheaper hours." className="h-full w-full object-contain" />
        ) : (
          <video
            ref={videoRef}
            src={VIDEO}
            poster={END_CARD}
            playsInline
            className="h-full w-full object-contain"
            onTimeUpdate={e => { if (e.currentTarget.currentTime >= END_CARD_AT) setShowLogin(true) }}
            onEnded={() => setShowLogin(true)}
            onError={() => setShowLogin(true)}
          />
        )}

        <button
          type="button"
          onClick={finish}
          tabIndex={showLogin ? 0 : -1}
          aria-hidden={!showLogin}
          className={`${glass} absolute left-1/2 top-[76%] -translate-x-1/2 -translate-y-1/2 px-7 py-2.5 text-sm font-semibold tracking-wide transition-opacity duration-700 ${showLogin ? "opacity-100" : "pointer-events-none opacity-0"}`}
        >
          Log in
        </button>
      </div>

      {!reducedMotion && (
        <>
          <button type="button" onClick={finish} className={`${glass} absolute right-4 top-4 px-3 py-1 text-xs`}>
            Skip
          </button>
          <button
            type="button"
            onClick={toggleSound}
            aria-pressed={!muted}
            className={`${glass} absolute bottom-4 right-4 px-3 py-1 text-xs`}
          >
            {muted ? "Sound on" : "Sound off"}
          </button>
        </>
      )}
    </div>
  )
}
