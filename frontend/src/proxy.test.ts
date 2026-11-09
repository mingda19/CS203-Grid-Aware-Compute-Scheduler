import { expect, it } from "vitest"
import { NextRequest } from "next/server"
import { proxy } from "./proxy"

it("allows sign-in even when a stale login-presence cookie remains", () => {
  const response = proxy(new NextRequest("http://localhost:3000/login", { headers: { cookie: "gacs_logged_in=true" } }))
  expect(response.headers.get("location")).toBeNull()
  expect(response.headers.get("x-middleware-next")).toBe("1")
})

it("redirects protected pages to sign-in when there is no session cookie", () => {
  const response = proxy(new NextRequest("http://localhost:3000/home"))
  expect(response.status).toBe(307)
  expect(response.headers.get("location")).toBe("http://localhost:3000/login")
})
