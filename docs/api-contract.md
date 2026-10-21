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

#### `GET /api/prices/forecast`
Returns the latest stored model prediction for each upcoming interval at a settlement location. This endpoint reads `predicted_price`; it does not generate a new forecast.
* **Query Parameters**:
  * `location` (string, optional, default: `"LZ_NORTH"`)
  * `hours` (integer, optional, default: `48`, range: 1–168)
* **Success Response (200 OK)**:
  ```json
  {
    "success": true,
    "message": "Price forecast retrieved",
    "data": {
      "horizonHours": 48,
      "points": [
        {
          "intervalStartUtc": "2026-10-10T00:00:00",
          "predictedPrice": 32.5,
          "modelVersion": "xgboost-baseline",
          "generatedAt": "2026-10-09T21:00:00"
        }
      ]
    }
  }
  ```
* `confidenceScore` (0-1, higher = more confident) is omitted when unavailable. The stored score is a normalized recent-error ratio; the API exposes it as `1 / (1 + ratio)` so it has consistent confidence semantics. A backend check runs every minute and detects newly generated low-confidence forecasts without waiting for a page request. It creates unread alerts in the existing data-freshness notification feed and sends one email per recipient, location, and forecast run. Configure the threshold with `FORECAST_CONFIDENCE_THRESHOLD` (default `0.7`); SMTP must be configured for email delivery.
* **Empty window**: `200` with `"points": []`. **Errors**: `400` for blank `location` or `hours` outside 1-168; `403` without a valid token.

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

#### `GET /api/workload-schedules`
Returns the authenticated operator's saved workload schedules ordered by UTC start time.

#### `POST /api/workload-schedules`
Creates a workload schedule for the authenticated operator. Request fields: `title`, `type`, `cluster`, `powerKw`, `savings`, `startUtc`, `endUtc`, and optional `notes`. Timestamps are UTC ISO local date-times, and `endUtc` must be after `startUtc`.

#### `PATCH /api/workload-schedules/{id}/status`
Updates a saved schedule status. Supported statuses: `Scheduled`, `Running`, `Throttled`, and `Completed`.

#### `DELETE /api/workload-schedules/{id}`
Deletes a saved schedule owned by the authenticated operator.

#### `POST /api/jobs`
Creates a structured compute job for the authenticated operator per GRID-9 / GRID-112.
* **Request Body**:
  ```json
  {
    "name": "LLM Fine-Tuning Run #42",
    "cpuHours": 64.0,
    "gpuHours": 16.0,
    "originalStartTime": "2026-10-20T10:00:00Z",
    "deadline": "2026-10-21T18:00:00Z",
    "flexibilityWindowHours": 12,
    "isExempt": false,
    "rawConstraints": "Can wait up to 12 hours for low price valley, finish by tomorrow evening"
  }
  ```
* **Success Response (201 Created)**: Returns the persisted Job entity with generated `id`, `ownerEmail`, `status: "PENDING"`, and audit timestamps.

#### `GET /api/jobs`
Returns all compute jobs owned by the authenticated operator, ordered chronologically by `originalStartTime` ascending.

#### `GET /api/jobs/{id}`
Returns details for a specific compute job owned by the authenticated operator. Returns 404 if the job does not exist or belongs to another operator.

#### `PUT /api/jobs/{id}`
Updates an existing job. All fields (including `flexibilityWindowHours`, `deadline`, and `isExempt`) are editable.

#### `DELETE /api/jobs/{id}`
Deletes a compute job owned by the authenticated operator.

---

### 6.3 Compute Job Schema & Validation Specification (GRID-112 / GRID-9)

The **Job** entity represents a compute workload submitted for schedule optimization:

| Field | Type | Required | Default | Description & Semantics |
|---|---|---|---|---|
| `id` | Long | Read-only | Generated | Unique primary identifier (PostgreSQL identity). |
| `name` | String | Yes | - | Human-readable workload name (1–255 characters). |
| `cpuHours` | Double | Yes | `0.0` | Total CPU core-hours required ($\ge 0.0$). |
| `gpuHours` | Double | Yes | `0.0` | Total GPU hours required ($\ge 0.0$). |
| `originalStartTime` | ISO-8601 UTC String | Yes | - | Baseline scheduled start time in UTC. |
| `deadline` | ISO-8601 UTC String | Yes | - | Hard completion deadline in UTC. Must be $\ge$ `originalStartTime`. |
| `flexibilityWindowHours` | Integer | Yes | `0` | Delay tolerance in hours ($\ge 0$). Start time may shift within $[\text{originalStartTime}, \text{originalStartTime} + \text{flexibilityWindowHours}]$. |
| `isExempt` | Boolean | Yes | `false` | Exempt-from-rescheduling flag. If `true`, job is non-negotiable and pinned to `originalStartTime` (GRID-86 / GRID-24). |
| `rawConstraints` | String | No | `null` | Optional natural-language constraint text (up to 1,000 characters). Auto-parsed by LLM into structured fields in Sprint 4 (GRID-39 / GRID-40). |
| `status` | String | No | `"PENDING"` | Workflow status: `"PENDING"`, `"SCHEDULED"`, `"RUNNING"`, `"COMPLETED"`, `"CANCELLED"`. |
| `ownerEmail` | String | Read-only | Auth user | Authenticated tenant operator email for tenancy isolation. |
| `createdAt` | ISO-8601 UTC String | Read-only | Auto | Timestamp when record was created. |
| `updatedAt` | ISO-8601 UTC String | Read-only | Auto | Timestamp when record was last updated. |

