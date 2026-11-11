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

export interface ApiResponse<T = unknown> {
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
  /**
   * How much to trust this interval's prediction, 0-1 (`priceApi.getForecast` converts the backend's
   * 0-100 percentage), derived from the model's stored forecast error. Omitted by the API while unavailable (no RMSE reference yet for the model version),
   * so null means "not known", never "no confidence". Contract FR-10.
   */
  confidenceScore: number | null
}

export type ForecastModel = "lstm" | "lear"

export interface PriceForecast {
  points: PriceForecastPoint[]
  horizonHours: number
  modelKind: ForecastModel
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
  /** Whose job this is. The calendar shows every user's entries. */
  ownerName: string
  /** True for the signed-in user's own entries, the only ones they may change. */
  mine: boolean
  /** The approved plan and the job behind this entry. Null for a row added through POST /api/workload-schedules. */
  runId: string | null
  jobId: number | null
}

export type JobPriority = "CRITICAL" | "HIGH" | "NORMAL" | "LOW"

export type ConfirmationOutcome = "PENDING" | "CONFIRMED" | "REJECTED"

/**
 * What kind of workload a job is. Matches the categories the workload-schedule calendar shows
 * as text in a job's details - WORKLOAD_CATEGORY_LABELS
 * below is the exact mapping the backend's WorkloadCategory enum uses to label a dispatched
 * calendar entry, so a job's chosen category always renders consistently with the calendar.
 */
export type WorkloadCategory = "ML_TRAINING" | "CRYPTO_MINING" | "HPC_BATCH" | "HVAC_PRE_COOL" | "GRID_ALERT"

export const PRIORITY_LABELS: Record<JobPriority, string> = { CRITICAL: "Critical", HIGH: "High", NORMAL: "Normal", LOW: "Low" }

export const WORKLOAD_CATEGORY_LABELS: Record<WorkloadCategory, string> = {
  ML_TRAINING: "ML Training",
  CRYPTO_MINING: "Crypto Mining",
  HPC_BATCH: "HPC Batch",
  HVAC_PRE_COOL: "HVAC Pre-Cool",
  GRID_ALERT: "Grid Alert",
}

/** Worked out by the server from the job's plans and calendar entries. */
export type JobStatus =
  | "SCHEDULING" | "AWAITING_APPROVAL" | "SCHEDULED" | "RUNNING" | "COMPLETED" | "REJECTED" | "NEEDS_ATTENTION" | "CANCELLED"

export interface ComputeJob {
  id: number
  ownerEmail?: string
  /** Set only in the admin listing of every user's jobs. */
  ownerName?: string
  /** Run ID of the approved plan the job is in. Such a job cannot be edited, only deleted. */
  approvedRunId?: string | null
  name: string
  description?: string | null
  cpuHours: number
  gpuHours: number
  originalStartTime: string
  deadline: string
  flexibilityWindowHours: number
  isExempt: boolean
  priority?: JobPriority
  workloadCategory?: WorkloadCategory
  rawConstraints?: string | null
  parsedAt?: string | null
  confirmationOutcome?: ConfirmationOutcome | null
  status: JobStatus
  /** Why the job is Rejected or Needs attention. */
  statusReason?: string | null
  createdAt?: string
  updatedAt?: string
}

export type ComputeJobPayload = Pick<ComputeJob,
  "name" | "cpuHours" | "gpuHours" | "originalStartTime" | "deadline" | "flexibilityWindowHours" | "isExempt"
> & {
  description?: string | null
  rawConstraints?: string | null
  parsedAt?: string | null
  confirmationOutcome?: ConfirmationOutcome | null
  priority?: JobPriority
  workloadCategory?: WorkloadCategory
}

export interface ConstraintDraft {
  originalStartTime: string | null
  deadline: string | null
  flexibilityWindowHours: number | null
  priority: JobPriority | null
  isExempt: boolean | null
}

export interface ConstraintParseRequest {
  rawConstraints: string
  draft: ConstraintDraft
  timeZone: string
}

import type { ConstraintTypeName } from "./constraint-confirmation"

