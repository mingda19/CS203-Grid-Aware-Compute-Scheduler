export interface User {
  id: number
  email: string
  fullName?: string
  role: string
  isVerified?: boolean
  verified?: boolean
}

export interface AuthResponse {
  success: boolean
  message: string
  user?: User
  requiresOtp?: boolean
  email?: string
  accessToken?: string
  tokenType?: string
  expiresIn?: number
  refreshExpiresIn?: number
}

export interface ApiResponse<T = any> {
  success: boolean
  message: string
  data?: T
}

export interface PricePoint {
  intervalStartUtc: string
  sppUsdMwh: number | null
}

export interface PriceHistory {
  points: PricePoint[]
  totalRecords: number
  limit: number
  offset: number
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080"

// In-memory access token storage (secure against XSS exfiltration from localStorage)
let inMemoryAccessToken: string | null = null

export function setAccessToken(token: string | null): void {
  inMemoryAccessToken = token
}

export function getAccessToken(): string | null {
  return inMemoryAccessToken
}

async function fetchJson<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = `${API_BASE_URL}${endpoint}`
  const headers: Record<string, string> = {
    "Content-Type": "application/json",
    Accept: "application/json",
    ...((options.headers as Record<string, string>) || {}),
  }

  const isPublicAuthEndpoint =
    endpoint.includes("/login") ||
    endpoint.includes("/refresh") ||
    endpoint.includes("/register") ||
    endpoint.includes("/verify-otp") ||
    endpoint.includes("/resend-otp")

  // If in-memory access token is missing on a protected route, attempt restoring it from the HttpOnly cookie
  if (!inMemoryAccessToken && !isPublicAuthEndpoint && typeof window !== "undefined") {
    try {
      const refreshed = await authApi.refreshToken()
      if (refreshed.accessToken) {
        setAccessToken(refreshed.accessToken)
      }
    } catch {
      // Cookie might be expired or not present
    }
  }

  // Attach JWT Bearer token if available
  if (inMemoryAccessToken && !headers["Authorization"]) {
    headers["Authorization"] = `Bearer ${inMemoryAccessToken}`
  }

  const response = await fetch(url, {
    ...options,
    headers,
    credentials: "include", // for HttpOnly refresh-token cookies
  })

  // Automatic token refresh on 401 Unauthorized or 403 Forbidden for authenticated endpoints
  if (
    (response.status === 401 || response.status === 403) &&
    !endpoint.includes("/login") &&
    !endpoint.includes("/refresh") &&
    !endpoint.includes("/register") &&
    !endpoint.includes("/verify-otp") &&
    !endpoint.includes("/resend-otp")
  ) {
    try {
      const refreshed = await authApi.refreshToken()
      if (refreshed.accessToken) {
        setAccessToken(refreshed.accessToken)
        headers["Authorization"] = `Bearer ${refreshed.accessToken}`
        const retryResponse = await fetch(url, {
          ...options,
          headers,
          credentials: "include",
        })
        const retryData = await retryResponse.json().catch(() => ({}))
        if (!retryResponse.ok) {
          const err = retryData?.message || `Request failed with status ${retryResponse.status}`
          throw new Error(err)
        }
        return retryData as T
      }
    } catch {
      // Refresh failed, proceed to handle original response error
    }
  }

  const data = await response.json().catch(() => ({}))

  if (!response.ok) {
    const errorMessage = data?.message || `Request failed with status ${response.status}`
    throw new Error(errorMessage)
  }

  return data as T
}

export const priceApi = {
  getHistory(params: { location: string; startDate: string; endDate: string; limit?: number; offset?: number }): Promise<ApiResponse<PriceHistory>> {
    const query = new URLSearchParams({
      location: params.location,
      startDate: params.startDate,
      endDate: params.endDate,
      limit: String(params.limit ?? 5000),
      offset: String(params.offset ?? 0),
    })
    return fetchJson<ApiResponse<PriceHistory>>(`/api/prices/history?${query.toString()}`, { cache: "no-store" })
  },
}