#### Field Constraints & Validation Rules (GRID-9 AC4)
1. **Resource Requirements**: At least one of `cpuHours > 0.0` or `gpuHours > 0.0` must be true. Both must be non-negative.
2. **Temporal Validity**: `deadline` must be strictly greater than or equal to `originalStartTime`.
3. **Flexibility Range**: `flexibilityWindowHours` must be an integer $\ge 0$. A value of `0` denotes an inflexible job that cannot be delayed.
4. **Exemption Semantics**: When `isExempt` is `true`, the optimizer models the flexibility window as zero-width, keeping the job at `originalStartTime` regardless of price signals.

---

### 6.4 Schedule Optimization Engine Contract (GRID-124)

**Status:** DRAFT: to be agreed by the team by end of day 1 of the sprint, then frozen. After that, changes are only recorded in the *Contract change notes* below. Job fields use the exact names of the Job schema in §6.3 (GRID-112).

#### `POST /api/schedules/optimize`
Evaluates jobs against forecast prices and cluster capacity and proposes a start time per job. Pure function of its input: nothing is persisted this sprint (persistence is Sprint 4). Same envelope as §2.5A.

**Slots.** One slot = 1 hour, identified by its UTC start (`intervalStartUtc`, same as `GET /api/prices/forecast`). A job is a single block: it occupies the one slot that starts at its start time and consumes all of its `cpuHours` / `gpuHours` in that slot. This is a deliberate Sprint 3 simplification: the Job schema has no duration field, and capacity is expressed as concurrent CPU/GPU hours per slot. A multi-hour duration can be added later as an optional, backward-compatible field.

**Request**
```json
{
  "jobs": [
    {
      "id": 101,
      "name": "LLM Fine-Tuning Run #42",
      "cpuHours": 64.0,
      "gpuHours": 16.0,
      "originalStartTime": "2026-10-20T10:00:00Z",
      "deadline": "2026-10-21T18:00:00Z",
      "flexibilityWindowHours": 12,
      "isExempt": false
    }
  ],
  "forecastLocation": "LZ_NORTH",
  "horizonHours": 48,
  "forecast": [
    { "intervalStartUtc": "2026-10-20T10:00:00Z", "predictedPrice": 112.0 }
  ],
  "clusterCapacity": {
    "maxConcurrentCpuHours": 256.0,
    "maxConcurrentGpuHours": 64.0
  },
  "capacityOverrides": [
    { "intervalStartUtc": "2026-10-20T10:00:00Z", "maxCpuHours": 128.0, "maxGpuHours": 32.0 }
  ]
}
```

| Field | Type | Required | Rule |
|---|---|---|---|
| `jobs[].id` | integer | Yes | Job `id`; unique within request |
| `jobs[].name` | string | No | Echoed in the response for readability |
| `jobs[].cpuHours` / `gpuHours` | number ≥ 0 | Yes | Total for the job, consumed in its single slot |
| `jobs[].originalStartTime` | ISO 8601 UTC, on the hour | Yes | Baseline start |
| `jobs[].deadline` | ISO 8601 UTC | Yes | Job must **end** (start + 1 h) at or before this |
| `jobs[].flexibilityWindowHours` | integer ≥ 0 | Yes | Start may be in `[originalStartTime, originalStartTime + flexibilityWindowHours]`. Jobs never start earlier than `originalStartTime`. |
| `jobs[].isExempt` | boolean | Yes | `true` = never moved |
| `forecast[]` | array | One of `forecast` / `forecastLocation` | Explicit price per slot (`predictedPrice` in $/MWh). Makes the run fully reproducible. |
| `forecastLocation`, `horizonHours` | string, integer | One of `forecast` / `forecastLocation` | Server resolves per-slot prices for that location from the forecast service (same data as `GET /api/prices/forecast`), starting at the earliest `originalStartTime`. `horizonHours` defaults to `48`. |
| `clusterCapacity` | object | No | Capacity applied to every slot. Falls back to the defaults below. |
| `capacityOverrides[]` | array | No | Per-slot capacity; takes precedence over `clusterCapacity` for that slot |

If both `forecast` and `forecastLocation` are sent, `forecast` wins. If neither is sent the request is a `400`. Either way the optimiser works on the same resolved list of per-slot prices. Other Job fields (`rawConstraints`, `status`, `ownerEmail`, timestamps) are not optimiser inputs and are ignored if sent.

**Price fallback (forecast missing).** When `forecastLocation` is used and no stored forecast row covers the window, the server builds an hour-of-day price curve from stored `electrical_price` history instead of failing, and sets `usedSeededFallbackPrices: true` in the response (the explanation text also says "seeded fallback"):

