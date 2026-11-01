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
  refreshToken?: string
  tokenType?: string
  expiresIn?: number
  refreshExpiresIn?: number
}

import { filenameFromContentDisposition } from "./download-utils"

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

export interface PriceForecastPoint {
  intervalStartUtc: string
  predictedPrice: number | null
  modelVersion: string
  generatedAt: string
}

export interface PriceForecast {
  points: PriceForecastPoint[]
  horizonHours: number
}

export interface WorkloadSchedule {
  id: number
  title: string
  type: string
  cluster: string
  powerKw: number
  savings: string | null
  status: "Scheduled" | "Running" | "Throttled" | "Completed"
  startUtc: string
  endUtc: string
  notes: string | null
}

export type JobPriority = "CRITICAL" | "HIGH" | "NORMAL" | "LOW"

export interface ComputeJob {
  id: number
  ownerEmail?: string
  name: string
  cpuHours: number
  gpuHours: number
  originalStartTime: string
  deadline: string
  flexibilityWindowHours: number
  isExempt: boolean
  priority?: JobPriority
  rawConstraints?: string | null
  status: string
  createdAt?: string
  updatedAt?: string
}

export type ComputeJobPayload = Pick<ComputeJob,
  "name" | "cpuHours" | "gpuHours" | "originalStartTime" | "deadline" | "flexibilityWindowHours" | "isExempt"
> & { rawConstraints?: string | null; priority?: JobPriority }

export interface FacilityConfig {
  id?: number
  ownerEmail?: string
  name: string
  maxCpuHours: number
  maxGpuHours: number
  location: string
  updatedAt?: string
}

export interface OptimizeJobInput {
  id: number
  name: string
  cpuHours: number
  gpuHours: number
  originalStartTime: string
  deadline: string
  flexibilityWindowHours: number
  isExempt: boolean
  priority?: JobPriority
}

export interface OptimizeRequest {
  jobs: OptimizeJobInput[]
  clusterCapacity?: {
    maxConcurrentCpuHours?: number
    maxConcurrentGpuHours?: number
  }
  forecastLocation?: string
  horizonHours?: number
  forecast?: Array<{
    intervalStartUtc: string
    predictedPrice: number
  }>
  capacityOverrides?: Array<{
    intervalStartUtc: string
    maxCpuHours: number
    maxGpuHours: number
  }>
}

export interface AllocationSlot {
  intervalStartUtc: string
  cpuHours: number
  gpuHours: number
}

export interface ScheduleItem {
  jobId: number
  jobName: string
  baselineStart: string
  scheduledStart: string | null
  scheduledEnd: string | null
  allocatedCpuHours: number
  allocatedGpuHours: number
  allocations: AllocationSlot[]
  perJobStatus: "OPTIMISED" | "UNCHANGED_EXEMPT" | "NO_FEASIBLE_SLOT"
  reason: string | null
  baselineCostUsd: number | null
  optimizedCostUsd: number | null
  savingsUsd: number | null
}

export interface OptimizeResponse {
  runId: string
  status: "SCHEDULED" | "NEEDS_ATTENTION"
  provenOptimal: boolean
  baselineCostUsd: number
  optimizedCostUsd: number
  projectedSavingsUsd: number
  projectedSavingsPercent: number
  plainEnglishExplanation: string
  usedSeededFallbackPrices: boolean
  items: ScheduleItem[]
}

export interface ExplanationResponse {
  runId: string
  explanation: string
}

export interface CreateWorkloadSchedule {
  title: string
  type: string
  cluster: string
  powerKw: number
  savings: string | null
  startUtc: string
  endUtc: string
  notes?: string
}

export interface DatasetFreshness {
  dataset: string
  latestRecordTimestamp: string | null
  status: "HEALTHY" | "STALE" | "MISSING" | "DATABASE_UNAVAILABLE"
  ageMinutes: number | null
  message: string
}

export interface FreshnessNotification {
  id: number
  dataset: string
  message: string
  createdAt: string
  read: boolean
}

export interface DataFreshnessResponse {
  datasets: DatasetFreshness[]
  notifications: FreshnessNotification[]
  checkedAt: string
}

export type AuditActionType = "APPROVE" | "REJECT" | "REVISE" | "OVERRIDE" | "DISPATCH"

export interface AuditEvent {
  id: number
  actor: string
  actionType: AuditActionType
  timestamp: string
  runId: string
  scheduleHash: string
  operatorRationale: string
}

