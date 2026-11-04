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
* `confidenceScore` (0-100, higher = more confident) is omitted when unavailable. `data_scripts/predictions.py` computes the final score itself (a normalized recent-error ratio mapped through `100 / (1 + ratio)`) before writing it to `predicted_price.confidence_score`; the API returns that stored value directly, with no further conversion. A backend check runs every minute and detects newly generated low-confidence forecasts without waiting for a page request. It creates unread alerts in the existing data-freshness notification feed and sends one email per recipient, location, and forecast run. Configure the threshold with `FORECAST_CONFIDENCE_THRESHOLD` (0-100 scale, default `40`); SMTP must be configured for email delivery.
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
    "priority": "NORMAL",
    "rawConstraints": "Can wait up to 12 hours for low price valley, finish by tomorrow evening",
    "parsedAt": "2026-10-20T09:30:00",
    "confirmationOutcome": "CONFIRMED"
  }
  ```
* **Success Response (201 Created)**: Returns the persisted Job entity with generated `id`, `ownerEmail`, `status: "PENDING"`, `priority: "NORMAL"`, and audit timestamps.

#### `GET /api/jobs`
Returns all compute jobs owned by the authenticated operator, ordered chronologically by `originalStartTime` ascending. Each job includes its configured `priority`.

#### `GET /api/jobs/{id}`
Returns details for a specific compute job owned by the authenticated operator. Returns 404 if the job does not exist or belongs to another operator.

#### `PUT /api/jobs/{id}`
Updates an existing job. All fields (including `priority`, `flexibilityWindowHours`, `deadline`, and `isExempt`) are editable. Unknown `priority` values are rejected with `400 Bad Request`.

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
| `rawConstraints` | String | No | `null` | Original natural-language source retained verbatim (up to 1,000 characters). Persistence does not trigger parsing. |
| `parsedAt` | ISO-8601 UTC local date-time | No | `null` | Actual parser timestamp supplied by the caller, e.g. `2026-10-20T09:30:00`; never inferred from job audit timestamps. |
| `confirmationOutcome` | String (Enum) | No | `null` | Latest parse decision: `PENDING`, `CONFIRMED`, or `REJECTED`. A supplied nonnull `parsedAt` defaults this to `PENDING` when the outcome is omitted. |
| `status` | String | No | `"PENDING"` | Workflow status: `"PENDING"`, `"SCHEDULED"`, `"RUNNING"`, `"COMPLETED"`, `"CANCELLED"`. |
| `priority` | String (Enum) | No | `"NORMAL"` | Workload priority level: `"CRITICAL"`, `"HIGH"`, `"NORMAL"`, `"LOW"`. Default `"NORMAL"`. Used by the optimizer for tie-breaking when jobs compete for capacity - see §6.4's *Objective and tie-break order* for the exact rule (GRID-155 / GRID-124). Unknown values are rejected with 400 Bad Request. |
| `ownerEmail` | String | Read-only | Auth user | Authenticated tenant operator email for tenancy isolation. |
| `createdAt` | ISO-8601 UTC String | Read-only | Auto | Timestamp when record was created. |
| `updatedAt` | ISO-8601 UTC String | Read-only | Auto | Timestamp when record was last updated. |

#### Natural-language provenance (AC4.1 / GRID-39)

Create, update, list, and detail responses include `rawConstraints`, `parsedAt`, and `confirmationOutcome`. Only the latest parse is stored. Existing jobs and new jobs without parse metadata have null metadata; no timestamps or decisions are backfilled.

On `PUT`, omission of any of these three fields preserves its stored value; explicit null clears it, subject to final-state validation. Changing `rawConstraints` (including clearing it) first resets the stored timestamp and outcome, then applies any replacement metadata in the request. Supplying a nonnull `parsedAt` with no outcome sets `PENDING`, including on a reparse of unchanged source. An explicitly null outcome remains null. For example, `"parsedAt": null, "confirmationOutcome": null` clears metadata while retaining the source if it is omitted.

Any nonnull parse metadata requires nonblank source text, and a nonnull outcome requires a nonnull timestamp. Invalid combinations, malformed timestamps, unknown outcomes, and source text longer than 1,000 characters return HTTP 400. These fields describe caller-supplied parsing and confirmation information; they do not change scheduling behavior or introduce parser or confirmation UI integration.

Schema rollout: the application currently uses Hibernate `ddl-auto=update`. The additive, idempotent SQL file `V3__add_job_parse_metadata.sql` is provided for managed PostgreSQL schema updates; no migration runner is configured to execute files in `db/migration` automatically. Apply the SQL explicitly when managing schema outside Hibernate. It adds nullable columns and an outcome check constraint without altering existing row values.

#### PostgreSQL CRUD evidence tests (AC4.2 / AC5.1)

`JobParseMetadataIntegrationTest` runs the shared CRUD assertions using the existing H2 test configuration during ordinary Maven verification. `JobParseMetadataPostgresIntegrationTest` runs the same assertions against a separately configured PostgreSQL test database when `TEST_POSTGRES_URL` is set. No Docker or Testcontainers setup is required, and no CI infrastructure changes are needed.

To run the PostgreSQL evidence suite, set `TEST_POSTGRES_URL` to a JDBC URL such as `jdbc:postgresql://localhost:5432/gacs_test`, `TEST_POSTGRES_USERNAME` (default `postgres`), and `TEST_POSTGRES_PASSWORD` (default empty), then run `./mvnw -B -Dtest=JobParseMetadataPostgresIntegrationTest test` from `backend` (Windows: `.\mvnw.cmd` instead of `./mvnw`). Use a test database account permitted to create and drop schemas. Without `TEST_POSTGRES_URL`, this PostgreSQL suite is explicitly skipped; an H2 pass alone does not establish AC5.1. Once configured, database connection or SQL errors fail the suite.

Before Spring creates its datasource, the PostgreSQL suite creates a unique schema and explicitly executes the test-only `src/test/resources/db/jobs-baseline.sql`, then the production V2 priority and V3 parse metadata SQL scripts. Each SQL file executes intact so PostgreSQL `DO $$` blocks retain their meaning. Hibernate schema generation is disabled (`spring.jpa.hibernate.ddl-auto=none`), and automatic Spring SQL initialization is disabled. All connections use the test schema; cleanup drops only that generated schema. Existing application tables are not modified. The baseline is test infrastructure only; production SQL must still be applied manually.