- **Seasonal curve:** the average price per UTC hour of day over the same calendar month one year earlier.
- **Recent curve:** the same average over the 30 days before the horizon start. Used only if the location's latest row is within 7 days of the horizon start; otherwise skipped.
- **Blend:** `w * recent + (1 - w) * seasonal`, with `w = gacs.optimiser.fallback-recent-weight` (default `0.5`). If only one curve has data for an hour, that curve is used as is.
- **Last resort:** an hour with no history uses a built-in diurnal curve (`PriceSeriesProvider.SEEDED_CURVE_USD_PER_MWH`, $15–$95/MWh).

The curve is repeated across every slot in the horizon, so every slot is priced. Partial forecast coverage does not trigger the fallback: uncovered hours are not candidate slots.

**Cost basis (AC2).** Constants, configurable (`gacs.optimiser.*`), defaults seeded:

| Property | Default | Meaning |
|---|---|---|
| `cpu-kwh-per-hour` | `0.05` | kWh per CPU-hour |
| `gpu-kwh-per-hour` | `0.40` | kWh per GPU-hour (400 W GPU baseline) |

```
jobKwh(job)        = cpuHours * cpuKwhPerHour + gpuHours * gpuKwhPerHour
jobCostUsd         = jobKwh / 1000 * predictedPrice(slot of start)      // price is $/MWh
optimizedCostUsd   = Σ jobCostUsd over all jobs at their proposed start   // the run's total cost
baselineCostUsd    = Σ jobCostUsd over all jobs at originalStartTime
```
Costs are rounded to 4 decimal places. A start whose slot has no forecast price is infeasible.

**Capacity (AC3).** Per slot, the sum of `cpuHours` (and of `gpuHours`) of jobs in that slot must be ≤ the slot's CPU (GPU) limit. The limit for a slot is, in order: its `capacityOverrides[]` entry, else `clusterCapacity`, else the configurable seeded defaults `gacs.optimiser.default-max-cpu-hours-per-slot=256.0` and `default-max-gpu-hours-per-slot=64.0`. Exempt and no-feasible-slot jobs are fixed at their original time and consume capacity there; the optimiser places the remaining jobs around them.

**Response (200 OK)**
```json
{
  "success": true,
  "message": "Optimized schedule generated",
  "data": {
    "runId": "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
    "status": "OPTIMAL",
    "baselineCostUsd": 1.0752,
    "optimizedCostUsd": 0.2304,
    "projectedSavingsUsd": 0.8448,
    "projectedSavingsPercent": 78.57,
    "plainEnglishExplanation": null,
    "items": [
      {
        "jobId": 101,
        "jobName": "LLM Fine-Tuning Run #42",
        "baselineStart": "2026-10-20T10:00:00Z",
        "scheduledStart": "2026-10-21T01:00:00Z",
        "scheduledEnd": "2026-10-21T02:00:00Z",
        "allocatedCpuHours": 64.0,
        "allocatedGpuHours": 16.0,
        "perJobStatus": "RESCHEDULED",
        "baselineCostUsd": 1.0752,
        "optimizedCostUsd": 0.2304,
        "savingsUsd": 0.8448
      }
    ]
  }
}
```
* `runId`: UUID v4 generated per request, not stored yet. Later features (approval, audit log, overrides, savings tracking) reference a decision by this ID.
* `status` (run level): `OPTIMAL` when every job was either placed at its cheapest feasible slot or is exempt; `PARTIAL` when at least one job is `UNCHANGED_NO_FEASIBLE_SLOT`.
* `optimizedCostUsd` (run level) is the total cost of the proposed schedule. `scheduledStart` is the proposed start time per job; `scheduledEnd` = `scheduledStart` + 1 h.
* `plainEnglishExplanation`: optional, may be `null` this sprint.
* `items[]` lists every input job exactly once.
* `perJobStatus`:
  - `RESCHEDULED`: start moved to a cheaper slot within its flexibility window.
  - `UNCHANGED_EXEMPT`: kept at `originalStartTime` because `isExempt` is `true`.
  - `UNCHANGED_NO_FEASIBLE_SLOT`: kept at `originalStartTime` because no start satisfies window, deadline, forecast coverage and capacity.
  - `UNCHANGED_OPTIMAL`: not exempt and feasible, but the original start is already the cheapest feasible slot. (Added beyond the three statuses in the ticket so that an already-optimal job is not mislabelled.)

**No feasible schedule (AC5).** A job with no feasible start (including one that cannot finish by `deadline` even at its original start) keeps `scheduledStart = baselineStart`, gets `perJobStatus = UNCHANGED_NO_FEASIBLE_SLOT`, and is listed in `items`. The response is still `200`, the run `status` is `PARTIAL`, and there is no error and no schedule that moves other jobs to hide it.

**Errors.** `400` only for malformed JSON, missing fields, neither `forecast` nor `forecastLocation`, duplicate job `id`, negative values, or a start time not on the hour. Lack of capacity or forecast coverage is **not** an error.

**Contract change notes**
| Date | Change | By |
|---|---|---|
| - | Initial draft (merges the GRID-112 and Sprint 3 drafts) | - |

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
