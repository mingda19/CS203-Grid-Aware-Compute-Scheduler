import * as React from "react"

type Point = [number, number]

/**
 * Builds a sharp, tapered zigzag polygon around a centerline: `halfWidths[i]` is the half thickness at
 * vertex i, mitered at the bends. A half width of 0 at an end gives a needle point.
 */
function taperedZigzag(points: Point[], halfWidths: number[]): string {
  const unit = ([x, y]: Point): Point => {
    const len = Math.hypot(x, y)
    return [x / len, y / len]
  }
  const dirs = points.slice(1).map((p, i) => unit([p[0] - points[i][0], p[1] - points[i][1]]))
  const normal = ([dx, dy]: Point): Point => [-dy, dx]
  const left: Point[] = []
  const right: Point[] = []
  points.forEach((p, i) => {
    const hw = halfWidths[i]
    let offset: Point
    if (i === 0 || i === points.length - 1) {
      const n = normal(dirs[i === 0 ? 0 : dirs.length - 1])
      offset = [n[0] * hw, n[1] * hw]
    } else {
      const n1 = normal(dirs[i - 1])
      const n2 = normal(dirs[i])
      const m = unit([n1[0] + n2[0], n1[1] + n2[1]])
      const scale = hw / (m[0] * n1[0] + m[1] * n1[1]) // miter length
      offset = [m[0] * scale, m[1] * scale]
    }
    left.push([p[0] + offset[0], p[1] + offset[1]])
    right.push([p[0] - offset[0], p[1] - offset[1]])
  })
  return [...left, ...right.reverse()].map(([x, y]) => `${x.toFixed(2)},${y.toFixed(2)}`).join(" ")
}

// Two-kink lightning strike: flat-cut top, needle tip at the bottom.
const BOLT = taperedZigzag(
  [[32, 4], [17, 21], [30, 26], [15, 45]],
  [3.8, 3.2, 2.8, 0],
)

const GRID = [14, 24, 34]

/**
 * GACS mark: a sharp two-kink lightning strike set in a grid cell. Grid lines run behind the bolt and
 * faintly through it, corner ticks frame the cell, and the bolt glows. Grid lines use `currentColor`.
 */
export function GacsLogo({ className }: { className?: string }) {
  const id = React.useId().replace(/:/g, "")
  const fillId = `gacs-fill-${id}`
  const clipId = `gacs-clip-${id}`
  return (
    <svg viewBox="0 0 48 48" fill="none" aria-hidden="true" className={className} style={{ overflow: "visible" }}>
      <defs>
        <linearGradient id={fillId} x1="32" y1="4" x2="15" y2="45" gradientUnits="userSpaceOnUse">
          <stop offset="0" stopColor="#a7f3d0" />
          <stop offset="0.45" stopColor="#10b981" />
          <stop offset="1" stopColor="#047857" />
        </linearGradient>
        <clipPath id={clipId}>
          <polygon points={BOLT} />
        </clipPath>
      </defs>

      {/* Grid cell: faint lines and corner ticks */}
      <g stroke="currentColor" strokeWidth={0.75} opacity={0.35}>
        {GRID.map((n) => (
          <React.Fragment key={n}>
            <path d={`M${n} 3V45`} />
            <path d={`M3 ${n}H45`} />
          </React.Fragment>
        ))}
      </g>
      <g stroke="currentColor" strokeWidth={1.4} strokeLinecap="square" opacity={0.8}>
        <path d="M3 8V3H8M40 3H45V8M45 40V45H40M8 45H3V40" />
      </g>

      {/* Bolt with glow */}
      <polygon
        points={BOLT}
        fill={`url(#${fillId})`}
        stroke="#d1fae5"
        strokeWidth={0.5}
        strokeLinejoin="miter"
        style={{ filter: "drop-shadow(0 0 3px rgba(16,185,129,0.75)) drop-shadow(0 0 8px rgba(16,185,129,0.4))" }}
      />

      {/* Grid lines showing through the bolt */}
      <g clipPath={`url(#${clipId})`} stroke="#ffffff" strokeWidth={0.8} opacity={0.3}>
        {GRID.map((n) => (
          <React.Fragment key={n}>
            <path d={`M${n} 0V48`} />
            <path d={`M0 ${n}H48`} />
          </React.Fragment>
        ))}
      </g>
    </svg>
  )
}
