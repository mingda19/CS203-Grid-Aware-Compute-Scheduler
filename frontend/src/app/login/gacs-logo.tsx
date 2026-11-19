/**
 * GACS mark (references/new_logo.png): an open "G" ring in `currentColor` with a primary-green bar from its centre.
 * Geometry measured from the PNG: the ring runs from 39° (counter-clockwise from 3 o'clock)
 * round the left to 0°, where it ends under the bar's round tip.
 */
export function GacsLogo({ className }: { className?: string }) {
  return (
    <svg viewBox="0 0 48 48" fill="none" aria-hidden="true" className={className}>
      <path d="M39.31 11.61A19.7 19.7 0 1 0 43.7 24" stroke="currentColor" strokeWidth={5.3} strokeLinecap="round" />
      <path d="M24 24H43.8" className="stroke-primary" strokeWidth={5.1} strokeLinecap="round" />
      <circle cx={24} cy={24} r={3.9} className="fill-primary" />
    </svg>
  )
}