export type OptimizerRunStatus = "SCHEDULED" | "NEEDS_ATTENTION" | "INFEASIBLE" | "TIMEOUT" | "FAILED"

export interface OptimizerRunSnapshot {
  id: string
  createdAt: string
  solverName: string
  solverVersion: string
  solverConfig: string
  status: OptimizerRunStatus
  provenOptimal: boolean | null
}

/** VISIBLE: you own the run and `schedule` is set; OWNER_ONLY: stored, but only its owner sees it; NOT_STORED: none. */
export type ScheduleAvailability = "VISIBLE" | "OWNER_ONLY" | "NOT_STORED"

export interface AuditEventDetail {
  event: AuditEvent
  run: OptimizerRunSnapshot | null
  scheduleAvailability: ScheduleAvailability
  /** Whether the stored schedule still matches the event's scheduleHash; null when NOT_STORED. */
  scheduleHashMatches: boolean | null
  /** The stored optimiser result; only when scheduleAvailability is VISIBLE. */
  schedule: OptimizeResponse | null
}

export interface AuditEventQuery {
  from?: string
  to?: string
  actor?: string
  actionType?: AuditActionType
  runId?: string
  jobId?: number
  page?: number
  size?: number
}

export interface Page<T> {
  content: T[]
  totalElements: number
  totalPages: number
  number: number
  size: number
}

export type ScheduleExportFormat = "csv" | "json"

/** Why the approval gate refused an export: 403 for NOT_APPROVED, 409 for STALE or INVALIDATED. */
export type ScheduleExportRejectionReason = "NOT_APPROVED" | "STALE" | "INVALIDATED"

const EXPORT_REJECTION_REASONS: ScheduleExportRejectionReason[] = ["NOT_APPROVED", "STALE", "INVALIDATED"]

/** A refused schedule export. `reason` is set when the approval gate refused it, rather than auth or lookup. */
export class ScheduleExportError extends Error {
  readonly status: number
  readonly reason?: ScheduleExportRejectionReason

  constructor(message: string, status: number, reason?: ScheduleExportRejectionReason) {
    super(message)
    this.name = "ScheduleExportError"
    this.status = status
    this.reason = reason
  }
}

export interface DownloadedFile {
  blob: Blob
  filename: string
}

const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL || process.env.NEXT_PUBLIC_API_URL || "http://localhost:8080").replace(/\/+$/, "")

// Storage keys
const ACCESS_TOKEN_KEY = "gacs_access_token"
const REFRESH_TOKEN_KEY = "gacs_refresh_token"

// In-memory access token cache
let inMemoryAccessToken: string | null = null

export function setAccessToken(token: string | null): void {
  inMemoryAccessToken = token
  if (typeof window !== "undefined") {
    if (token) {
      localStorage.setItem(ACCESS_TOKEN_KEY, token)
    } else {
      localStorage.removeItem(ACCESS_TOKEN_KEY)
    }
  }
}

export function getAccessToken(): string | null {
  if (inMemoryAccessToken) {
    return inMemoryAccessToken
  }
  if (typeof window !== "undefined") {
    const stored = localStorage.getItem(ACCESS_TOKEN_KEY)
    if (stored) {
      inMemoryAccessToken = stored
      return stored
    }
  }
  return null
}

export function setRefreshToken(token: string | null): void {
  if (typeof window !== "undefined") {
    if (token) {
      localStorage.setItem(REFRESH_TOKEN_KEY, token)
    } else {
      localStorage.removeItem(REFRESH_TOKEN_KEY)
    }
  }
}

