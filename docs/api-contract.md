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
* `confidenceScore` (0-1, higher = more confident) is reserved for GRID-68 and is omitted from each point until the model provides it.
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

#### `POST /api/schedules/optimize`
Triggers an optimization run evaluating all eligible workloads against grid price forecasts and resource constraints.

* **Optimizer Input Contract**:
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
    "clusterCapacity": {
      "maxConcurrentCpuHours": 256.0,
      "maxConcurrentGpuHours": 64.0
    },
    "forecastLocation": "LZ_NORTH",
    "horizonHours": 48
  }
  ```

* **Cost Basis & Energy Conversion (GRID-124 AC2)**:
  - **CPU Conversion Factor**: $1 \text{ CPU-hour} = 0.05 \text{ kWh}$
  - **GPU Conversion Factor**: $1 \text{ GPU-hour} = 0.40 \text{ kWh}$ (standard 400W enterprise GPU baseline)
  - **Total Energy**: $\text{Energy}_{\text{job}} (\text{kWh}) = (\text{cpuHours} \times 0.05) + (\text{gpuHours} \times 0.40)$
  - **Slot Pricing**: Forecast price $P_t$ is given in $\$ / \text{MWh}$ ($\$0.001 / \text{kWh}$).
  - **Job Cost**: $\text{Cost}(job, t) = \text{Energy}_{\text{job}} \times \frac{P_t}{1000}$

* **Capacity Input & Default Seeds (GRID-124 AC3)**:
  - `maxConcurrentCpuHours`: Default $256.0 \text{ core-hours / slot}$
  - `maxConcurrentGpuHours`: Default $64.0 \text{ GPU-hours / slot}$

* **Optimizer Output Contract (GRID-124 AC4, AC5)**:
  ```json
  {
    "success": true,
    "message": "Optimized schedule generated",
    "data": {
      "runId": "run-a1b2c3d4-e5f6-7890-abcd-1234567890ab",
      "status": "OPTIMAL",
      "baselineCostUsd": 4120.00,
      "optimizedCostUsd": 3140.25,
      "projectedSavingsUsd": 979.75,
      "projectedSavingsPercent": 23.78,
      "plainEnglishExplanation": "By shifting GPU cluster training from the 14:00 peak ($112/MWh) to 01:00 off-peak ($24/MWh) wind-rich hours, projected energy costs are reduced by 23.8% while completing all jobs prior to deadline.",
      "usedSeededFallbackPrices": false,
      "usedHeuristicFallback": false,
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
          "baselineCostUsd": 4.12,
          "optimizedCostUsd": 3.14,
          "savingsUsd": 0.98
        }
      ]
    }
  }
  ```

* **Per-Job Status Values**:
  - `RESCHEDULED`: Shifted into a lower-cost slot within its flexibility window.
  - `UNCHANGED_ALREADY_OPTIMAL`: Kept at `originalStartTime` because that slot was already the cheapest feasible one available to the job. Distinguished from `RESCHEDULED` so a plan that moves nothing is not reported as though it had moved every job; a job with `flexibilityWindowHours: 0` always lands here or in `UNCHANGED_NO_FEASIBLE_SLOT`.
  - `UNCHANGED_EXEMPT`: Kept at `originalStartTime` because `isExempt` is `true`.
  - `UNCHANGED_NO_FEASIBLE_SLOT`: Kept at `originalStartTime` because no feasible slot meets deadline or capacity constraints (GRID-124 AC5: the endpoint returns this status gracefully without failing or returning an invalid schedule).

  Under the real solver (GRID-125, point 10 below), a single item's `savingsUsd` can be
  negative - the aggregate guarantee is that the run's *total* `optimizedCostUsd` never
  exceeds `baselineCostUsd`, not that every individual job's cost is non-increasing.

---

#### Optimizer Semantics & Resolved Ambiguities (GRID-124)

The contract above fixes the wire shape. The following points were undefined by it and are
settled here so the solver, the test suites (GRID-123, GRID-124) and the eventual real
solver (GRID-125) agree. Items marked **team decision** were agreed before implementation.

1. **Slot granularity — team decision.** A job occupies exactly **one one-hour slot**.
   The `Job` schema carries no duration field, and the worked example above runs a
   64-CPU-hour job from `01:00` to `02:00`. `scheduledEnd` is therefore always
   `scheduledStart + 1h`.
2. **Capacity dimension.** Because a job fills a single one-hour slot, its `cpuHours` and
   `gpuHours` are also its load *within* that slot. `maxConcurrentCpuHours` and
   `maxConcurrentGpuHours` are compared against the sum of the hours of all jobs placed in
   the same slot. A job whose own `cpuHours` exceeds the ceiling can never be placed and
   returns `UNCHANGED_NO_FEASIBLE_SLOT`.
3. **Deadline semantics.** A job finishing *exactly* on its deadline is feasible; finishing
   after it is not. Formally, a slot is deadline-feasible when
   `scheduledStart + 1h <= deadline`.
4. **Exemption outranks the deadline — team decision.** An exempt job is pinned to
   `originalStartTime` even when that slot already breaches its own deadline or the capacity
   ceiling, and it reserves that capacity ahead of all flexible work. Consequently the
   invariant "no job finishes after its deadline" holds for **non-exempt, placed** jobs:
   a job returned as `UNCHANGED_EXEMPT` or `UNCHANGED_NO_FEASIBLE_SLOT` sits at its
   original time by definition and may be late if the caller's own input was already late.
5. **Job source.** Omitting the `jobs` field entirely optimizes the authenticated
   operator's own stored jobs, excluding those with status `COMPLETED` or `CANCELLED`;
   this is the flow the end-to-end acceptance test uses. Supplying `jobs` optimizes exactly
   that array. An explicitly empty `jobs: []`, or an omitted `jobs` field when the operator
   has no eligible stored jobs, is a `400`.
6. **Timestamp format.** Timestamps are UTC ISO local date-times. Responses always omit the
   offset suffix (`2026-10-20T10:00:00`), consistent with every other endpoint in this
   service. Requests accept **either** form: a trailing `Z` is parsed and, since these
   values are UTC by definition, resolves to the same instant (`2026-10-20T12:00:00Z` is
   read as `12:00`). A non-zero offset such as `+08:00` is **not** supported and must be
   converted to UTC by the caller.
7. **Monetary precision.** Every dollar figure is rounded to cent precision, half-up, and
   the summary totals are derived from the already-rounded per-job figures, so
   `baselineCostUsd`, `optimizedCostUsd` and `projectedSavingsUsd` always reconcile with the
   `items` array. Note that with the conversion factors above a 64-CPU-hour job draws only
   3.2 kWh, so realistic job sizes produce sub-dollar costs where cent rounding is coarse.
8. **Clock.** The optimization horizon starts at the current UTC hour, truncated. The
   service takes an injected `java.time.Clock`, so tests pin time rather than depending on
   the wall clock.
9. **`status` and `usedHeuristicFallback` (GRID-125).** The real solver is a hybrid of an
   exact MILP solver (ojAlgo) and a deterministic simulated-annealing fallback. `status` is
   `OPTIMAL` when the exact solver certified a proven global minimum within its time budget,
   and `FEASIBLE` when it did not - either because the simulated-annealing fallback produced
   the plan instead, or (rarely) because the exact solver was attempted but only returned a
   time-boxed, unproven incumbent. `FEASIBLE` still means every AC2/AC3/AC6 constraint holds
   (flexibility windows, deadlines, capacity); it only means optimality is not proven. The
   companion boolean `usedHeuristicFallback` is `true` exactly when `status` is `FEASIBLE`
   and `false` whenever it is `OPTIMAL` - a convenience flag so a caller doesn't have to
   string-compare `status` to know which path produced the plan.

   | Field | Type | Description |
   |---|---|---|
   | `usedHeuristicFallback` | Boolean | `true` when the simulated-annealing fallback (or an unproven time-boxed incumbent) produced this plan instead of a certified-optimal one. |

10. **The real solver's minimum is a true *aggregate* minimum, not a per-job ceiling (GRID-125).**
   The stub's own mechanism - never moving a job into an hour priced above the hour it already
   occupies - was a heuristic's self-protection, not a property of the underlying optimization
   problem, and the real solver does not carry it forward. It is mathematically possible for
   the true minimum-cost schedule to raise one job's own cost above its baseline if doing so
   frees capacity that lets a different job drop into a much cheaper slot, with the net total
   strictly improving; `savingsUsd` on an individual item can therefore legitimately be
   negative under the real solver, even though it never was under the stub. What still holds,
   provably, is the *aggregate* invariant: `optimizedCostUsd <= baselineCostUsd` always holds,
   because "every flexible job stays at its own baseline cost" is always one feasible point in
   the solver's search space (a job that cannot find a jointly-feasible real slot is reported
   `UNCHANGED_NO_FEASIBLE_SLOT` at exactly its baseline cost, never worse), and the solver finds
   the true minimum over that space. A *strict* reduction in the aggregate still requires that
   at least one job had a cheaper feasible slot to move into.
11. **Capacity can be exceeded by exempt work alone.** Because exempt jobs are pinned
   before anything flexible is placed (point 4) and are never refused, a slot whose exempt
   jobs alone exceed a ceiling is reported as-is. Flexible jobs are always checked against
   remaining capacity, so they can never push a slot over a ceiling. Consequently the
   invariant "no slot exceeds cluster capacity" holds for every slot containing no pinned
   exempt work, and holds universally only for inputs whose exempt jobs fit on their own.
   In the real solver (GRID-125) this is enforced by clamping each slot's capacity
   expression to `max(0, ceiling - exemptReservedAtSlot)` rather than the stub's running
   `used[] <= ceiling` check - the clamp matters because a *negative* bound (exempt demand
   already over the ceiling) would otherwise make even the all-zero placement infeasible,
   breaking the whole optimization run instead of just that one slot.

* **Price Source & Seeded Fallback (GRID-124 AC5.2)**:
  Prices come from the latest stored model forecast for `forecastLocation` over the horizon.
  When **no** forecast row covers the window, the run falls back to a seeded diurnal curve
  rather than failing, and sets `usedSeededFallbackPrices: true` on the response. Partial
  coverage is not treated as absence: hours with no forecast price are simply not candidate
  slots. The baseline cost and the optimized cost are always computed from the *same* price
  series, seeded or not (AC5.3), which is what makes `projectedSavingsUsd` meaningful.

  | Field | Type | Description |
  |---|---|---|
  | `usedSeededFallbackPrices` | Boolean | `true` when the seeded fallback curve was used for this run instead of stored forecasts. |
  | `usedHeuristicFallback` | Boolean | `true` when the simulated-annealing fallback (or an unproven time-boxed incumbent) produced this plan instead of a certified-optimal one (point 9 above). Independent of `usedSeededFallbackPrices` - either, both, or neither can be true on the same run. |

* **Error Responses (GRID-124 AC5.4)**:
  Invalid input returns `400` in the standard envelope — `{"success": false, "message": "<field-specific reason>", "data": null}` — and **never** a partial or placeholder schedule.
  Inline job problems name the offending element, e.g. `jobs[2].deadline must be greater than or equal to originalStartTime`. The validated conditions are:

  | Condition | Message names |
  |---|---|
  | `jobs: []` explicitly empty | `jobs` |
  | Omitted `jobs` with no eligible stored jobs | operator job set |
  | `name` blank | `jobs[i].name` |
  | `cpuHours` or `gpuHours` negative or non-finite | that field |
  | Both `cpuHours` and `gpuHours` zero | `jobs[i]` |
  | `originalStartTime` or `deadline` absent | that field |
  | `deadline` earlier than `originalStartTime` | `jobs[i].deadline` |
  | `flexibilityWindowHours` negative | `jobs[i].flexibilityWindowHours` |
  | `horizonHours` outside 1–168 | `horizonHours` |
  | `forecastLocation` blank or over 100 characters | `forecastLocation` |
  | `clusterCapacity` value non-positive or non-finite | that field |
  | A job's baseline slot falls outside the horizon, or has no forecast price | that job's identity |

  That last condition exists because a job whose original slot cannot be priced has no
  baseline to compare against; reporting savings for it would mean inventing a number.


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
