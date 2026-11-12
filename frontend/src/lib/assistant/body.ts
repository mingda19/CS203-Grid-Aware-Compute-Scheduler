/**
 * Reads a request body with a hard byte limit, so an oversized upload is refused before it is
 * all in memory. `request.text()` would buffer everything first and only then let us look at it.
 */

export type BodyResult =
  | { ok: true; text: string }
  | { ok: false; reason: "too_large" | "unreadable" }

export async function readBodyLimited(request: Request, maxBytes: number): Promise<BodyResult> {
  // A declared size over the limit is refused without reading a byte. A missing or false header
  // is not trusted: the stream below is counted either way.
  const declared = request.headers.get("content-length")
  if (declared !== null && /^\d+$/.test(declared.trim()) && Number(declared) > maxBytes) {
    return { ok: false, reason: "too_large" }
  }
  if (!request.body) return { ok: true, text: "" }

  const reader = request.body.getReader()
  const chunks: Uint8Array[] = []
  let total = 0
  try {
    for (;;) {
      const { value, done } = await reader.read()
      if (done) break
      total += value.byteLength
      if (total > maxBytes) {
        // Stop the sender; nothing past the limit is kept.
        await reader.cancel().catch(() => undefined)
        return { ok: false, reason: "too_large" }
      }
      chunks.push(value)
    }
  } catch {
    return { ok: false, reason: "unreadable" }
  }

  const bytes = new Uint8Array(total)
  let offset = 0
  for (const chunk of chunks) {
    bytes.set(chunk, offset)
    offset += chunk.byteLength
  }
  try {
    return { ok: true, text: new TextDecoder("utf-8", { fatal: true }).decode(bytes) }
  } catch {
    return { ok: false, reason: "unreadable" }
  }
}