export function getRefreshToken(): string | null {
  if (typeof window !== "undefined") {
    return localStorage.getItem(REFRESH_TOKEN_KEY)
  }
  return null
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

  // If access token is missing on a protected route, attempt restoring it
  if (!getAccessToken() && !isPublicAuthEndpoint && typeof window !== "undefined") {
    try {
      const refreshed = await authApi.refreshToken()
      if (refreshed.accessToken) {
        setAccessToken(refreshed.accessToken)
      }
    } catch {
      // Cookie or refresh token might be expired or not present
    }
  }

  // Attach JWT Bearer token if available
  const token = getAccessToken()
  if (token && !headers["Authorization"]) {
    headers["Authorization"] = `Bearer ${token}`
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
  getForecast(params: { location: string; hours?: number }): Promise<ApiResponse<PriceForecast>> {
    const query = new URLSearchParams({
      location: params.location,
      hours: String(params.hours ?? 48),
    })
    return fetchJson<ApiResponse<PriceForecast>>(`/api/prices/forecast?${query.toString()}`, { cache: "no-store" })
  },

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

export const workloadScheduleApi = {
  list(): Promise<ApiResponse<WorkloadSchedule[]>> {
    return fetchJson<ApiResponse<WorkloadSchedule[]>>("/api/workload-schedules", { cache: "no-store" })
  },

  create(payload: CreateWorkloadSchedule): Promise<ApiResponse<WorkloadSchedule>> {
    return fetchJson<ApiResponse<WorkloadSchedule>>("/api/workload-schedules", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },

  updateStatus(id: number, status: WorkloadSchedule["status"]): Promise<ApiResponse<WorkloadSchedule>> {
    return fetchJson<ApiResponse<WorkloadSchedule>>(`/api/workload-schedules/${id}/status`, {
      method: "PATCH",
      body: JSON.stringify({ status }),
    })
  },

  remove(id: number): Promise<ApiResponse<void>> {
    return fetchJson<ApiResponse<void>>(`/api/workload-schedules/${id}`, { method: "DELETE" })
  },
}

export const jobsApi = {
  list(): Promise<ApiResponse<ComputeJob[]>> {
    return fetchJson<ApiResponse<ComputeJob[]>>("/api/jobs", { cache: "no-store" })
  },
  get(id: number): Promise<ApiResponse<ComputeJob>> {
    return fetchJson<ApiResponse<ComputeJob>>(`/api/jobs/${id}`, { cache: "no-store" })
  },
  create(payload: ComputeJobPayload): Promise<ApiResponse<ComputeJob>> {
    return fetchJson<ApiResponse<ComputeJob>>("/api/jobs", { method: "POST", body: JSON.stringify(payload) })
  },
  update(id: number, payload: ComputeJobPayload): Promise<ApiResponse<ComputeJob>> {
    return fetchJson<ApiResponse<ComputeJob>>(`/api/jobs/${id}`, { method: "PUT", body: JSON.stringify(payload) })
  },
  remove(id: number): Promise<ApiResponse<void>> {
    return fetchJson<ApiResponse<void>>(`/api/jobs/${id}`, { method: "DELETE" })
  },
}

export const optimizerApi = {
  optimize(payload: OptimizeRequest): Promise<ApiResponse<OptimizeResponse>> {
    return fetchJson<ApiResponse<OptimizeResponse>>("/api/schedules/optimize", {
      method: "POST",
      body: JSON.stringify(payload),
    })
  },
  explanation(runId: string): Promise<ApiResponse<ExplanationResponse>> {
    return fetchJson<ApiResponse<ExplanationResponse>>(`/api/schedules/${runId}/explanation`, {
      cache: "no-store",
    })
  },
}

export const facilityApi = {
  get(): Promise<ApiResponse<FacilityConfig>> {
    return fetchJson<ApiResponse<FacilityConfig>>("/api/facilities/me", { cache: "no-store" })
  },
  update(payload: FacilityConfig): Promise<ApiResponse<FacilityConfig>> {
    return fetchJson<ApiResponse<FacilityConfig>>("/api/facilities/me", {
      method: "PUT",
      body: JSON.stringify(payload),
    })
  },
}

export const freshnessApi = {
  getStatus(): Promise<ApiResponse<DataFreshnessResponse>> {
    return fetchJson<ApiResponse<DataFreshnessResponse>>("/api/health/data-freshness", {
      method: "GET",
      cache: "no-store",
    })
  },

  markNotificationRead(notificationId: number): Promise<ApiResponse<void>> {
    return fetchJson<ApiResponse<void>>(`/api/health/data-freshness/notifications/${notificationId}/read`, {
      method: "POST",
    })
  },
}

export const auditApi = {
  list(query: AuditEventQuery = {}): Promise<ApiResponse<Page<AuditEvent>>> {
    const params = new URLSearchParams()
    for (const [key, value] of Object.entries(query)) {
      if (value !== undefined && value !== "") params.set(key, String(value))
    }
    const qs = params.toString()
    return fetchJson<ApiResponse<Page<AuditEvent>>>(`/api/audit-events${qs ? `?${qs}` : ""}`, { cache: "no-store" })
  },
  get(id: number): Promise<ApiResponse<AuditEventDetail>> {
    return fetchJson<ApiResponse<AuditEventDetail>>(`/api/audit-events/${id}`, { cache: "no-store" })
  },
}

const EXPORT_ACCEPT: Record<ScheduleExportFormat, string> = {
  // JSON as a fallback so refusals can come back in the standard error envelope.
  csv: "text/csv, application/json;q=0.9",
  json: "application/json",
}

/** The message and gate reason from an error envelope, or null when the body is not one (e.g. empty). */
async function readExportError(
  response: Response,
): Promise<{ message?: string; reason?: ScheduleExportRejectionReason } | null> {
  try {
    const body = await response.clone().json()
    const reason = body?.data?.reason
    return {
      message: typeof body?.message === "string" ? body.message : undefined,
      reason: EXPORT_REJECTION_REASONS.includes(reason) ? reason : undefined,
    }
  } catch {
    return null
  }
}

/** GET a file with the JWT attached, refreshing the access token once if the security layer rejects it. */
async function fetchFileWithAuth(endpoint: string, accept: string): Promise<Response> {
  const url = `${API_BASE_URL}${endpoint}`
  if (!getAccessToken() && typeof window !== "undefined") {
    try {
      await authApi.refreshToken()
    } catch {
      // No valid refresh token; the request below reports the auth failure.
    }
  }
  const send = () => {
    const headers: Record<string, string> = { Accept: accept }
    const token = getAccessToken()
    if (token) headers["Authorization"] = `Bearer ${token}`
    return fetch(url, { headers, credentials: "include", cache: "no-store" })
  }

  const response = await send()
  // A 403 that names a gate reason is the approval gate's answer, which a new token would not change.
  const authRejected =
    response.status === 401 || (response.status === 403 && !(await readExportError(response))?.reason)
  if (!authRejected) return response
  try {
    const refreshed = await authApi.refreshToken()
    if (refreshed.accessToken) return await send()
  } catch {
    // Refresh failed; report the original response.
  }
  return response
}

export const scheduleExportApi = {
  /**
   * Downloads an approved schedule (contract section 6.6). Resolves with the file and the name from
   * Content-Disposition; rejects with a ScheduleExportError carrying the server's explanation.
   */
  async download(runId: string, format: ScheduleExportFormat): Promise<DownloadedFile> {
    const response = await fetchFileWithAuth(
      `/api/schedules/${encodeURIComponent(runId)}/export.${format}`,
      EXPORT_ACCEPT[format],
    )
    if (!response.ok) {
      const error = await readExportError(response)
      const fallback =
        response.status === 401 || response.status === 403
          ? "Your session has expired. Sign in again to download this schedule."
          : `Export failed with status ${response.status}`
      throw new ScheduleExportError(error?.message || fallback, response.status, error?.reason)
    }
    const filename =
      filenameFromContentDisposition(response.headers.get("Content-Disposition")) ?? `schedule-run-${runId}.${format}`
    return { blob: await response.blob(), filename }
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

    if (response.refreshToken) {
      setRefreshToken(response.refreshToken)
    }

    // allows navigation to protected routes immediately after login
    if (response.user) {
      setStoredUser(response.user, response.refreshExpiresIn)
    }

    return response
  },

  async refreshToken(): Promise<AuthResponse> {
    const headers: Record<string, string> = {}
    const rToken = getRefreshToken()
    if (rToken) {
      headers["X-Refresh-Token"] = rToken
    }
    const response = await fetchJson<AuthResponse>("/api/auth/refresh", {
      method: "POST",
      headers,
    })

    if (response.accessToken) {
      setAccessToken(response.accessToken)
    }

    if (response.refreshToken) {
      setRefreshToken(response.refreshToken)
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
    const headers: Record<string, string> = {}
    const rToken = getRefreshToken()
    if (rToken) {
      headers["X-Refresh-Token"] = rToken
    }
    try {
      return await fetchJson<ApiResponse<void>>("/logout", {
        method: "POST",
        headers,
      })
    } finally {
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
  if (typeof window !== "undefined") {
    try {
      localStorage.removeItem(USER_STORAGE_KEY)
      localStorage.removeItem(ACCESS_TOKEN_KEY)
      localStorage.removeItem(REFRESH_TOKEN_KEY)
      document.cookie = `${COOKIE_NAME}=; path=/; max-age=0; SameSite=Lax`
    } catch {
      // Ignore
    }
  }
  setAccessToken(null)
  setRefreshToken(null)
}
