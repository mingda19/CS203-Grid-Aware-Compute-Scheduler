import * as React from "react"

/**
 * Login backdrop, drawn from the intro video's end card: a dark field with a green glow behind the card,
 * the concentric-ring spiral from the logo (with an orange dispatch arc), a faint power-grid with pulsing
 * nodes, and the tagline. Pure CSS/SVG, no assets. Motion stops under prefers-reduced-motion.
 */

const RING_RADII = [70, 110, 150, 190, 230, 270, 310, 350, 390, 430, 470]

// Grid nodes sit on multiples of 100 in the 1600x900 grid viewBox so they stay on line intersections.
const NODES = [
  { x: 300, y: 200, delay: "0s" },
  { x: 1300, y: 300, delay: "1.2s" },
  { x: 500, y: 700, delay: "2.1s" },
  { x: 1100, y: 800, delay: "0.6s" },
  { x: 100, y: 500, delay: "1.8s" },
  { x: 1500, y: 600, delay: "2.7s" },
  { x: 800, y: 100, delay: "3.3s" },
]

const GRID_LINES = (() => {
  const v = Array.from({ length: 17 }, (_, i) => `M${i * 100} 0V900`)
  const h = Array.from({ length: 10 }, (_, i) => `M0 ${i * 100}H1600`)
  return [...v, ...h].join("")
})()

const FADE_MASK = "radial-gradient(circle at center, black 25%, transparent 72%)"

export function LoginBackdrop() {
  return (
    <div aria-hidden="true" className="pointer-events-none absolute inset-0 z-0 overflow-hidden">
      {/* Glow behind the card + edge vignette (dark only) */}
      <div className="absolute inset-0 bg-[radial-gradient(ellipse_60%_50%_at_50%_45%,rgba(16,185,129,0.14),transparent_70%)] [.dark_&]:bg-[radial-gradient(ellipse_60%_50%_at_50%_45%,rgba(16,185,129,0.22),transparent_70%)]" />
      <div className="absolute inset-0 [.dark_&]:bg-[radial-gradient(ellipse_at_center,transparent_45%,rgba(0,0,0,0.55)_100%)]" />

      {/* Power grid: faint lines that fade out toward the edges, with a few pulsing nodes */}
      <svg
        viewBox="0 0 1600 900"
        preserveAspectRatio="xMidYMid slice"
        className="absolute inset-0 h-full w-full text-emerald-700/15 [.dark_&]:text-emerald-400/10"
        style={{ maskImage: FADE_MASK, WebkitMaskImage: FADE_MASK }}
      >
        <path d={GRID_LINES} stroke="currentColor" strokeWidth={1} fill="none" />
        {NODES.map((n) => (
          <circle
            key={`${n.x}-${n.y}`}
            cx={n.x}
            cy={n.y}
            r={4}
            className="animate-pulse fill-emerald-500/70 motion-reduce:animate-none"
            style={{ animationDelay: n.delay, animationDuration: "3.6s" }}
          />
        ))}
      </svg>

      {/* Concentric rings from the logo. Outer layer turns slowly; orange arcs counter-rotate. */}
      <div className="absolute inset-0 flex items-center justify-center" style={{ maskImage: FADE_MASK, WebkitMaskImage: FADE_MASK }}>
        <div className="h-[150vmin] w-[150vmin] animate-[spin_180s_linear_infinite] motion-reduce:animate-none">
          <svg viewBox="0 0 1000 1000" fill="none" className="h-full w-full text-emerald-600/25 [.dark_&]:text-emerald-400/[0.14]">
            {RING_RADII.map((r, i) => {
              const c = 2 * Math.PI * r
              return (
                <circle
                  key={r}
                  cx={500}
                  cy={500}
                  r={r}
                  stroke="currentColor"
                  strokeWidth={1.5}
                  strokeLinecap="round"
                  strokeDasharray={`${c * 0.84} ${c * 0.16}`}
                  transform={`rotate(${i * 23} 500 500)`}
                />
              )
            })}
          </svg>
        </div>
        <div className="absolute h-[150vmin] w-[150vmin] animate-[spin_120s_linear_infinite_reverse] motion-reduce:animate-none">
          <svg viewBox="0 0 1000 1000" fill="none" className="h-full w-full text-orange-500/60 [.dark_&]:text-orange-400/50">
            {[190, 310, 430].map((r, i) => {
              const c = 2 * Math.PI * r
              return (
                <circle
                  key={r}
                  cx={500}
                  cy={500}
                  r={r}
                  stroke="currentColor"
                  strokeWidth={2.5}
                  strokeLinecap="round"
                  strokeDasharray={`${c * 0.045} ${c * 0.955}`}
                  transform={`rotate(${i * 110 + 40} 500 500)`}
                />
              )
            })}
          </svg>
        </div>
      </div>

      {/* Tagline from the end card; only when there is room below the card */}
      <p className="absolute inset-x-0 bottom-6 hidden select-none text-center text-sm tracking-wide text-muted-foreground/70 [@media(min-height:900px)]:block">
        Same work. Same deadlines. <span className="text-emerald-600 [.dark_&]:text-emerald-400">Cheaper hours.</span>
      </p>
    </div>
  )
}
