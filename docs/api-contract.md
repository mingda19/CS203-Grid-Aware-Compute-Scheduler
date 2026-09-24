# API Contract & Integration Specification
**Grid Aware Compute Scheduler (GACS)**  
**Document Status:** Approved Baseline  
**Classification:** Engineering Specification & Architecture Standard  
**Sprint Deliverables:** `[B-6]` Swagger/OpenAPI docs for all endpoints & `[F-2]` Draft & commit API contract (`docs/api-contract.md`)  
**Version:** 1.0.0  

---

## 1. Executive Summary & Purpose

The **Grid Aware Compute Scheduler (GACS)** platform provides decision-support and workload scheduling for operators of flexible, deadline-tolerant compute (e.g., AI/ML model training, batch processing, crypto-mining, and HVAC cooling). GACS ingests wholesale electricity prices, forecasts price curves over a 24–72-hour horizon, models machine and power constraints, generates cost-optimized execution schedules against a fixed-schedule baseline, and presents plain-English rationales for human approval.

This **API Contract** establishes the binding interface agreement between:
1. **Next.js Frontend Application**: Client-side UI, server components, and edge middleware.
2. **Spring Boot Backend**: Core REST API services handling authentication, authorization, role management, audit logging, and CRUD operations.
3. **Forecasting & Optimization Microservices**: Analytical pipelines providing price predictions and workload solver plans.
4. **Third-Party Execution Adapters**: Automated cluster schedulers and facility export tools.

---

## 2. Global Architectural Conventions

### 2.1 Base URLs & Environments

| Environment | Base URL | Description |
|---|---|---|
| **Local Development** | `http://localhost:8080` | Local Spring Boot backend server |
| **Interactive Swagger UI** | `http://localhost:8080/swagger-ui.html` | Swagger UI documentation & test console |
| **OpenAPI 3.1 JSON Spec** | `http://localhost:8080/v3/api-docs` | Machine-readable OpenAPI specification |
| **Staging Environment** | `https://staging-api.gacs.datacenter.io` | Pre-production testing environment |
| **Production Environment** | `https://api.gacs.datacenter.io` | Production cluster endpoint |

Frontend consumers configure the active base URL using the environment variable:
```env
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
```

### 2.2 Standard Request Headers

All client requests must adhere to standard HTTP/1.1 or HTTP/2 transport specifications:

```http
Content-Type: application/json
Accept: application/json
Authorization: Bearer <jwt_access_token>
```

* For endpoints requiring cookie-based session management (`/api/auth/refresh`, `/api/auth/logout`), the browser client must include credentials:
  ```typescript
  fetch(url, { credentials: "include" })
  ```

### 2.3 Cross-Origin Resource Sharing (CORS)

