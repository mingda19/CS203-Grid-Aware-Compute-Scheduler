/**
 * Filename from a Content-Disposition header (RFC 6266). Prefers the RFC 5987 `filename*` form over
 * `filename`, and keeps only the last path segment so a server value cannot point outside the download folder.
 * Returns null when the header is missing or names no file.
 */
export function filenameFromContentDisposition(header: string | null | undefined): string | null {
  if (!header) return null

  const extended = /filename\*\s*=\s*([^']*)'[^']*'([^;]+)/i.exec(header)
  if (extended) {
    try {
      const decoded = basename(decodeURIComponent(extended[2].trim()))
      if (decoded) return decoded
    } catch {
      // Malformed percent-encoding: fall back to the plain filename parameter.
    }
  }

  const quoted = /filename\s*=\s*"((?:\\.|[^"\\])*)"/i.exec(header)
  if (quoted) return basename(quoted[1].replace(/\\(.)/g, "$1")) || null

  const token = /filename\s*=\s*([^;\s]+)/i.exec(header)
  return token ? basename(token[1]) || null : null
}

function basename(name: string): string {
  return name.split(/[/\\]/).pop()?.trim() ?? ""
}

/** Saves a blob as a file by clicking a temporary link. Browser only. */
export function saveBlob(blob: Blob, filename: string): void {
  const url = URL.createObjectURL(blob)
  const link = document.createElement("a")
  link.href = url
  link.download = filename
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  // Revoke after the click has been handled; some browsers read the URL asynchronously.
  setTimeout(() => URL.revokeObjectURL(url), 0)
}
