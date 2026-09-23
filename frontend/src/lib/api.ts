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
}

export interface ApiResponse<T = any> {
  success: boolean
  message: string
  data?: T
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

  // Attach JWT Bearer token if available
  if (inMemoryAccessToken && !headers["Authorization"]) {
    headers["Authorization"] = `Bearer ${inMemoryAccessToken}`
  }

  const response = await fetch(url, {
    ...options,
    headers,
    credentials: "include", // for HttpOnly refresh-token cookies
  })

  const data = await response.json().catch(() => ({}))

  if (!response.ok) {
    const errorMessage = data?.message || `Request failed with status ${response.status}`
    throw new Error(errorMessage)
  }

  return data as T
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

    return response
  },

  async refreshToken(): Promise<AuthResponse> {
    const response = await fetchJson<AuthResponse>("/api/auth/refresh", {
      method: "POST",
    })

    if (response.accessToken) {
      setAccessToken(response.accessToken)
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
    const user = JSON.parse(raw)
    // Synchronize cookie if missing so Next.js middleware knows the user is logged in
    if (user && !document.cookie.includes(`${COOKIE_NAME}=true`)) {
      document.cookie = `${COOKIE_NAME}=true; path=/; max-age=604800; SameSite=Lax`
    }
    return user
  } catch {
    return null
  }
}

export function setStoredUser(user: User): void {
  if (typeof window === "undefined") return
  try {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
    document.cookie = `${COOKIE_NAME}=true; path=/; max-age=604800; SameSite=Lax`
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

