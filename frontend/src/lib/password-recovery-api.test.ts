import { afterEach, expect, it, vi } from "vitest"

afterEach(() => { vi.unstubAllGlobals(); vi.resetModules() })

it("requests password recovery without a session and posts the reset payload", async () => {
  const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify({ success: true }), { status: 200 }))
  vi.stubGlobal("fetch", fetchMock)
  const { authApi } = await import("./api")
  await authApi.forgotPassword({ email: "user@example.com" })
  await authApi.resetPassword({ email: "user@example.com", token: "a".repeat(43), password: "new-password" })
  expect(fetchMock).toHaveBeenCalledTimes(2)
  expect(fetchMock.mock.calls[0][0]).toMatch(/\/forgot-password$/)
  expect(fetchMock.mock.calls[1][0]).toMatch(/\/reset-password$/)
  expect(JSON.parse(fetchMock.mock.calls[1][1].body)).toEqual({ email: "user@example.com", token: "a".repeat(43), password: "new-password" })
})

it("does not refresh or retry a rejected public recovery request", async () => {
  const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify({ message: "Invalid recovery code" }), { status: 401 })))
  vi.stubGlobal("fetch", fetchMock)
  const { authApi } = await import("./api")
  await expect(authApi.resetPassword({ email: "user@example.com", token: "a".repeat(43), password: "new-password" })).rejects.toThrow()
  expect(fetchMock).toHaveBeenCalledTimes(1)
})
