import { afterEach, expect, it, vi } from "vitest"

afterEach(() => { vi.unstubAllEnvs(); vi.unstubAllGlobals(); vi.resetModules() })

it("does not silently use localhost when a production API URL is missing", async () => {
  vi.stubEnv("NODE_ENV", "production")
  vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "")
  vi.stubEnv("NEXT_PUBLIC_API_URL", "")
  vi.resetModules()
  const fetchMock = vi.fn()
  vi.stubGlobal("fetch", fetchMock)
  const { authApi } = await import("./api")
  await expect(authApi.login({ email: "operator@example.com", password: "test" })).rejects.toThrow("not connected to its API")
  expect(fetchMock).not.toHaveBeenCalled()
})
