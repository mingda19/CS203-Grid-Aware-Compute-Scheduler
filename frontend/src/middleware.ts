import { NextResponse } from "next/server"
import type { NextRequest } from "next/server"

export function middleware(request: NextRequest) {
  const { pathname } = request.nextUrl

  // Ignore internal Next.js assets, public assets, or APIs
  if (
    pathname.startsWith("/_next") ||
    pathname.startsWith("/api") ||
    pathname.includes("favicon.ico") ||
    pathname.match(/\.(png|jpg|jpeg|gif|webp|svg|ico)$/)
  ) {
    return NextResponse.next()
  }

  const isLoggedIn = request.cookies.get("gacs_logged_in")?.value === "true"

  // If attempting to visit /login while already logged in, redirect to dashboard
  if (pathname.startsWith("/login")) {
    if (isLoggedIn) {
      return NextResponse.redirect(new URL("/", request.url))
    }
    return NextResponse.next()
  }

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