Requests commit through the existing controller/service/repository path without an enclosing test transaction. Fresh repository reads and direct SQL queries verify source text, microsecond timestamps, and confirmation outcomes across creation, update, detail, and list responses. Tests also cover omission versus null, source replacement, reparsing, owner isolation, and invalid updates leaving every stored job field unchanged. The PostgreSQL suite additionally checks script reapplication. `PENDING` and `REJECTED` are evidence states, not save barriers: valid job edits remain allowed, and existing job validation applies to every confirmation state.

#### Field Constraints & Validation Rules (GRID-9 AC4)
1. **Resource Requirements**: At least one of `cpuHours > 0.0` or `gpuHours > 0.0` must be true. Both must be non-negative.
2. **Temporal Validity**: `deadline` is the latest time the job must **finish**, and must be greater than or equal to `originalStartTime`. `cpuHours` and `gpuHours` are the total *work* the job needs, not its duration; the optimiser derives the duration (see §6.4).
3. **Flexibility Range**: `flexibilityWindowHours` must be an integer $\ge 0$. A value of `0` denotes an inflexible job that cannot be delayed.
4. **Exemption Semantics**: When `isExempt` is `true`, the optimizer models the flexibility window as zero-width, keeping the job at `originalStartTime` regardless of price signals.
5. **Priority Range (GRID-155 AC1 / AC1.1)**: `priority` must be one of `CRITICAL`, `HIGH`, `NORMAL`, `LOW`. Defaults to `NORMAL` if omitted or null. Unknown values are rejected with `400 Bad Request`.

---

### 6.4 Schedule Optimization Engine Contract (GRID-124)

**Status:** DRAFT: to be agreed by the team by end of day 1 of the sprint, then frozen. After that, changes are only recorded in the *Contract change notes* below. Job fields use the exact names of the Job schema in §6.3 (GRID-112).

#### `POST /api/schedules/optimize`
Evaluates jobs against forecast prices and cluster capacity and proposes a start time and an hourly allocation per job. Pure function of its input: nothing is persisted this sprint (persistence is Sprint 4). Same envelope as §2.5A.

**Slots and work.** One slot = 1 hour, identified by its UTC start (`intervalStartUtc`, same as `GET /api/prices/forecast`). `cpuHours` and `gpuHours` are the total **work** a job needs, not its duration. In each slot the optimiser gives a job a *share* `x` of that work (`0 < x ≤ 1`), so the job uses `x * cpuHours` CPU-hours and `x * gpuHours` GPU-hours in that slot. A job's shares add up to exactly 1.
* CPU and GPU cannot be separated within a job: one share `x` drives both.
* A job runs in consecutive slots, with no pauses and no splitting.
* There is no per-job rate cap: a job may take whatever capacity is free in a slot. Its duration is therefore an **output** of the optimisation (the number of slots it needs), limited by the capacity left after other jobs.
* `scheduledStart` is the start of the first slot used and `scheduledEnd` is the end of the last.

**Objective and tie-break order (GRID-155 / GRID-124).** The optimiser compares plans in this order:
1. Place as many jobs as possible.
2. Serve higher priorities: more `CRITICAL` jobs placed, then more `HIGH`, then `NORMAL`, then `LOW`. This outranks cost, so when capacity only fits one of several competing jobs the higher-priority job gets the slot even if the other would be cheaper. Priority is not a cost weight: jobs that do not compete are optimised exactly as before.
3. Minimise total cost.
4. Remaining ties go to the job that comes first by: higher `priority`, earlier `deadline`, fewer total resource-hours (`cpuHours + gpuHours`), earlier `originalStartTime`, then lower `id` (ids are assigned in creation order, standing in for creation time, which the request does not carry).

