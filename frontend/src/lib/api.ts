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
}

export interface ApiResponse<T = any> {
  success: boolean
  message: string
  data?: T
}

const API_BASE_URL = process.env.NEXT_PUBLIC_API_BASE_URL || "http://localhost:8080"

async function fetchJson<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = `${API_BASE_URL}${endpoint}`
  const headers = {
    "Content-Type": "application/json",
    Accept: "application/json",
    ...(options.headers || {}),
  }

  const response = await fetch(url, {
    ...options,
    headers,
    credentials: "include", // for session cookies
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

  async login(payload: { email: string; password: string }): Promise<AuthResponse> {
    return fetchJson<AuthResponse>("/login", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },

  async logout(): Promise<ApiResponse<void>> {
    return fetchJson<ApiResponse<void>>("/logout", {
      method: "POST",
    })
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

// Client-side session helpers
const USER_STORAGE_KEY = "gacs_user"

export function getStoredUser(): User | null {
  if (typeof window === "undefined") return null
  try {
    const raw = localStorage.getItem(USER_STORAGE_KEY)
    return raw ? JSON.parse(raw) : null
  } catch {
    return null
  }
}

export function setStoredUser(user: User): void {
  if (typeof window === "undefined") return
  try {
    localStorage.setItem(USER_STORAGE_KEY, JSON.stringify(user))
  } catch {
    // Ignore storage quota errors
  }
}

export function removeStoredUser(): void {
  if (typeof window === "undefined") return
  try {
    localStorage.removeItem(USER_STORAGE_KEY)
  } catch {
    // Ignore
  }
}
