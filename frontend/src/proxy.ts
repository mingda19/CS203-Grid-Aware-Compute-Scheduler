import { NextResponse } from "next/server"
import type { NextRequest } from "next/server"

export function proxy(request: NextRequest) {
  const { pathname } = request.nextUrl

  // Ignore internal Next.js assets, public assets, or APIs
  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.includes("favicon.ico") ||
    pathname.match(/\.(png|jpg|jpeg|gif|webp|svg|ico|mp4)$/)
  ) {
    return NextResponse.next()
  }

  const isLoggedIn = request.cookies.get("gacs_logged_in")?.value === "true"

  // The client verifies the session; a stale presence cookie must not prevent sign-in.
  if (pathname.startsWith("/login")) return NextResponse.next()

  // If visiting any protected page without being logged in, redirect to /login
  if (!isLoggedIn) {
    const loginUrl = new URL("/login", request.url)
    return NextResponse.redirect(loginUrl)
  }

  return NextResponse.next()
}

export const config = {
  matcher: [
    /*
     * Match all request paths except static files
     */
    "/((?!_next/static|_next/image|favicon.ico).*)",
  ],
}