export interface ConstraintParseResponse {
  status: "PARSED" | "NEEDS_CLARIFICATION" | "INVALID"
  fields: ConstraintDraft
  evidence: Array<{ field: keyof ConstraintDraft; value: string; phrase: string; start: number; end: number }>
  ambiguities: Array<{ field: keyof ConstraintDraft | null; reason: string; phrase: string; message: string; start: number; end: number }>
  conflicts: Array<{ field: keyof ConstraintDraft; phrases: string[]; message: string }>
  unsupported: Array<{ phrase: string; start: number; end: number }>
  validationError: string | null
  parsedAt: string
  timeZone: string
  /**
   * Constraints the classifier found that the form has no field for - a recurring daily window, or
   * an ordering against another job. Empty when no classifier is configured, which is the default.
   */
  suggestions: ConstraintSuggestion[]
  /**
   * Where the classifier and the rule-based parser read the text differently. Each is a question,
   * not a resolution: neither source overrides the other, so the operator picks. A non-empty list
   * means `status` is NEEDS_CLARIFICATION.
   */
  disagreements: ConstraintDisagreement[]
}

export interface ConstraintSuggestion {
  type: ConstraintTypeName
  /** Probability the reading is correct, 0-1. Not the model's confidence - a different quantity. */
  probability: number
  phrase: string
  /** The other job, for an ordering or exclusion rule. Always one the operator owns. */
  referencedJobId: number | null
  needsConfirmation: boolean
}

export interface ConstraintDisagreement {
  field: keyof ConstraintDraft
  /** What the rule-based parser read, or null when it read nothing. */
  parsedValue: string | null
  /** Null when the classifier never reported this type - not the same as a score of zero. */
  probability: number | null
  question: string
  options: Array<{ id: string; label: string }>
}

export interface FacilityConfig {
  id?: number
  ownerEmail?: string
  name: string
  maxCpuHours: number
  maxGpuHours: number
  location: string
  updatedAt?: string
}

/** Scheduled use of one resource in the current hour, against the facility limit per hourly slot. */
export interface ResourceUtilization {
  /** Null when no live approved plan exists. */
  used: number | null
  limit: number
  /** Not clamped, so it can exceed 100. Null when `used` is null or the limit is 0. */
  percent: number | null
}

/** Scheduled (not measured) use, summed over all live approved plans. */
export interface FacilityUtilization {
  state: "LIVE" | "NO_APPROVED_PLAN"
  runIds: string[]
  hourStartUtc: string
  cpu: ResourceUtilization
  gpu: ResourceUtilization
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
  /**
   * Opt in to rescheduling jobs whose originalStartTime has already passed, letting them start
   * anywhere from the current hour up to the last slot that still lets them finish by their
   * deadline. Omitted (or false) keeps each job's stated window exactly, which is the default.
   *
   * This widens placement beyond what the operator originally specified, so it is only ever sent
   * after they confirm a dialog naming the affected jobs - never inferred from the data.
   */
  rescheduleElapsedJobs?: boolean
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
  /**
   * True when this job was placed only because the run opted in to rescheduling jobs whose
   * originalStartTime had already passed: its start window was re-anchored from
   * [originalStartTime, originalStartTime + flexibilityWindowHours] to
   * [current hour, last slot that still finishes by its deadline]. Such a job moved because its
   * start had elapsed, not (or not only) to save money, and its savings are measured against a
   * baseline that itself already partly elapsed - so the table labels it rather than presenting it
   * alongside ordinary cost optimisation.
   */
  rescheduledFromElapsedStart: boolean
}

export interface OptimizeResponse {
  runId: string
  status: "SCHEDULED" | "NEEDS_ATTENTION" | "INFEASIBLE"
  provenOptimal: boolean
  baselineCostUsd: number
  optimizedCostUsd: number
  projectedSavingsUsd: number
  projectedSavingsPercent: number
  plainEnglishExplanation: string
  usedSeededFallbackPrices: boolean
  items: ScheduleItem[]
}

