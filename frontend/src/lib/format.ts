/** Whole dollars for large sums, cents for small ones. The sign stays: savings can be negative. */
export function money(usd: number) {
  const digits = Math.abs(usd) >= 100 ? 0 : 2
  return usd.toLocaleString("en-US", { style: "currency", currency: "USD", maximumFractionDigits: digits })
}