Priority never overrides a job's `deadline` or `isExempt`: an exempt job always runs at its original time and keeps its capacity, and a job that cannot finish by its deadline is not placed. A job that loses is `NO_FEASIBLE_SLOT`, keeps its original time, and its `reason` names the job that took the capacity, e.g. `... UTC: job id 7 (CRITICAL) and 1 other(s) use the capacity this job needs ...`. Both solvers apply this order.

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
      "isExempt": false,
      "priority": "NORMAL"
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
| `jobs[].name` | string | Yes | 1–255 characters (Job schema §6.3); echoed in the response |
| `jobs[].cpuHours` / `gpuHours` | number ≥ 0 | Yes | Total work for the job; at least one must be > 0 (Job schema §6.3) |
| `jobs[].originalStartTime` | ISO 8601 UTC, on the hour | Yes | Baseline start |
| `jobs[].deadline` | ISO 8601 UTC | Yes | Job must **finish** (end of its last slot) at or before this |
| `jobs[].flexibilityWindowHours` | integer ≥ 0 | Yes | Start may be in `[originalStartTime, originalStartTime + flexibilityWindowHours]`. Jobs never start earlier than `originalStartTime`. |
| `jobs[].isExempt` | boolean | Yes | `true` = never moved |
| `jobs[].priority` | string | No | Priority level (`CRITICAL`, `HIGH`, `NORMAL`, `LOW`; default `NORMAL`). See *Objective and tie-break order* above (GRID-155 AC1/AC3). Unknown values are rejected with `400`. |
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
slotCostUsd(job,t) = share(job,t) * jobKwh / 1000 * predictedPrice(t)    // price is $/MWh
jobCostUsd         = Σ slotCostUsd over the slots the job uses
optimizedCostUsd   = Σ jobCostUsd over all scheduled jobs                 // the run's total cost
baselineCostUsd    = Σ baseline jobCostUsd over the same scheduled jobs
```
The **baseline** of a job is its cost if it started at `originalStartTime` and, alone on an otherwise empty cluster, took the largest share capacity allows in each consecutive slot until its work is done. Jobs with `NO_FEASIBLE_SLOT` are left out of both totals so that savings compare like with like. Costs are rounded to 4 decimal places. A slot with no forecast price cannot be used.

**Capacity (AC3).** Per slot, the sum over jobs of `share * cpuHours` (and of `share * gpuHours`) must be ≤ the slot's CPU (GPU) limit. The limit for a slot is, in order: its `capacityOverrides[]` entry, else `clusterCapacity`, else the configurable seeded defaults `gacs.optimiser.default-max-cpu-hours-per-slot=256.0` and `default-max-gpu-hours-per-slot=64.0`. Exempt jobs run from `originalStartTime` exactly as in their baseline, are never moved and consume capacity there; the optimiser places the remaining jobs around them. Jobs with `NO_FEASIBLE_SLOT` consume no capacity.

**Response (200 OK)**
```json
{
  "success": true,
  "message": "Optimized schedule generated",
  "data": {
    "runId": "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
    "status": "SCHEDULED",
    "provenOptimal": true,
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
        "allocations": [
          { "intervalStartUtc": "2026-10-21T01:00:00Z", "cpuHours": 64.0, "gpuHours": 16.0 }
        ],
        "perJobStatus": "OPTIMISED",
        "reason": null,
        "baselineCostUsd": 1.0752,
        "optimizedCostUsd": 0.2304,
        "savingsUsd": 0.8448
      }
    ]
  }
}
```
* `runId`: UUID v4 generated per request. Successful API runs atomically persist an `OptimizerRun` (configured solver name, version, configuration snapshot, outcome and optimality) and a linked `OptimizationPlan` with a shared `VARCHAR(36)` run ID enforced by a foreign key. The plan stores the immutable result, authenticated owner's database user ID, UTC creation time, and a separate review state initially `PENDING`. Decision routes reference this ID; the optimizer response shape and optimization `status` are unchanged. Failed optimization requests create neither row. Each call stores the complete result as unbounded `text`; storage growth and retention need review before large-scale use. Authenticated principals without a database user receive `401`. The run's persisted explanation remains scoped to the authenticated operator.
* `status` (run level): `SCHEDULED` when every job is scheduled or exempt; `NEEDS_ATTENTION` when at least one job is `NO_FEASIBLE_SLOT`; `INFEASIBLE` when no job at all could be placed. An `INFEASIBLE` run is stored but can never be approved, applied or exported (`403 NOT_APPROVED`). If the solver throws, the response is `503` with `data.runId`; the run is recorded as `FAILED` (with its input snapshot) and has no plan, so decision and export routes return `404`. An exact-solver time-out is not a failure: the heuristic plan is returned with `provenOptimal=false`. Failed optimization requests that never reach the solver (`400`, `401`, `422`) create no rows (GRID-142).
* `provenOptimal` (run level): `true` when the solver proved that no cheaper schedule exists; `false` when a heuristic fallback produced the plan. A `false` plan still satisfies every window, deadline and capacity rule. Informational only.
* `optimizedCostUsd` (run level) is the total cost of the proposed schedule. `scheduledStart` is the start of the job's first slot and `scheduledEnd` the end of its last.
* `items[].allocations[]`: the slots the job uses and the CPU/GPU hours it takes in each. Their sum equals `allocatedCpuHours` / `allocatedGpuHours`.
* `items[].reason`: `null`, except for `NO_FEASIBLE_SLOT`, where it is a short human-readable cause (for example "cluster full between 10:00 and 18:00").
* `plainEnglishExplanation`: optional text summarising the run; may be `null`.
* `items[]` lists every input job exactly once.
* `perJobStatus`:
  - `OPTIMISED`: not exempt and feasible; placed on its cheapest feasible plan. The plan may or may not start at `originalStartTime`; compare the two fields if that matters.
  - `UNCHANGED_EXEMPT`: kept at `originalStartTime` because `isExempt` is `true`.
  - `NO_FEASIBLE_SLOT`: no plan satisfies window, deadline, forecast coverage and capacity; the job is not scheduled (see below).

**No feasible slot (AC5; changed 2026-10-20).** The input is valid but the cluster has no room for the job in any allowed hour. The job is listed in `items` with `perJobStatus = NO_FEASIBLE_SLOT`, `scheduledStart = null`, `scheduledEnd = null`, no `allocations` and a `reason`. No time is invented for it. The response is still `200`, the run `status` is `NEEDS_ATTENTION`, and the job is excluded from the cost totals. Queuing such jobs for a later run needs stored state and is planned for Sprint 4.

**Errors.** Three cases, in the standard error envelope:
* **Invalid input, `400`:** malformed JSON, missing fields, blank `name`, neither `forecast` nor `forecastLocation`, duplicate job `id`, negative values, both `cpuHours` and `gpuHours` equal to 0, a start time not on the hour, `deadline` before `originalStartTime`, or a job whose work cannot be finished between `originalStartTime` and `deadline` even alone on an empty cluster.
* **Invalid or missing data, `422`:** the forecast or other data source is missing, corrupted or does not cover a job's `originalStartTime` slot, so the baseline cannot be priced. The message names the missing or bad data.
* **No capacity** is **not** an error: see *No feasible slot* above.

### Persisted run explanation

`GET /api/schedules/{runId}/explanation` returns the explanation saved with the optimization run:

```json
{
  "success": true,
  "message": "Run explanation retrieved",
  "data": {
    "runId": "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
    "explanation": "Scheduled 1 of 1 job(s). ...",
    "items": [{
      "jobId": 101,
      "whyThisPlacement": "Placed on its cheapest feasible plan.",
      "constraints": [{
        "constraintType": "DEADLINE",
        "code": "DEADLINE_SLACK",
        "slackHours": 3,
        "relatedJobIds": [],
        "explanation": "Finishes before its deadline with 3 hours of slack."
      }]
    }]
  }
}
```

The `items` array contains saved per-job explanations and constraint impacts, keyed by job ID.
Unscheduled jobs have no constraint impacts; the UI must not report their constraints as satisfied.
Explanation strings are untrusted text and must be escaped when rendered. Per-job cost differences
come from the matching optimization result (`baselineCostUsd`, `optimizedCostUsd`, `savingsUsd`).

The endpoint only reads the persisted explanation for the authenticated operator. It does not re-run the
optimizer or invoke explanation generation again. A run belonging to another operator is returned as `404`.

**Contract change notes**
| Date | Change | By |
|---|---|---|
| - | Initial draft (merges the GRID-112 and Sprint 3 drafts) | - |
| 2026-10-20 | Replaced the single 1-hour block with work-based allocation: `cpuHours` / `gpuHours` are total work; the optimiser decides hourly shares and duration; consecutive slots; no rate cap; CPU and GPU share one schedule. Added `allocations[]`. | - |
| 2026-10-20 | `deadline` defined as latest finish time; `name` required and at least one of `cpuHours` / `gpuHours` > 0, matching §6.3. | - |
| 2026-10-20 | Run `status` values changed from `OPTIMAL` / `PARTIAL` to `SCHEDULED` / `NEEDS_ATTENTION`; added `provenOptimal` so the solver's certainty is reported separately from whether every job got a place. | - |
| 2026-10-20 | AC5: a job with no feasible slot is no longer returned at its original time; it has null times, status `NO_FEASIBLE_SLOT` (was `UNCHANGED_NO_FEASIBLE_SLOT`) and a `reason`. | - |
| 2026-10-20 | Errors split into invalid input (`400`), invalid or missing data (`422`) and no capacity (`200`). Baseline defined as the job alone on an empty cluster. | - |
| 2026-10-28 | Added `priority` (`CRITICAL`, `HIGH`, `NORMAL`, `LOW`; default `NORMAL`) to Job schema, CRUD API, and optimizer contract (GRID-155 AC1 / AC1.1 / GRID-124). | - |
| 2026-11-01 | Added §6.6: deterministic CSV format for approved schedules (columns, row order, timestamp and number format) for execution adapters (GRID-135 AC1). | - |
| 2026-11-01 | §6.6: added the approved-schedule JSON format with complete run metadata and per-job slots (GRID-135 AC2). | - |
| 2026-11-01 | §6.6: added `GET /api/schedules/{runId}/export.csv` and `.json` behind an approval gate (403 not approved; 409 stale or invalidated) with `schedule-run-{runId}` attachment filenames (GRID-135 AC3). | - |
| 2026-11-01 | §6.6: the JSON export adds `run.createdAt`, `run.solver` and `approval` (GRID-135 AC2). | - |
| 2026-11-01 | Added §6.7 documenting the audit log queries, with a new `jobId` filter backed by `optimization_plan_jobs`, and the stored schedule (owner only) in the event detail (GRID-134 AC6). | - |
| 2026-11-01 | §6.4: added *Objective and tie-break order*, documenting the rule `priority` had been reserved for but did not yet affect - the optimiser now orders competing jobs by `priority`, breaks remaining ties by `deadline`, then total resource-hours, then the original `originalStartTime`/`id` fallback (GRID-155). | - |
| 2026-11-01 | §6.4: added run `status` `INFEASIBLE` (nothing placeable; never approvable or exportable) and `503` with `runId` when the solver fails, recorded as a `FAILED` run with no plan (GRID-142 AC1.3). | - |
| 2026-11-01 | Added §6.8: automatic material-input invalidation, shared backend execution gating, approved-run application, and plan detail (GRID-11 AC2.3). | - |
| 2026-11-04 | §6.5: a plan now holds exactly one terminal decision. Once APPROVED, REJECTED, or REVISION_REQUESTED, every further `approve`/`reject`/`revise` call for that run returns `409` with `data.reason = "ALREADY_DECIDED"`, superseding the "repeated requests ... create additional history entries" text below, which described the previous (reversible) behavior (usability bug 3). An operator who wants to retract an approval must generate a new recommendation rather than reject/revise the same run. | - |

### 6.5 Operator decisions (GRID-11 AC2 / AC2.2)

#### Browsing plans for review (GRID-55)

`GET /api/schedules?page=0&size=20` returns a standard `ApiResponse<Page<PlanSummary>>`
for the authenticated operator only, newest first by `createdAt`, then descending `runId`.
Each summary contains `runId`, UTC `createdAt`, and `reviewState` (`PENDING`, `APPROVED`,
`REJECTED`, `REVISION_REQUESTED`, `STALE`, or `INVALIDATED`). Page indices start at zero;
`size` must be 1–100. Invalid pagination returns **400**. Missing authentication returns
**403**; an authenticated email without a database user returns **401**. Administrators
cannot list another operator's plans.

The list reports stored states. Before offering decisions, the UI reads
`GET /api/schedules/{runId}` (§6.8) to check validity and display the persisted recommendation.
A **409** decision refusal retains its error message and requires a refresh before another
decision. Approval, rejection, and revision each require explicit confirmation with a
justification. The UI updates state only after a successful server response and refreshes
decision history. It does not imply that an approval dispatched a workload.

The Jobs optimization summary links to `/approvals?runId={runId}` with **Review and approve**.
The review page selects that exact run, including runs outside the first list page. Once approval
succeeds, **Add approved schedule to calendar** calls `POST /api/schedules/{runId}/apply` (§6.8).
Jobs no longer creates individual calendar entries from an unapproved optimization preview.
An already approved run does not require another approval before applying it. Application
refusals require refreshing the plan; the backend remains authoritative if approval changes
between reviewing and applying.

#### Recording a decision

All routes require JWT authentication and ownership of the persisted optimization plan. Existing users act as operators; administrator privileges do not bypass ownership. The operator's numeric database ID is resolved from the authenticated email, never from request fields or the JWT's numeric user ID claim.

| Route | Recorded action | New review state |
|---|---|---|
| `POST /api/schedules/{runId}/approve` | `APPROVE` | `APPROVED` |
| `POST /api/schedules/{runId}/reject` | `REJECT` | `REJECTED` |
| `POST /api/schedules/{runId}/revise` | `REVISE` | `REVISION_REQUESTED` |

Each route requires a JSON object with a nonblank string `comment`, limited to 2,000 characters before trimming. Missing, null, empty, whitespace-only, and non-string comments return `400` before plan lookup or locking. This follows the approved audit log requirement for operator rationale. Identity fields are ignored. Example:

```json
{"comment":"Approved schedule after verifying cooling margin."}
```

All three return **200 OK** in the standard envelope:

```json
{
  "success": true,
  "message": "Schedule decision recorded",
  "data": {
    "decisionId": 42,
    "runId": "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
    "action": "APPROVE",
    "operatorId": 7,
    "comment": "Approved schedule after verifying cooling margin.",
    "decidedAt": "2026-10-30T09:00:00Z",
    "reviewState": "APPROVED"
  }
}
```

Decisions use `audit_events` as their only immutable history; there is no separate `schedule_decisions` table. `decisionId` is the audit event ID. The audit actor comes from the authenticated security context, and `operatorRationale` stores the trimmed comment. `scheduleHash` is the lowercase SHA-256 hex digest of the stored `resultJson` encoded as UTF-8. Each audit event insert and review-state update commits atomically under an operator-row lock followed by a plan write lock; decisions and automatic invalidation determine current review state. The original optimizer result never changes.

**One terminal decision per run (2026-11-04, usability bug 3).** `APPROVE`, `REJECT`, and `REVISION_REQUESTED` are terminal: once a run's `reviewState` is one of these, every further call to any of the three decision routes for that run - including retrying or repeating the same action - returns **409** with `data.reason = "ALREADY_DECIDED"` and writes no new audit event. Only `PENDING` plans, and plans the system (not an operator) moves to `STALE`/`INVALIDATED`, can still be decided. There is no way to retract a terminal decision on the same run; a new recommendation (a fresh `runId`) must be generated instead.

Errors: **400** for malformed UUIDs/JSON, a missing or non-object body, missing, null, blank, non-string, or oversized comments; **403** for missing/invalid JWTs (existing security behavior); **401** for an authenticated email without a database user; **404** with `Optimization plan not found` for both missing and unowned plans; **409** with `data.reason = "ALREADY_DECIDED"` for a run whose `reviewState` already holds a terminal decision (see above).

Audit history can be read through the existing audit log endpoints.

Approval records intent only and does **not** dispatch or export workloads. Revision does not edit or rerun a plan: a later optimization creates a new immutable version with a new `runId`. Backend dispatch gating and expiry/material-change policies are defined in §6.8; frontend integration is separate work. Export formats are defined in §6.6. Run IDs created before plan persistence was introduced cannot be reviewed; optimize again to obtain a persisted plan.

### 6.6 Approved Schedule Export (GRID-135)

An approved schedule is handed to execution adapters as CSV (`ApprovedScheduleCsvSerializer`) or JSON (`ApprovedScheduleJsonSerializer`). Both take the §6.4 optimise response the operator approved (JSON also takes the stored run record and the approving audit event) and apply the same rules: jobs sorted by `jobId` ascending and slots by `intervalStartUtc` ascending, timestamps in the §2.4 `YYYY-MM-DDTHH:mm:ssZ` format, one slot = 1 hour, and numbers rounded half-up to a fixed number of decimal places independent of server locale. The same schedule therefore always produces byte-identical output. Both reject a blank `runId`, a missing or repeated `jobId`, an allocation without `intervalStartUtc`, or a non-finite number instead of producing a file. The files are served by the export endpoints below, only while the plan's approval stands.

#### Export endpoints and approval gate (GRID-135 AC3)

| Route | `Content-Type` | Download name |
|---|---|---|
| `GET /api/schedules/{runId}/export.csv` | `text/csv;charset=UTF-8` | `schedule-run-{runId}.csv` |
| `GET /api/schedules/{runId}/export.json` | `application/json` | `schedule-run-{runId}.json` |

Both require JWT authentication and ownership of the plan, with the same identity rules as the §6.5 decision routes: administrators do not bypass ownership. A successful response is **200 OK** with the file as the body, `Content-Disposition: attachment; filename="schedule-run-{runId}.csv"` (or `.json`) and `Cache-Control: no-store`. CORS exposes `Content-Disposition` so the browser app can read the filename. Refusals are JSON error envelopes, so clients should accept `application/json` as well as the file type.

**Approval gate.** Checks run in this order and the first failure is returned:

| # | Requirement | On failure |
|---|---|---|
| 1 | The plan exists and the caller owns it | **404** `Optimization plan not found` (missing and unowned plans look the same) |
| 2 | Its review state is not terminal (`INVALIDATED`/`STALE` return **409** with the matching reason), and is `APPROVED` | **403**, `data.reason = "NOT_APPROVED"` (`PENDING`, `REJECTED` or `REVISION_REQUESTED`) |
| 3 | Its latest `APPROVE` / `REJECT` / `REVISE` event in `audit_events` is an `APPROVE` whose `scheduleHash` equals the SHA-256 of the stored `resultJson` | **409**, `data.reason = "INVALIDATED"` |
| 4 | At least one of its slots has not ended: now (UTC) is before the latest `scheduledEnd` | **409**, `data.reason = "STALE"` |
| 5 | No later-approved `APPROVED` plan of the same owner lists any of the same `jobId`s | **409**, `data.reason = "STALE"` |
| 6 | Every scheduled job is still in the owner's jobs (§6.3) and the plan still fits it, by the §6.4 window rules: its CPU and GPU hours equal the job's `allocatedCpuHours` / `allocatedGpuHours`; `scheduledStart` lies in [`originalStartTime` truncated to the hour, that + `flexibilityWindowHours`] (exactly `originalStartTime` when `isExempt`); and `scheduledEnd` is no later than `deadline` truncated to the hour | **409**, `data.reason = "INVALIDATED"` |
| 7 | (GRID-141) Every job in the run is exactly as the optimiser was given it: the run stores a snapshot of its inputs (`optimizer_runs.input_snapshot`, jobs sorted by id, then prices, then capacity) and its SHA-256 (`input_hash`); the shared approval/application/export gate rebuilds the jobs line from the owner's jobs now (id, CPU and GPU hours, `originalStartTime` truncated to the hour, `deadline`, `flexibilityWindowHours`, `isExempt`, `priority`). Name and non-cancelled status are ignored. This solver snapshot is separate from the job/facility provenance in `optimization_plans.input_snapshot`. Runs without a solver snapshot skip this comparison but still require valid plan provenance | **409**, `data.reason = "INVALIDATED"` |

`NO_FEASIBLE_SLOT` jobs do not contribute to elapsed-slot detection, but all input jobs participate in provenance validation (§6.8). Current job and facility scheduling revisions must match the stored input snapshot. Material changes invalidate the plan even when its original slots remain feasible. Cosmetic edits and ordinary execution status changes do not invalidate it. Invalidation and staleness are permanent for a run; withdrawing a superseding approval does not revive earlier plans. Export refusals persist detected invalidation/staleness, without adding operator decision events. The message explains the refusal, for example:

```json
{
  "success": false,
  "message": "Schedule run 7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44 is stale: run 2b1d9f04-6c3e-4a57-9e8f-0d2c4b6a8e13 was approved later and also schedules job(s) [101]",
  "data": { "reason": "STALE" }
}
```

Other errors: **400** for a malformed run ID; **401** for an authenticated email without a database user; **403** for a missing or invalid JWT, with Spring's default error body (`timestamp`, `status`, `error`, `path`). That 403 is told apart from `NOT_APPROVED` by the absent `data.reason`.

#### CSV format (GRID-135 AC1)

**Format.** RFC 4180, UTF-8 without BOM, comma-separated, CRLF after every record including the last. The header row is always present. A field is wrapped in double quotes only when it contains a comma, double quote, CR or LF, and embedded double quotes are doubled. Values are otherwise written verbatim.

**Columns**, in this order:

| Column | Source (§6.4) | Format |
|---|---|---|
| `runId` | `runId` | UUID string, repeated on every row |
| `jobId` | `items[].jobId` | Integer |
| `jobName` | `items[].jobName` | Text; empty if null |
| `perJobStatus` | `items[].perJobStatus` | `OPTIMISED`, `UNCHANGED_EXEMPT` or `NO_FEASIBLE_SLOT` |
| `intervalStartUtc` | `items[].allocations[].intervalStartUtc` | `YYYY-MM-DDTHH:mm:ssZ` (§2.4) |
| `intervalEndUtc` | slot start + 1 hour | `YYYY-MM-DDTHH:mm:ssZ` |
| `cpuHours` | `items[].allocations[].cpuHours` | Exactly 4 decimal places, half-up, `.` separator, no exponent |
| `gpuHours` | `items[].allocations[].gpuHours` | As `cpuHours` |
| `reason` | `items[].reason` | Text; empty unless `NO_FEASIBLE_SLOT` |

**Rows.** One row per job per slot it uses. A `NO_FEASIBLE_SLOT` job has no slots, so it gets one row with `intervalStartUtc`, `intervalEndUtc`, `cpuHours` and `gpuHours` empty and the `reason` filled in; adapters must not dispatch rows without a slot. A schedule with no items is the header row alone.

**Rounding.** Hours are rounded to 4 places per slot, so a job's rows may differ from `allocatedCpuHours` / `allocatedGpuHours` by up to 0.00005 per slot.

Example (a two-slot job, a job with no feasible slot, and an exempt job):
```csv
runId,jobId,jobName,perJobStatus,intervalStartUtc,intervalEndUtc,cpuHours,gpuHours,reason
7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44,101,LLM Fine-Tuning Run #42,OPTIMISED,2026-10-21T01:00:00Z,2026-10-21T02:00:00Z,21.3333,5.3333,
7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44,101,LLM Fine-Tuning Run #42,OPTIMISED,2026-10-21T02:00:00Z,2026-10-21T03:00:00Z,42.6667,10.6667,
7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44,150,Render batch,NO_FEASIBLE_SLOT,,,,,cluster full between 10:00 and 18:00
7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44,205,Nightly ETL,UNCHANGED_EXEMPT,2026-10-20T10:00:00Z,2026-10-20T11:00:00Z,8.0000,0.0000,
```

#### JSON format (GRID-135 AC2)

**Format.** UTF-8, compact (no whitespace between tokens, no trailing newline). Keys always appear in the order listed below, and every key is always present: a missing value is `null`, never an omitted key. Numbers are JSON numbers written in plain notation with a fixed number of decimal places, so `8.0000` keeps its trailing zeros. `formatVersion` is incremented if the shape changes incompatibly.

**Document.**

| Key | Type | Notes |
|---|---|---|
| `formatVersion` | integer | `1` |
| `run` | object | Run metadata, below |
| `approval` | object \| null | The decision that approved the schedule, below |
| `jobs` | array | Every job in the run exactly once, sorted by `jobId`; `[]` when the run has no jobs |

**`run`**: every run-level field of the §6.4 response, plus the stored `OptimizerRun` record (§6.5).

| Key | Type | Notes |
|---|---|---|
| `runId` | string | UUID |
| `status` | string | `SCHEDULED`, `NEEDS_ATTENTION` or `INFEASIBLE` |
| `provenOptimal` | boolean | |
| `usedSeededFallbackPrices` | boolean | `true` when the history-based fallback price curve was used |
| `baselineCostUsd` | number | 4 decimal places |
| `optimizedCostUsd` | number | 4 decimal places |
| `projectedSavingsUsd` | number | 4 decimal places; may be negative |
| `projectedSavingsPercent` | number | 2 decimal places; may be negative |
| `plainEnglishExplanation` | string \| null | |
| `createdAt` | string \| null | When the run was stored; UTC timestamp, whole seconds |
| `solver` | object \| null | `name` (string), `version` (string) and `config`: the `gacs.optimiser.*` settings stored with the run, as a JSON object (or the stored text, if it is not valid JSON) |

**`approval`**: the latest decision on the run, which the gate has checked is an `APPROVE` of exactly this schedule.

| Key | Type | Notes |
|---|---|---|
| `decisionId` | integer | Audit event ID |
| `approvedBy` | string | The approving operator's email |
| `approvedAt` | string | UTC timestamp, whole seconds |
| `comment` | string | The operator's rationale |
| `scheduleHash` | string | SHA-256 of the stored schedule recorded with the approval |

The export endpoints always fill `run.createdAt`, `run.solver` and `approval`; they are `null` only when the serializer is used without a run record or approval.

**`jobs[]`**: every field of a §6.4 `items[]` entry, with `allocations` replaced by `slots`.

| Key | Type | Notes |
|---|---|---|
| `jobId` | integer | |
| `jobName` | string \| null | |
| `perJobStatus` | string | `OPTIMISED`, `UNCHANGED_EXEMPT` or `NO_FEASIBLE_SLOT` |
| `reason` | string \| null | Set only for `NO_FEASIBLE_SLOT` |
| `baselineStart` | string | UTC timestamp |
| `scheduledStart` | string \| null | Start of the first slot; `null` for `NO_FEASIBLE_SLOT` |
| `scheduledEnd` | string \| null | End of the last slot; `null` for `NO_FEASIBLE_SLOT` |
| `allocatedCpuHours` | number | 4 decimal places; `0.0000` for `NO_FEASIBLE_SLOT` |
| `allocatedGpuHours` | number | 4 decimal places; `0.0000` for `NO_FEASIBLE_SLOT` |
| `baselineCostUsd` | number \| null | 4 decimal places; `null` for `NO_FEASIBLE_SLOT` |
| `optimizedCostUsd` | number \| null | 4 decimal places; `null` for `NO_FEASIBLE_SLOT` |
| `savingsUsd` | number \| null | 4 decimal places; may be negative; `null` for `NO_FEASIBLE_SLOT` |
| `slots` | array | Approved slots sorted by start; `[]` for `NO_FEASIBLE_SLOT` |

**`jobs[].slots[]`**

| Key | Type | Notes |
|---|---|---|
| `intervalStartUtc` | string | UTC timestamp |
| `intervalEndUtc` | string | Slot start + 1 hour |
| `cpuHours` | number | 4 decimal places |
| `gpuHours` | number | 4 decimal places |

Example (the schedule from the CSV example; shown indented here for reading, but the actual output is a single line):
```json
{
  "formatVersion": 1,
  "run": {
    "runId": "7f3c1e52-9a0b-4d6e-8c21-5b1f0a9e3d44",
    "status": "NEEDS_ATTENTION",
    "provenOptimal": true,
    "usedSeededFallbackPrices": false,
    "baselineCostUsd": 1.5752,
    "optimizedCostUsd": 0.7304,
    "projectedSavingsUsd": 0.8448,
    "projectedSavingsPercent": 53.63,
    "plainEnglishExplanation": "Scheduled 2 of 3 job(s).",
    "createdAt": "2026-10-20T09:00:00Z",
    "solver": {
      "name": "HybridAllocationSolver",
      "version": "1",
      "config": { "cpuKwhPerHour": 0.05, "gpuKwhPerHour": 0.4 }
    }
  },
  "approval": {
    "decisionId": 42,
    "approvedBy": "operator@gacs.internal",
    "approvedAt": "2026-10-20T09:30:15Z",
    "comment": "Approved after verifying cooling margin.",
    "scheduleHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
  },
  "jobs": [
    {
      "jobId": 101, "jobName": "LLM Fine-Tuning Run #42", "perJobStatus": "OPTIMISED", "reason": null,
      "baselineStart": "2026-10-20T10:00:00Z", "scheduledStart": "2026-10-21T01:00:00Z", "scheduledEnd": "2026-10-21T03:00:00Z",
      "allocatedCpuHours": 64.0000, "allocatedGpuHours": 16.0000,
      "baselineCostUsd": 1.0752, "optimizedCostUsd": 0.2304, "savingsUsd": 0.8448,
      "slots": [
        { "intervalStartUtc": "2026-10-21T01:00:00Z", "intervalEndUtc": "2026-10-21T02:00:00Z", "cpuHours": 21.3333, "gpuHours": 5.3333 },
        { "intervalStartUtc": "2026-10-21T02:00:00Z", "intervalEndUtc": "2026-10-21T03:00:00Z", "cpuHours": 42.6667, "gpuHours": 10.6667 }
      ]
    },
    {
      "jobId": 150, "jobName": "Render batch", "perJobStatus": "NO_FEASIBLE_SLOT", "reason": "cluster full between 10:00 and 18:00",
      "baselineStart": "2026-10-20T12:00:00Z", "scheduledStart": null, "scheduledEnd": null,
      "allocatedCpuHours": 0.0000, "allocatedGpuHours": 0.0000,
      "baselineCostUsd": null, "optimizedCostUsd": null, "savingsUsd": null,
      "slots": []
    },
    {
      "jobId": 205, "jobName": "Nightly ETL", "perJobStatus": "UNCHANGED_EXEMPT", "reason": null,
      "baselineStart": "2026-10-20T10:00:00Z", "scheduledStart": "2026-10-20T10:00:00Z", "scheduledEnd": "2026-10-20T11:00:00Z",
      "allocatedCpuHours": 8.0000, "allocatedGpuHours": 0.0000,
      "baselineCostUsd": 0.5000, "optimizedCostUsd": 0.5000, "savingsUsd": 0.0000,
      "slots": [
        { "intervalStartUtc": "2026-10-20T10:00:00Z", "intervalEndUtc": "2026-10-20T11:00:00Z", "cpuHours": 8.0000, "gpuHours": 0.0000 }
      ]
    }
  ]
}
```

### 6.7 Audit Log Queries (GRID-134 AC6)

Both routes require `ROLE_USER` or `ROLE_ADMIN`; every such user can read every audit event.

`POST /api/audit-events` remains a compatibility entry point for decisions. `APPROVE`, `REJECT`, and
`REVISE` use the same owner-scoped transaction and validity checks as the schedule decision endpoints.
The run ID must be a UUID; missing or unowned plans return 404. The recorded hash is computed from
the stored schedule, rather than trusting the supplied `scheduleHash`. Prefer the schedule decision
endpoints for new clients.

#### `GET /api/audit-events`
Returns audit events newest first, a page at a time, in the standard envelope (`data.content`, `data.totalElements`, `data.totalPages`, `data.number`, `data.size`). Every filter is optional, and filters combine with AND.

| Parameter | Matches |
|---|---|
| `from` | Events at or after this ISO-8601 UTC time |
| `to` | Events before this ISO-8601 UTC time (exclusive) |
| `actor` | Operator email, case-insensitive substring |
| `actionType` | `APPROVE`, `REJECT`, `REVISE`, `OVERRIDE` or `DISPATCH` |
| `runId` | Run ID, case-insensitive substring |
| `jobId` | Events on runs whose stored plan (§6.5) lists this job |
| `page`, `size` | Zero-based page and page size (default 20) |

Each plan's job IDs are kept in `optimization_plan_jobs`, written when the plan is created. Plans stored before that table existed are filled in at application startup. **400** for a malformed value or `from` not before `to`.

#### `GET /api/audit-events/{id}`
Returns `{ event, run, scheduleAvailability, scheduleHashMatches, schedule }`:

| Field | Meaning |
|---|---|
| `event` | The audit event |
| `run` | The stored `OptimizerRun` for the event's `runId` (solver, version, configuration, status, optimality); `null` if none |
| `scheduleAvailability` | `VISIBLE` when the caller owns the run's plan; `OWNER_ONLY` when a plan is stored but belongs to someone else; `NOT_STORED` when there is none |
| `scheduleHashMatches` | Whether the stored plan still hashes to the event's `scheduleHash`; `null` when `NOT_STORED` |
| `schedule` | The stored §6.4 optimise result, only when `VISIBLE` |

As with decisions and exports, administrators do not bypass ownership of the stored schedule. **404** when the event does not exist.

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
3. **Authenticated File Downloads**:
   `scheduleExportApi.download(runId, "csv" | "json")` fetches a §6.6 export with the bearer token and returns the file with its `Content-Disposition` name. It refreshes the token once on 401, or on a 403 without `data.reason`. A gate refusal (`NOT_APPROVED`, `STALE`, `INVALIDATED`) is not retried and is raised as a `ScheduleExportError` carrying the server's message and reason.
4. **Route Protection & Next.js Middleware**:
   Protected routes (`/admin`, `/dashboard`) check the lightweight session synchronization cookie (`gacs_logged_in=true`) in Next.js `middleware.ts`. If unauthenticated, the user is redirected to `/login`.

---

## 8. Versioning, Deprecation & Contract Evolution

1. **Semantic Versioning**: All API contracts follow `MAJOR.MINOR.PATCH` versioning rules.
   * `MAJOR`: Breaking schema modifications, deleted endpoints, or changed authentication semantics.
   * `MINOR`: Non-breaking additions (new endpoints, optional request properties, additional response fields).
   * `PATCH`: Bug fixes, documentation clarifications, and internal optimizations.
2. **Deprecation Notice**: Any endpoint scheduled for deprecation will return the `Deprecation: true` and `Sunset: <date>` HTTP headers at least one sprint prior to removal.
3. **Backward Compatibility Guarantee**: All public auth endpoints support legacy aliases (`/login`, `/register`, `/verify-otp`, `/resend-otp`, `/refresh`, `/me`, `/logout`) alongside standard `/api/auth/*` namespaces.


### 6.8 Approval lifecycle and gated application (GRID-11 AC2.3)

**Backend enforcement.** Frontend integration is a separate task. Until it adopts this contract, existing direct calendar-create calls will return **403** `NOT_APPROVED`.

#### Material inputs and lifecycle

New plans retain an immutable input snapshot containing the submitted optimization request, owned job scheduling revisions and values, and the facility scheduling revision and values. Snapshot capture uses a short transaction before solving; solver and explanation calls hold no operator lock or database transaction. A submitted request may still be optimized as a preview, but approval is refused if its jobs are missing/unowned or scheduling inputs do not match persisted jobs, its capacity differs from the facility (including omitted solver defaults), its per-slot override exceeds facility capacity, or its forecast location differs from the facility when no explicit forecast was supplied. For an unconfigured facility, defaults are 256 CPU-hours, 64 GPU-hours, and `LZ_NORTH`.

Job CPU/GPU hours, original start, deadline, flexibility, exemption, priority, cancellation/reinstatement, raw constraint source, and confirmation outcome are material. A timestamp-only parser edit is not material unless it changes confirmation. Facility CPU/GPU capacity and grid location are material. Every actual material change increments a monotonic scheduling revision, including changes subsequently reverted. Job updates/deletions invalidate that owner's approved plans containing the job. Facility changes invalidate all of that owner's approved plans. Input updates and invalidation commit atomically. New unrelated jobs, names, identical updates, and ordinary job execution statuses do not invalidate plans.

Before approval, the backend checks current ownership, revisions, input values, cancellation and confirmation (`PENDING`/`REJECTED` structured constraints cannot be approved). Missing, unreadable, or incomplete provenance fails closed. Changes during solving or while a recommendation awaits review therefore prevent approval. No stale/invalidated run can receive another decision; generate a new recommendation instead. Immutable schedule JSON and prior audit records remain intact.

A successful approval permanently transitions other approved plans containing overlapping jobs to `STALE`. The order is approval order, not optimization creation time. A plan also becomes stale when all scheduled slots have ended. A scheduled check runs every 60 seconds (`gacs.schedule.expiry-check-ms`); synchronous gates enforce expiry immediately without waiting for that check. Forecast refreshes and solver configuration changes are outside this policy.

#### Application and plan detail

| Route | Success | Behavior |
|---|---|---|
| `POST /api/schedules/{runId}/apply` | **200**, `data: WorkloadScheduleDto[]` | Creates calendar entries atomically from the stored approved schedule; no request payload is required or used. |
| `GET /api/schedules/{runId}` | **200**, `data: PlanDetail` | Returns the immutable recommendation and current lifecycle state; detects and persists outdated inputs/expiry. |

Both routes require JWT authentication and ownership. Missing/unowned plans return **404** `Optimization plan not found`; an authenticated email without a database user returns **401**. Administrator privileges do not bypass ownership.

`PlanDetail` contains `runId`, `reviewState` (`PENDING`, `APPROVED`, `REJECTED`, `REVISION_REQUESTED`, `INVALIDATED`, `STALE`), `invalidationReason` (string/null), `invalidatedAt` (UTC instant/null), and `recommendation` (the §6.4 optimizer response; null only if the stored result is unreadable). Detail requests succeed for unapproved/terminal plans so clients can explain the state.

Application accepts only a run ID: allocations, job identities, and timings come from the stored result. Jobs without executable slots are skipped; a run with no executable entries returns **400**. Entries retain existing calendar display fields and add nullable `runId` and `jobId` fields. `(run_id, job_id)` is unique; application is idempotent, with a persisted application timestamp preventing recreation even after entries are deleted. A successful first application appends one `DISPATCH` audit event atomically with all entries. Retries add no events. The run remains `APPROVED` and can still be invalidated or rejected afterward.

`POST /api/workload-schedules` always refuses direct creation with **403** `data.reason = "NOT_APPROVED"`, directing clients to `/api/schedules/{runId}/apply`. Frontend callers on Jobs, Workloads, and the manual calendar must migrate to optimize → review/approve → apply.

#### Execution boundaries and errors

Exports, application, and `PATCH /api/workload-schedules/{id}/status` transitions into `Scheduled` or `Running` share the same gate: current approval, matching latest decision and schedule hash, unexpired/non-superseded schedule, and matching input provenance. A resume from `Throttled` to `Running` rechecks approval. Operator input changes, decisions, application, and execution transitions acquire the same operator-row write lock, followed by plan locks where needed, preventing concurrent edits from bypassing the gate. Invalidation detected during a refusal commits even though no entries or decision events are created.

| Condition | HTTP / `data.reason` |
|---|---|
| Pending, rejected, revision requested, or unlinked legacy calendar entry | **403** / `NOT_APPROVED` |
| Material inputs changed, approval hash mismatched, missing provenance, cancelled/unconfirmed job | **409** / `INVALIDATED` |
| Expired slots or superseded approval | **409** / `STALE` |

Already running work is not interrupted by invalidation; completion remains permitted. Legacy calendar entries remain readable/deletable but cannot start or resume without a freshly generated and applied approved run. This backend does not control external execution systems or revoke files previously exported; external adapters must check current approval immediately before starting work.

**Rollout.** Apply `V5__schedule_approval_lifecycle.sql` explicitly before deployment for managed PostgreSQL schemas; no migration runner is configured. It adds revisions, provenance/lifecycle/application fields, nullable entry linkage, and uniqueness, expands the review-state check, and invalidates existing plans missing provenance. Hibernate `ddl-auto=update` adds model columns in development; old approved plans also fail closed at the next gate or expiry check. Historical decisions and legacy calendar entries are preserved. Do not infer provenance for old schedules.
