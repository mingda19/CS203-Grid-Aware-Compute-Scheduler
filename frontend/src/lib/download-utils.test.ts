import { describe, it, expect } from "vitest"
import { filenameFromContentDisposition } from "./download-utils"

describe("filenameFromContentDisposition", () => {
  it("reads the quoted filename Spring sends", () => {
    expect(
      filenameFromContentDisposition('attachment; filename="schedule-run-7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44.csv"'),
    ).toBe("schedule-run-7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44.csv")
  })

  it("reads unquoted and escaped filenames", () => {
    expect(filenameFromContentDisposition("attachment; filename=schedule-run-1.json")).toBe("schedule-run-1.json")
    expect(filenameFromContentDisposition('attachment; filename="a \\"b\\".csv"')).toBe('a "b".csv')
  })

  it("prefers the RFC 5987 filename* form", () => {
    expect(
      filenameFromContentDisposition("attachment; filename=\"fallback.csv\"; filename*=UTF-8''r%C3%A9sum%C3%A9.csv"),
    ).toBe("résumé.csv")
  })

  it("falls back to filename when filename* is malformed", () => {
    expect(filenameFromContentDisposition("attachment; filename*=UTF-8''bad%E0%A4.csv; filename=\"ok.csv\"")).toBe(
      "ok.csv",
    )
  })

  it("drops path segments", () => {
    expect(filenameFromContentDisposition('attachment; filename="../../etc/passwd"')).toBe("passwd")
    expect(filenameFromContentDisposition('attachment; filename="C:\\\\temp\\\\x.csv"')).toBe("x.csv")
  })

  it("returns null when there is no usable filename", () => {
    expect(filenameFromContentDisposition(null)).toBeNull()
    expect(filenameFromContentDisposition("attachment")).toBeNull()
    expect(filenameFromContentDisposition('attachment; filename=""')).toBeNull()
  })
})