export const authApi = {
  async register(payload: {
    email: string
    password: string
    fullName?: string
  }): Promise<AuthResponse> {
    return fetchJson<AuthResponse>("/register", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },

  async verifyOtp(payload: { email: string; otp: string }): Promise<AuthResponse> {
    return fetchJson<AuthResponse>("/verify-otp", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },

  async resendOtp(payload: { email: string }): Promise<ApiResponse<string>> {
    return fetchJson<ApiResponse<string>>("/resend-otp", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },

  async login(payload: {
    email: string
    password: string
    rememberMe?: boolean
  }): Promise<AuthResponse> {
    const response = await fetchJson<AuthResponse>("/login", {
      method: "POST",
      body: JSON.stringify(payload),
    })

    if (response.accessToken) {
      setAccessToken(response.accessToken)
    }

    // allows navigation to protected routes immediately after login
    if (response.user) {
      setStoredUser(response.user, response.refreshExpiresIn)
    }

    return response
  },

  async refreshToken(): Promise<AuthResponse> {
    const response = await fetchJson<AuthResponse>("/api/auth/refresh", {
      method: "POST",
    })

    if (response.accessToken) {
      setAccessToken(response.accessToken)
    }

    if (response.user) {
      setStoredUser(response.user, response.refreshExpiresIn)
    }

    return response
  },

  async getMe(): Promise<ApiResponse<User>> {
    return fetchJson<ApiResponse<User>>("/api/auth/me", {
      method: "GET",
    })
  },

  async logout(): Promise<ApiResponse<void>> {
    try {
      return await fetchJson<ApiResponse<void>>("/logout", {
        method: "POST",
      })
    } finally {
      setAccessToken(null)
      removeStoredUser()
    }
  },
}

export const adminApi = {
  async getAllUsers(): Promise<ApiResponse<User[]>> {
    return fetchJson<ApiResponse<User[]>>("/api/admin/users", {
      method: "GET",
    })
  },

  async updateUserRole(
    userId: number,
    role: "ROLE_USER" | "ROLE_ADMIN"
  ): Promise<ApiResponse<User>> {
    return fetchJson<ApiResponse<User>>(`/api/admin/users/${userId}/role`, {
      method: "PATCH",
      body: JSON.stringify({ role }),
    })
  },
}

// Client-side UI cache helpers (non-sensitive profile metadata only)
const USER_STORAGE_KEY = "gacs_user"
const COOKIE_NAME = "gacs_logged_in"

export function getStoredUser(): User | null {
  if (typeof window === "undefined") return null
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY)
    if (!raw) return null
    const data = JSON.parse(raw)
    const maxAge = data._maxAgeSeconds && data._maxAgeSeconds > 0 ? data._maxAgeSeconds : 86400
    // Synchronize cookie if missing so Next.js middleware knows the user is logged in
    if (data && !document.cookie.includes(`${COOKIE_NAME}=true`)) {
      document.cookie = `${COOKIE_NAME}=true; path=/; max-age=${maxAge}; SameSite=Lax`
    }
    const { _maxAgeSeconds, ...user } = data
    return user as User
  } catch {
    return null
  }
}

export function setStoredUser(user: User, maxAgeSeconds?: number): void {
  if (typeof window === "undefined") return
  try {
    const payload = { ...user, _maxAgeSeconds: maxAgeSeconds }
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(payload))
    const maxAge = maxAgeSeconds && maxAgeSeconds > 0 ? maxAgeSeconds : 86400
    document.cookie = `${COOKIE_NAME}=true; path=/; max-age=${maxAge}; SameSite=Lax`
  } catch {
    // Ignore storage quota errors
  }
}

export function removeStoredUser(): void {
  if (typeof window === "undefined") return
  try {
    localStorage.removeItem(USER_STORAGE_KEY)
    document.cookie = `${COOKIE_NAME}=; path=/; max-age=0; SameSite=Lax`
  } catch {
    // Ignore
  }
}