The backend enforces strict CORS policies. Allowed origins are configured via `CORS_ALLOWED_ORIGINS` (defaulting to `http://localhost:3000,http://127.0.0.1:3000`).
* Allowed Methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`
* Allowed Headers: `Authorization, Content-Type, Accept, Origin, X-Requested-With`
* Credentials Allowed: `true` (enables browser storage and transmission of the `HttpOnly` refresh token cookie)
* Max Age: `3600` seconds (1 hour preflight cache)

### 2.4 Date and Time Standards

* **Standard Format**: All timestamps transmitted across the API boundary are formatted as ISO 8601 UTC strings:
  `YYYY-MM-DDTHH:mm:ssZ` or `YYYY-MM-DDTHH:mm:ss.sssZ`
* **Internal Storage**: All date-time values are stored in UTC epoch or UTC timestamp columns. Local market times (e.g., `US/Central` for ERCOT) are computed on the client for presentation or explicitly labeled.

### 2.5 Standard Response Envelopes

Every REST response conforms to a consistent JSON structure to simplify client-side consumption, deserialization, and error handling.

#### A. Generic API Response Envelope (`ApiResponse<T>`)
Used for operational, profile, and data endpoints:
```json
{
  "success": true,
  "message": "Operation completed successfully",
  "data": { ... }
}
```

#### B. Authentication Response Envelope (`AuthResponse`)
Used for login, registration, verification, and token refresh:
```json
{
  "success": true,
  "message": "Login successful.",
  "user": {
    "id": 1,
    "email": "operator@datacenter.io",
    "fullName": "Alex Morgan",
    "role": "ROLE_USER",
    "isVerified": true
  },
  "requiresOtp": false,
  "email": "operator@datacenter.io",
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "refreshExpiresIn": 86400
}
```

#### C. Validation & Error Envelope
Returned when request parameters fail validation (HTTP 400 Bad Request):
```json
{
  "success": false,
  "message": "Validation failed",
  "data": {
    "email": "Invalid email format",
    "password": "Password must be at least 6 characters"
  }
}
```

### 2.6 HTTP Status Code Matrix

| Status Code | Meaning | Usage Scenario |
|---|---|---|
| **200 OK** | Success | Request succeeded. Response body contains requested resource or status. |
| **201 Created** | Created | Resource successfully created (e.g., job registration). |
| **400 Bad Request** | Client Error | Malformed JSON, constraint violation, failed validation, or invalid state. |
| **401 Unauthorized** | Unauthenticated | Missing, expired, or malformed JWT Bearer token; or invalid login credentials. |
| **403 Forbidden** | Access Denied | Authenticated user lacks sufficient role privileges (e.g., non-admin accessing `/api/admin/*`). |
| **404 Not Found** | Resource Missing | Requested user, workload, forecast run, or schedule ID does not exist. |
| **409 Conflict** | State Conflict | Duplicate entity creation (e.g., registering an email that already exists). |
| **422 Unprocessable Entity** | Semantic Error | Syntactically valid request that violates domain rules (e.g., impossible job deadline). |
| **500 Internal Error** | Server Error | Unhandled server exception or downstream dependency failure. |

---

## 3. Security, Authentication & Session Architecture

As specified in the **Token Expiry and Secure Storage Policy** (`docs/token-expiry-and-secure-storage-policy.md`):

```
+-----------------------------------------------------------------------------------------+
|                                    Client Browser                                       |
|                                                                                         |
|  [ In-Memory React State ]                 [ HttpOnly Cookie: refreshToken ]            |
|   - Short-lived Access Token (15m)          - Cryptographically secure UUIDv4           |
|   - Sent in Authorization: Bearer           - 24h standard / 14d remember-me            |
|   - Protected against XSS exfiltration     - Protected against JS access & XSS         |
+---------------------------+-------------------------------------+-----------------------+
                            |                                     |
               Authorization: Bearer <JWT>           Cookie: refreshToken=<UUID>
                            |                                     |
+---------------------------v-------------------------------------v-----------------------+
|                                  Spring Boot Backend                                    |
|                                                                                         |
|  [ JwtAuthenticationFilter ]              [ RefreshTokenService ]                       |
|   - Stateless signature verification       - Single-use Refresh Token Rotation (RTR)    |
|   - Sub-millisecond latency                - Automatic theft detection & family revoke   |
+-----------------------------------------------------------------------------------------+
```

### 3.1 Token Lifecycles

| Token Category | Transmission Method | Validity Period | Storage Location |
|---|---|---|---|
| **JWT Access Token** | `Authorization: Bearer <token>` | **15 minutes** (900 seconds) | Client RAM (React module state) |
| **Standard Refresh Token** | Cookie: `refreshToken=<UUID>` | **24 hours** (86,400 seconds) | Browser `HttpOnly` cookie |
| **Remember-Me Refresh Token** | Cookie: `refreshToken=<UUID>` | **14 days** (1,209,600 seconds) | Browser `HttpOnly` cookie |
| **Email Verification OTP** | Transmitted via email | **10 minutes** (600 seconds) | Server Database (`otp_verifications`) |

### 3.2 Refresh Token Rotation (RTR) & Theft Detection

Every token refresh (`POST /api/auth/refresh`) invalidates the submitted token and issues a brand-new refresh token in the response cookie. If an already-used or revoked token is received, the backend flags a **theft incident**, revokes the user's entire active session family, logs a security warning, and rejects the request with HTTP 401.

### 3.3 Role-Based Access Control (RBAC)

User privileges are governed by roles embedded in the JWT claims (`role`):
* `ROLE_USER`: Standard authenticated user (view forecasts, submit workloads, review recommendations).
* `ROLE_ADMIN`: Administrative user (manage user accounts, update role assignments, system maintenance).
* `ROLE_APPROVER` *(Phase 1 roadmap)*: Authorized personnel permitted to approve or reject compute schedules.

---

## 4. Current Endpoints: Authentication & Session Management

All endpoints under this section are fully implemented in `com.gacs.backend.controller.AuthController` and documented via OpenAPI annotations. Dual-route mapping is supported (e.g. `/register` and `/api/auth/register`) for backwards-compatibility.

### 4.1 Register New Account

Creates a user account, hashes credentials using BCrypt, generates a 6-digit email OTP, and sends verification instructions.

* **Method**: `POST`
* **Path**: `/api/auth/register` (alias: `/register`)
* **Access**: Public
* **Request Headers**:
  ```http
  Content-Type: application/json
  ```
* **Request Body**:
  | Field | Type | Required | Constraints | Description |
  |---|---|---|---|---|
  | `email` | string | **Yes** | Valid email format | User's unique login email |
  | `password` | string | **Yes** | Min 6 characters | Plaintext password (hashed server-side) |
  | `fullName` | string | No | Max 100 characters | Display name of the user |

* **Request Example**:
  ```json
  {
    "email": "engineer@datacenter.io",
    "password": "SecurePassword123!",
    "fullName": "Elena Vance"
  }
  ```

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "User registered successfully. An OTP has been sent to your email.",
    "user": null,
    "requiresOtp": true,
    "email": "engineer@datacenter.io",
    "accessToken": null,
    "tokenType": "Bearer",
    "expiresIn": null,
    "refreshExpiresIn": null
  }
  ```

* **Error Responses**:
  * `400 Bad Request`: Email already registered, missing fields, or password too short:
    ```json
    {
      "success": false,
      "message": "Email already in use",
      "data": null
    }
    ```

---

### 4.2 Verify OTP Code

Validates the 6-digit numeric verification code dispatched to the user's email during registration.

* **Method**: `POST`
* **Path**: `/api/auth/verify-otp` (alias: `/verify-otp`)
* **Access**: Public
* **Request Body**:
  | Field | Type | Required | Constraints | Description |
  |---|---|---|---|---|
  | `email` | string | **Yes** | Valid email | Email address being verified |
  | `otp` | string | **Yes** | Exactly 6 digits (`^\d{6}$`) | Code delivered via email |

* **Request Example**:
  ```json
  {
    "email": "engineer@datacenter.io",
    "otp": "492018"
  }
  ```

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "OTP verified successfully. You can now log in.",
    "user": null,
    "requiresOtp": false,
    "email": "engineer@datacenter.io",
    "accessToken": null,
    "tokenType": "Bearer",
    "expiresIn": null,
    "refreshExpiresIn": null
  }
  ```

* **Error Responses**:
  * `400 Bad Request`: Invalid OTP, expired OTP (> 10 mins), or user not found.

---

### 4.3 Resend Verification OTP

Generates and delivers a fresh 6-digit OTP code to the requested unverified account.

* **Method**: `POST`
* **Path**: `/api/auth/resend-otp` (alias: `/resend-otp`)
* **Access**: Public
* **Request Body**:
  | Field | Type | Required | Description |
  |---|---|---|---|
  | `email` | string | **Yes** | Target email address |

* **Request Example**:
  ```json
  {
    "email": "engineer@datacenter.io"
  }
  ```

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "A new OTP has been sent to your email.",
    "data": "A new OTP has been sent to your email."
  }
  ```

* **Error Responses**:
  * `400 Bad Request`: User already verified or user does not exist.

---

### 4.4 User Login

Authenticates user credentials. If verified, issues a 15-minute JWT access token in the response payload and sets a persistent, HttpOnly refresh token cookie.

* **Method**: `POST`
* **Path**: `/api/auth/login` (alias: `/login`)
* **Access**: Public
* **Request Body**:
  | Field | Type | Required | Default | Description |
  |---|---|---|---|---|
  | `email` | string | **Yes** | - | Registered email |
  | `password` | string | **Yes** | - | Account password |
  | `rememberMe` | boolean | No | `false` | If true, extends refresh cookie TTL from 24h to 14 days |

* **Request Example**:
  ```json
  {
    "email": "engineer@datacenter.io",
    "password": "SecurePassword123!",
    "rememberMe": true
  }
  ```

* **Success Response (200 OK)**:
  * **Headers**:
    ```http
    Set-Cookie: refreshToken=c3b879a9-1951-4e12-b9cf-14cbe87198bb; Path=/; Max-Age=1209600; Expires=Thu, 08 Oct 2026 02:45:00 GMT; HttpOnly; SameSite=Lax
    ```
  * **Body**:
    ```json
    {
      "success": true,
      "message": "Login successful.",
      "user": {
        "id": 2,
        "email": "engineer@datacenter.io",
        "fullName": "Elena Vance",
        "role": "ROLE_USER",
        "isVerified": true
      },
      "requiresOtp": false,
      "email": "engineer@datacenter.io",
      "accessToken": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJlbGVuYUBkYXRhY2VudGVyLmlvIiwidXNlcklkIjoyLCJyb2xlIjoiUk9MRV9VU0VSIiwiZXhwIjoxNzg5MDAwOTAwfQ...",
      "tokenType": "Bearer",
      "expiresIn": 900,
      "refreshExpiresIn": 1209600
    }
    ```

* **Unverified Account Flow (200 OK)**:
  If the credentials match but the user has not completed OTP verification, a new OTP is emailed automatically:
  ```json
  {
    "success": true,
    "message": "Account unverified. A new OTP has been sent to your email.",
    "user": null,
    "requiresOtp": true,
    "email": "engineer@datacenter.io",
    "accessToken": null,
    "tokenType": "Bearer",
    "expiresIn": null,
    "refreshExpiresIn": null
  }
  ```

* **Error Responses**:
  * `401 Unauthorized`: Invalid email or incorrect password.
    ```json
    {
      "success": false,
      "message": "Invalid email or password",
      "data": null
    }
    ```

---

### 4.5 Refresh Access Token

Exchanges the persistent refresh cookie for a brand new 15-minute JWT access token and rotates the refresh cookie.

* **Method**: `POST`
* **Path**: `/api/auth/refresh` (alias: `/refresh`)
* **Access**: Public (Authenticated via `refreshToken` cookie)
* **Request Headers**:
  ```http
  Cookie: refreshToken=<UUIDv4>
  ```
* **Request Body**: None

* **Success Response (200 OK)**:
  * **Headers**:
    ```http
    Set-Cookie: refreshToken=f9781810-bb2a-45c1-9dc5-d14d87be33c4; Path=/; Max-Age=1209600; HttpOnly; SameSite=Lax
    ```
  * **Body**:
    ```json
    {
      "success": true,
      "message": "Token refreshed successfully.",
      "user": {
        "id": 2,
        "email": "engineer@datacenter.io",
        "fullName": "Elena Vance",
        "role": "ROLE_USER",
        "isVerified": true
      },
      "requiresOtp": false,
      "email": "engineer@datacenter.io",
      "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
      "tokenType": "Bearer",
      "expiresIn": 900,
      "refreshExpiresIn": 1209600
    }
    ```

* **Error Responses**:
  * `401 Unauthorized`: Cookie missing, token expired, or token revoked/replayed.

---

### 4.6 Get Current User Profile

Retrieves the active profile associated with the provided JWT Bearer token.

* **Method**: `GET`
* **Path**: `/api/auth/me` (alias: `/me`)
* **Access**: Authenticated (`ROLE_USER` or `ROLE_ADMIN`)
* **Request Headers**:
  ```http
  Authorization: Bearer <accessToken>
  ```
* **Request Body**: None

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "User session is active.",
    "data": {
      "id": 2,
      "email": "engineer@datacenter.io",
      "fullName": "Elena Vance",
      "role": "ROLE_USER",
      "isVerified": true
    }
  }
  ```

* **Error Responses**:
  * `401 Unauthorized`: Missing, expired, or signature-mismatched JWT token.

---

### 4.7 User Logout

Invalidates the persistent refresh token in the database, clears the client's `refreshToken` cookie, and clears the server security context.

* **Method**: `POST`
* **Path**: `/api/auth/logout` (alias: `/logout`)
* **Access**: Public / Authenticated
* **Request Headers**:
  ```http
  Cookie: refreshToken=<UUIDv4>
  ```
* **Success Response (200 OK)**:
  * **Headers**:
    ```http
    Set-Cookie: refreshToken=; Path=/; Max-Age=0; Expires=Thu, 01 Jan 1970 00:00:00 GMT; HttpOnly; SameSite=Lax
    ```
  * **Body**:
    ```json
    {
      "success": true,
      "message": "Logged out successfully.",
      "data": null
    }
    ```

---

## 5. Current Endpoints: Administration & Role Management

All endpoints in this section reside in `com.gacs.backend.controller.AdminController` and require `ROLE_ADMIN` authority.

### 5.1 List All Users

Returns a complete list of registered accounts in the system.

* **Method**: `GET`
* **Path**: `/api/admin/users`
* **Access**: Restricted to `ROLE_ADMIN`
* **Request Headers**:
  ```http
  Authorization: Bearer <admin_access_token>
  ```
* **Request Body**: None

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Users retrieved successfully",
    "data": [
      {
        "id": 1,
        "email": "admin@datacenter.io",
        "fullName": "System Administrator",
        "role": "ROLE_ADMIN",
        "isVerified": true
      },
      {
        "id": 2,
        "email": "engineer@datacenter.io",
        "fullName": "Elena Vance",
        "role": "ROLE_USER",
        "isVerified": true
      }
    ]
  }
  ```

* **Error Responses**:
  * `401 Unauthorized`: Token absent or expired.
  * `403 Forbidden`: Authenticated user role is not `ROLE_ADMIN`.

---

### 5.2 Update User Role

Updates the authorization authority of a specific user (`ROLE_USER` or `ROLE_ADMIN`). Enforces a safeguard preventing administrators from demoting their own account.

* **Method**: `PATCH`
* **Path**: `/api/admin/users/{id}/role`
* **Access**: Restricted to `ROLE_ADMIN`
* **Path Parameters**:
  | Parameter | Type | Required | Description |
  |---|---|---|---|
  | `id` | integer (Long) | **Yes** | Primary key identifier of the target user |
* **Request Body**:
  | Field | Type | Required | Allowable Values | Description |
  |---|---|---|---|---|
  | `role` | string | **Yes** | `ROLE_USER`, `ROLE_ADMIN` | New authority role |

* **Request Example**:
  ```json
  {
    "role": "ROLE_ADMIN"
  }
  ```

* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "User role updated successfully",
    "data": {
      "id": 2,
      "email": "engineer@datacenter.io",
      "fullName": "Elena Vance",
      "role": "ROLE_ADMIN",
      "isVerified": true
    }
  }
  ```

* **Error Responses**:
  * `400 Bad Request`: Invalid role value, user ID not found, or attempted self-demotion (`"You cannot demote your own administrator account."`).
  * `401 Unauthorized`: Authentication missing.
  * `403 Forbidden`: Non-admin user.

---

## 6. Grid-Aware Compute Scheduler Domain Endpoints (Phase 1 Roadmap)

This section details the contractual schemas and interfaces for upcoming sprint deliverables as established in the **Product Requirements Document (PRD)**.

```
                    +-----------------------------+
                    |    Grid Market Ingestion    |
                    |    (ERCOT DAM & RTM Feeds)  |
                    +--------------+--------------+
                                   |
                                   v
                    +-----------------------------+
                    |  Price Forecasting Pipeline |
                    |      (LSTM / XGBoost)       |
                    +--------------+--------------+
                                   |
         +-------------------------+-------------------------+
         |                                                   |
         v                                                   v
+-----------------------------+             +-------------------------------+
|     Workload Registry       |             |   Resource Pool & Capacities  |
|  (Jobs, Deadlines, Power)   |             |   (GPUs, CPUs, Power Caps)    |
+--------------+--------------+             +---------------+---------------+
               |                                            |
               +---------------------+----------------------+
                                     |
                                     v
                    +--------------------------------+
                    |   Optimization Solver Engine   |
                    |  (Cost vs Deadline Objective)  |
                    +----------------+---------------+
                                     |
                                     v
                    +--------------------------------+
                    |  Recommendation & Explanation  |
                    |   (Plain English + Baseline)   |
                    +----------------+---------------+
                                     |
                                     v
                    +--------------------------------+
                    |     Human Approval Gate        |
                    |   (Mandatory Operator Gate)    |
                    +----------------+---------------+
                                     |
                                     v
                    +--------------------------------+
                    | Execution Adapter / CSV Export |
                    +--------------------------------+
```

### 6.1 Market Forecasts & Grid Signals

#### `GET /api/forecasts/ercot`
Retrieves forecasted electricity prices and uncertainty intervals for the selected horizon.
* **Query Parameters**:
  * `horizon` (string, optional, default: `"72h"`): `"24h" | "48h" | "72h"`
  * `region` (string, optional, default: `"ERCOT_NORTH"`): Market settlement zone
* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Forecast generated successfully",
    "data": {
      "region": "ERCOT_NORTH",
      "horizon": "72h",
      "model": "LSTM-XGB-Ensemble-v1.4",
      "generatedAt": "2026-09-24T10:00:00Z",
      "dataFreshness": "FRESH",
      "confidenceScore": 0.92,
      "priceSeries": [
        {
          "timestamp": "2026-09-24T11:00:00Z",
          "forecastPriceMwh": 32.50,
          "confidenceIntervalLow": 28.10,
          "confidenceIntervalHigh": 36.90,
          "actualPriceMwh": null,
          "quality": "VALIDATED"
        }
      ]
    }
  }
  ```

---

### 6.2 Workload Management

#### `POST /api/workloads`
Registers a flexible, deadline-tolerant compute job.
* **Request Body**:
  ```json
  {
    "name": "LLM Fine-Tuning Run #42",
    "workloadType": "ML_TRAINING",
    "requiredGpus": 32,
    "requiredCpus": 128,
    "powerDrawKw": 45.0,
    "runtimeMinutes": 180,
    "minimumRuntimeBlockMinutes": 60,
    "deadline": "2026-09-25T18:00:00Z",
    "earliestStart": "2026-09-24T12:00:00Z",
    "preemptible": true,
    "priority": "MEDIUM"
  }
  ```
* **Success Response (201 Created)**: Returns the persisted workload entity with unique `id` and status `PENDING`.

#### `GET /api/workloads`
Lists registered workloads. Supports filtering by `status` (`PENDING`, `SCHEDULED`, `RUNNING`, `COMPLETED`, `CANCELLED`).

---

### 6.3 Schedule Optimization & Approval Workflow

#### `POST /api/schedules/optimize`
Triggers an optimization run evaluating all eligible workloads against grid price forecasts and resource constraints.
* **Success Response (200 OK)**: Returns the computed schedule plan:
  ```json
  {
    "success": true,
    "message": "Optimized schedule generated",
    "data": {
      "scheduleId": "sched-20260924-001",
      "status": "PROPOSED",
      "objectiveValue": 3140.25,
      "baselineCostUsd": 4120.00,
      "optimizedCostUsd": 3140.25,
      "projectedSavingsUsd": 979.75,
      "projectedSavingsPercent": 23.78,
      "plainEnglishExplanation": "By shifting GPU cluster training from the 14:00 peak ($112/MWh) to 01:00 off-peak ($24/MWh) wind-rich hours, projected energy costs are reduced by 23.8% while completing all jobs 4 hours prior to deadline.",
      "items": [
        {
          "workloadId": 101,
          "workloadName": "LLM Fine-Tuning Run #42",
          "baselineStart": "2026-09-24T13:00:00Z",
          "scheduledStart": "2026-09-25T01:00:00Z",
          "scheduledEnd": "2026-09-25T04:00:00Z",
          "allocatedGpus": 32,
          "allocatedPowerKw": 45.0
        }
      ]
    }
  }
  ```

#### `POST /api/schedules/{id}/approve`
**Mandatory Human Gate**: Explicit approval by an authorized operator prior to schedule dispatch.
* **Request Body**:
  ```json
  {
    "comment": "Approved schedule after verifying cooling margin."
  }
  ```
* **Success Response (200 OK)**: Updates status to `APPROVED`, records immutable audit log, and delivers plan to execution adapter.

#### `POST /api/schedules/{id}/reject`
Rejects the proposed plan. Status transitions to `REJECTED`, leaving the facility on the fixed-schedule baseline.

---

## 7. Frontend Integration Guide (`frontend/src/lib/api.ts`)

The Next.js frontend interacts with the backend using the helper module `frontend/src/lib/api.ts`. Key integration mechanisms include:

1. **In-Memory JWT Access Token**:
   ```typescript
   let inMemoryAccessToken: string | null = null;
   export function setAccessToken(token: string | null) { inMemoryAccessToken = token; }
   export function getAccessToken(): string | null { return inMemoryAccessToken; }
   ```
2. **Transparent Silent Refresh (401 Interceptor)**:
   When an authenticated request returns 401 Unauthorized, `fetchJson` intercepts the response, invokes `POST /api/auth/refresh` using the `HttpOnly` cookie, updates `inMemoryAccessToken`, and retries the original request seamlessly.
3. **Route Protection & Next.js Middleware**:
   Protected routes (`/admin`, `/dashboard`) check the lightweight session synchronization cookie (`gacs_logged_in=true`) in Next.js `middleware.ts`. If unauthenticated, the user is redirected to `/login`.

---

## 8. Versioning, Deprecation & Contract Evolution

1. **Semantic Versioning**: All API contracts follow `MAJOR.MINOR.PATCH` versioning rules.
   * `MAJOR`: Breaking schema modifications, deleted endpoints, or changed authentication semantics.
   * `MINOR`: Non-breaking additions (new endpoints, optional request properties, additional response fields).
   * `PATCH`: Bug fixes, documentation clarifications, and internal optimizations.
2. **Deprecation Notice**: Any endpoint scheduled for deprecation will return the `Deprecation: true` and `Sunset: <date>` HTTP headers at least one sprint prior to removal.
3. **Backward Compatibility Guarantee**: All public auth endpoints support legacy aliases (`/login`, `/register`, `/verify-otp`, `/resend-otp`, `/refresh`, `/me`, `/logout`) alongside standard `/api/auth/*` namespaces.