export interface JobExplanation {
  jobId: number
  whyThisPlacement: string | null
  constraints: Array<{
    constraintType: string
    code: string
    slackHours: number | null
    relatedJobIds: number[]
    explanation: string
  }>
}

export interface ExplanationResponse {
  runId: string
  explanation: string
  items?: JobExplanation[]
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
  /** Readable name, for example "Daily ERCOT price". `dataset` is the machine name. */
  label: string
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

export type AuditActionType = "APPROVE" | "REJECT" | "REVISE" | "REVOKE" | "OVERRIDE" | "DISPATCH" | "REMOVE_JOB"

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

const API_BASE_URL = (process.env.NEXT_PUBLIC_API_BASE_URL || process.env.NEXT_PUBLIC_API_URL ||
  (process.env.NODE_ENV === "production" ? "" : "http://localhost:8080")).replace(/\/+$/, "")

function apiUrl(endpoint: string): string {
  if (!API_BASE_URL) throw new Error("The application is not connected to its API. Contact your administrator.")
  return `${API_BASE_URL}${endpoint}`
}

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

export class ApiRequestError extends Error {
  constructor(message: string, public readonly status: number, public readonly reason?: string) {
    super(message)
    this.name = "ApiRequestError"
  }
}

/**
 * The backend answers every error in the `ApiResponse` envelope, but a failing request does not
 * always reach the backend: a proxy, a gateway or a server older than this browser can answer with
 * HTML, with an empty body, or with JSON of a completely different shape. Reading `message` straight
 * off that body yields `undefined`, an object, or a number, and the UI then renders nothing, renders
 * "[object Object]", or (where a message is used as a React child) throws.
 *
 * <p>So the message is taken only when it really is a non-blank string, and otherwise synthesised
 * from the status - which is always known. 404 and 405 get their own wording because they are what a
 * client calling an endpoint its server does not have actually receives: that is the shape of the
 * reported "Revoke approval does nothing", and "Request failed with status 404" told the operator
 * nothing about why or what to do.
 */
export function apiErrorMessage(status: number, body: unknown): string {
  const message = (body as { message?: unknown } | null)?.message
  if (typeof message === "string" && message.trim()) return message
  if (status === 404 || status === 405) {
    return `This action is not available on the server (HTTP ${status}). The server may be running an `
      + "older version than this page; reload, and ask your administrator to redeploy the backend if it persists."
  }
  if (status === 0) return "The server could not be reached. Check your connection and try again."
  if (status >= 500) return `The server failed to handle this request (HTTP ${status}). Try again, or contact support.`
  return `Request failed with status ${status}`
}

/** `data.reason` from the envelope, but only when it is a string - the gate's reason code. */
function apiErrorReason(body: unknown): string | undefined {
  const reason = (body as { data?: { reason?: unknown } } | null)?.data?.reason
  return typeof reason === "string" ? reason : undefined
}

async function fetchJson<T>(endpoint: string, options: RequestInit = {}): Promise<T> {
  const url = apiUrl(endpoint)
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
    endpoint.includes("/resend-otp") ||
    endpoint.includes("/forgot-password") ||
    endpoint.includes("/reset-password")

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

  // A transport failure (server down, DNS, CORS preflight refused, connection dropped) rejects
  // here with a bare TypeError whose message is the browser's own wording - "Failed to fetch" or
  // "NetworkError", neither of which means anything to an operator. Normalising it to an
  // ApiRequestError with status 0 means every caller has one error type to handle and one readable
  // message, instead of a raw TypeError reaching the UI as the explanation for a failed decision.
  const send = async (): Promise<Response> => {
    try {
      return await fetch(url, {
        ...options,
        headers,
        credentials: "include", // for HttpOnly refresh-token cookies
      })
    } catch {
      throw new ApiRequestError(apiErrorMessage(0, null), 0)
    }
  }
  const response = await send()

  // Automatic token refresh on 401 Unauthorized or 403 Forbidden for authenticated endpoints
  if (
    (response.status === 401 || response.status === 403) &&
    !isPublicAuthEndpoint
  ) {
    let refreshedAccessToken: string | undefined
    try {
      refreshedAccessToken = (await authApi.refreshToken()).accessToken
    } catch {
      // Keep the original refusal if refreshing authentication fails.
    }
    if (refreshedAccessToken) {
      setAccessToken(refreshedAccessToken)
      headers["Authorization"] = `Bearer ${refreshedAccessToken}`
      const retryResponse = await send()
      const retryData = await retryResponse.json().catch(() => ({}))
      if (!retryResponse.ok) {
        throw new ApiRequestError(apiErrorMessage(retryResponse.status, retryData),
          retryResponse.status, apiErrorReason(retryData))
      }
      return retryData as T
    }
  }

  const data = await response.json().catch(() => ({}))

  if (!response.ok) {
    throw new ApiRequestError(apiErrorMessage(response.status, data), response.status, apiErrorReason(data))
  }

  return data as T
}

export const priceApi = {
  async getForecast(params: { location: string; hours?: number; model?: ForecastModel }): Promise<ApiResponse<PriceForecast>> {
    const query = new URLSearchParams({
      location: params.location,
      hours: String(params.hours ?? 48),
      model: params.model ?? "lstm",
    })
    const response = await fetchJson<ApiResponse<PriceForecast>>(`/api/prices/forecast?${query.toString()}`, { cache: "no-store" })
    // The backend forwards predicted_price.confidence_score as stored: a percentage from 0 to 100.
    // The rest of the UI uses 0-1 (lib/forecast-confidence.ts), so convert once, here.
    if (response.data?.points) {
      response.data = {
        ...response.data,
        points: response.data.points.map((point) => ({
          ...point,
          confidenceScore: point.confidenceScore == null ? null : point.confidenceScore / 100,
        })),
      }
    }
    return response
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
}

export const jobsApi = {
  parseConstraints(payload: ConstraintParseRequest): Promise<ApiResponse<ConstraintParseResponse>> {
    return fetchJson<ApiResponse<ConstraintParseResponse>>("/api/jobs/parse-constraints", {
      method: "POST", body: JSON.stringify(payload),
    })
  },
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
  /** Plans the caller's jobs that have no live plan, and the ones turned away or failed. */
  recompute(): Promise<ApiResponse<{ runIds: string[]; failed: number }>> {
    return fetchJson<ApiResponse<{ runIds: string[]; failed: number }>>("/api/schedules/recompute", { method: "POST" })
  },
  /** Admin only: every user's jobs, each with its owner's name. */
  listAll(): Promise<ApiResponse<ComputeJob[]>> {
    return fetchJson<ApiResponse<ComputeJob[]>>("/api/admin/jobs", { cache: "no-store" })
  },
}

export type PlanReviewState = "PENDING" | "APPROVED" | "REJECTED" | "REVISION_REQUESTED" | "STALE" | "INVALIDATED"
/**
 * "revoke" withdraws an approval already given, halting a schedule that may already have been
 * dispatched. It is deliberately not "reject": rejecting is a first decision on a pending
 * recommendation, revoking undoes one already made, and only an APPROVED plan accepts it.
 */
export type ScheduleDecisionAction = "approve" | "reject" | "revise" | "revoke"
export interface SchedulePlanSummary {
  runId: string
  createdAt: string
  reviewState: PlanReviewState
  /** Whose jobs the plan schedules. */
  ownerName: string
  /** Whether the signed-in user may approve it. A submitter may not, unless an admin or the setting allows it. */
  canApprove: boolean
  /** The signed-in user submitted it. */
  mine: boolean
  /** The plan's jobs, without any removed after approval. Empty if the stored result cannot be read. */
  jobNames: string[]
  projectedSavingsUsd: number | null
  projectedSavingsPercent: number | null
  /** Earliest scheduled start (UTC), or null if nothing is placed. */
  firstStart: string | null
  usedSeededFallbackPrices: boolean
}
export interface SchedulePlanDetail {
  runId: string
  reviewState: PlanReviewState
  invalidationReason: string | null
  invalidatedAt: string | null
  /**
   * When this plan was written to the workload calendar, or null if it never has been. Server-held,
   * so it survives navigating away and back - which is the point: the review panel used to track
   * "already added" in component state, lose it on remount, and offer "Add approved schedule to
   * calendar" again for a schedule that was applied an hour ago.
   */
  appliedAt: string | null
  recommendation: OptimizeResponse | null
  ownerName: string
  canApprove: boolean
  /** Whether the signed-in user may revoke its approval: the plan's owner or an admin. */
  canRevoke: boolean
  /** Jobs that left the plan after approval (deleted, or never placed). */
  removedJobIds: number[]
}
/**
 * The action as the backend records it: {@link ScheduleDecisionAction} upper-cased, matching the
 * `AuditActionType` values a decision can carry. REVOKE was missing here while `/revoke` already
 * returned `action: "REVOKE"`, so the declared type did not describe the response the UI actually
 * received for a revocation - a narrowing on `action` would have excluded the one case it exists for.
 */
export type RecordedDecisionAction = "APPROVE" | "REJECT" | "REVISE" | "REVOKE"

export interface ScheduleDecision {
  decisionId: number
  runId: string
  action: RecordedDecisionAction
  operatorId: number
  comment: string
  decidedAt: string
  reviewState: PlanReviewState
}

export const scheduleReviewApi = {
  apply(runId: string): Promise<ApiResponse<WorkloadSchedule[]>> {
    return fetchJson(`/api/schedules/${encodeURIComponent(runId)}/apply`, { method: "POST" })
  },
  /** Every owner's plans. `approvable` keeps only the ones the signed-in user may approve. */
  list(page = 0, state?: PlanReviewState, approvable = false): Promise<ApiResponse<Page<SchedulePlanSummary>>> {
    const filter = (state ? `&state=${state}` : "") + (approvable ? "&approvable=true" : "")
    return fetchJson(`/api/schedules?page=${page}&size=20${filter}`, { cache: "no-store" })
  },
  detail(runId: string): Promise<ApiResponse<SchedulePlanDetail>> {
    return fetchJson(`/api/schedules/${encodeURIComponent(runId)}`, { cache: "no-store" })
  },
  decide(runId: string, action: ScheduleDecisionAction, comment: string): Promise<ApiResponse<ScheduleDecision>> {
    return fetchJson(`/api/schedules/${encodeURIComponent(runId)}/${action}`, {
      method: "POST", body: JSON.stringify({ comment }),
    })
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
  /** Runs the server's plan validity check, so it can change stored plan states. */
  utilization(): Promise<ApiResponse<FacilityUtilization>> {
    return fetchJson<ApiResponse<FacilityUtilization>>("/api/facilities/me/utilization", { cache: "no-store" })
  },
  /** Admin only. A change to capacity or location ends the approval of every approved plan. */
  update(payload: FacilityConfig): Promise<ApiResponse<FacilityConfig>> {
    return fetchJson<ApiResponse<FacilityConfig>>("/api/admin/facility", {
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
  const url = apiUrl(endpoint)
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
  async forgotPassword(payload: { email: string }): Promise<ApiResponse<string>> {
    return fetchJson("/forgot-password", { method: "POST", body: JSON.stringify(payload) })
  },
  async resetPassword(payload: { email: string; token: string; password: string }): Promise<ApiResponse<string>> {
    return fetchJson("/reset-password", { method: "POST", body: JSON.stringify(payload) })
  },
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

export interface AppSettings { allowSelfApproval: boolean }

export const settingsApi = {
  /** Admin only. */
  get(): Promise<ApiResponse<AppSettings>> {
    return fetchJson<ApiResponse<AppSettings>>("/api/admin/settings", { cache: "no-store" })
  },
  update(payload: AppSettings): Promise<ApiResponse<AppSettings>> {
    return fetchJson<ApiResponse<AppSettings>>("/api/admin/settings", { method: "PUT", body: JSON.stringify(payload) })
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
    const user = { ...data }
    delete user._maxAgeSeconds
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
