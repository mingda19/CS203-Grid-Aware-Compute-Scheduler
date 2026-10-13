# GACS Deployment Runbook & Rollback Procedures

**Document Version:** 1.0.0  
**Target Environment:** Production / Staging  
**Acceptance Criteria Reference:** GRID-117 AC4 / AC4.1  
> **AC4.1** — A deployment runbook/rollback procedure is documented, and the full pipeline (PR → tests → merge → auto-deploy) is verified end-to-end.

---

## 1. System Architecture & Deployment Topology

The **Grid-Aware Compute Scheduler (GACS)** system consists of three primary infrastructure layers:

```mermaid
flowchart TD
    subgraph Clients
        Browser[Client Browser]
    end

    subgraph "Frontend Layer (Vercel)"
        VercelEdge[Vercel Global Edge CDN]
        NextApp[Next.js 16 Web Application]
    end

    subgraph "Backend Layer (Render)"
        RenderLB[Render Load Balancer / HTTPS]
        SpringApp[Spring Boot 4.1.1 Application (Java 25 Docker)]
    end

    subgraph "Data Layer"
        Postgres[(Managed PostgreSQL Database)]
    end

    subgraph "Automation / CI/CD"
        GHA[GitHub Actions]
    end

    Browser -->|HTTPS| VercelEdge
    VercelEdge --> NextApp
    NextApp -->|REST API with JWT| RenderLB
    RenderLB --> SpringApp
    SpringApp -->|JDBC / HikariCP| Postgres
    GHA -->|Deploy Hook POST| SpringApp
    GHA -->|Vercel CLI Deploy| NextApp
```

### Infrastructure Summary
| Component | Hosting Provider | Runtime / Platform | Production URL / Domain | Purpose |
| :--- | :--- | :--- | :--- | :--- |
| **Backend API** | Render | Docker (Temurin JDK 25) | `https://gacs-backend.onrender.com` | Core business logic, scheduling engine, auth, JWT issuance, price data |
| **Frontend UI** | Vercel | Node.js 20 / Next.js 16 | `https://gacs-frontend.vercel.app` | Responsive operator dashboard, calendar view, forecast visualizations |
| **Database** | Managed PostgreSQL (Render / Neon / Supabase) | PostgreSQL 16+ | Direct connection string via `DATABASE_URL` | User credentials, compute tasks, grid price history, forecast predictions |
| **CI / CD Automation** | GitHub Actions | Ubuntu Latest Runner | `.github/workflows/ci.yml`<br>`.github/workflows/cd.yml` | Automated unit/integration tests, staging enforcement, build verification, auto-deploy |

---

## 2. Environment Variables & Secret Management

All environment variables and secrets must be configured in their respective platforms before triggering deployments.

### 2.1 Backend Environment Variables (Render)
Configure in **Render Dashboard $\rightarrow$ Web Service $\rightarrow$ Environment**:

| Variable Name | Required | Default / Example Value | Description |
| :--- | :---: | :--- | :--- |
| `PORT` | Yes | `10000` | HTTP port exposed by Spring Boot container in Render |
| `DATABASE_URL` | Yes | `postgres://user:pass@host:5432/dbname` | PostgreSQL connection URL (auto-parsed by `DotenvLoader`) |
| `JWT_SECRET_KEY` | Yes | `[256-bit base64 string]` | Cryptographic secret for signing and verifying HMAC-SHA256 JWT tokens |
| `JWT_ACCESS_EXPIRATION_MS` | Yes | `900000` (15 mins) | Expiration time for access tokens |
| `JWT_REFRESH_EXPIRATION_MS` | Yes | `86400000` (24 hours) | Expiration time for standard refresh tokens |
| `JWT_REMEMBER_ME_EXPIRATION_MS` | Yes | `1209600000` (14 days) | Expiration time for extended remember-me refresh tokens |
| `COOKIE_SECURE` | Yes | `true` | Enforces `Secure` flag on JWT HTTP-only cookies in HTTPS |
| `CORS_ALLOWED_ORIGINS` | Yes | `https://gacs-frontend.vercel.app` | Comma-delimited list of allowed frontend origins for CORS |

### 2.2 Frontend Environment Variables (Vercel)
Configure in **Vercel Project $\rightarrow$ Settings $\rightarrow$ Environment Variables**:

| Variable Name | Environment | Example Value | Description |
| :--- | :---: | :--- | :--- |
| `NEXT_PUBLIC_API_URL` | Production & Preview | `https://gacs-backend.onrender.com` | Base URL used by browser fetch client to communicate with backend API |

### 2.3 GitHub Actions Repository Secrets
Configure in **GitHub Repository $\rightarrow$ Settings $\rightarrow$ Secrets and variables $\rightarrow$ Actions**:

| Secret Name | Required | Source | Description |
| :--- | :---: | :--- | :--- |
| `RENDER_DEPLOY_HOOK_URL` | Yes | Render Service $\rightarrow$ Settings $\rightarrow$ Deploy Hook | Webhook URL triggered via POST to trigger instant zero-downtime Render deployment |
| `VERCEL_TOKEN` | Optional* | Vercel Account $\rightarrow$ Settings $\rightarrow$ Tokens | Personal access token used by Vercel CLI in CI/CD |
| `VERCEL_ORG_ID` | Optional* | Vercel Team / Account $\rightarrow$ Settings | Vercel Organization ID |
| `VERCEL_PROJECT_ID` | Optional* | Vercel Project $\rightarrow$ Settings $\rightarrow$ General | Vercel Project ID |

*\* Note: If Vercel is connected directly to the GitHub repository via Vercel GitHub App, Vercel deploys commits on `main` automatically upon merge; the CI/CD secrets provide programmatic fallback and deterministic pipeline gating.*

---

## 3. End-to-End CI/CD Pipeline Workflow

The GACS delivery pipeline enforces strict quality gates before code reaches production:

```mermaid
sequenceDiagram
    autonumber
    actor Dev as Developer
    participant Git as GitHub Repo
    participant CI as GitHub Actions (CI)
    participant CD as GitHub Actions (CD)
    participant Render as Render (Backend)
    participant Vercel as Vercel (Frontend)

    Dev->>Git: Push feature branch & open PR into staging
    Dev->>Git: Open PR from staging into main
    Git->>CI: Trigger ci.yml & enforce-staging-source.yml
    critical Quality Assurance Gates
        CI->>CI: Job 1: Backend tests (Maven verify / JDK 25 / H2)
        CI->>CI: Job 2: Frontend tests (Vitest / Node 24)
        CI->>CI: Job 3: Data script tests (Pytest / Python 3.13)
        CI->>CI: Branch check: Verify source branch is staging
    end
    Note over Dev,Git: Peer Review Approval Required
    Dev->>Git: Approve & Merge PR into main
    Git->>CD: Trigger cd.yml on push to main
    critical Continuous Deployment
        CD->>CD: Job 1: Build & Verify Backend (Maven clean package)
        CD->>CD: Job 2: Build & Verify Frontend (npm run build)
        CD->>Render: Job 3: Trigger Render Deploy Hook (POST)
        CD->>Vercel: Job 3: Deploy Frontend to Vercel (--prod)
    end
    Render-->>CD: HTTP 200/201 Deploy Triggered
    Vercel-->>CD: Release Deployed to Production Domain
```

### Stage 1: Pull Request Verification (CI)
- **Trigger**: Opening, updating, or syncing a Pull Request targeting `main`.
- **Workflow**: `.github/workflows/ci.yml` & `.github/workflows/enforce-staging-source.yml`.
- **Enforced Rules**:
  1. Source branch must strictly be `staging` (direct PRs from feature branches to `main` fail immediately).
  2. Spring Boot tests run against in-memory H2 database (`./mvnw -B verify`).
  3. Frontend unit tests run (`npm test`).
  4. Python data script tests run (`pytest -v`).
  5. Peer review approval is required before merge permissions are granted.

### Stage 2: Merge & Build Verification (CD)
- **Trigger**: Merge of approved Pull Request into `main`.
- **Workflow**: `.github/workflows/cd.yml`.
- **Steps**:
  1. Compiles and packages backend artifact (`./mvnw clean package`).
  2. Compiles and bundles frontend Next.js production build (`npm run build`).
  3. Both builds must succeed before deployment triggers fire.

### Stage 3: Automated Zero-Manual-Step Deployment
- **Backend**: Executes an authenticated POST request to Render Deploy Hook. Render pulls the latest commit, builds the Docker container, executes health checks, and performs zero-downtime traffic switching.
- **Frontend**: Executes `vercel --prod` to deploy to Vercel production edge network.
- **Audit Summary**: Generates a GitHub Actions Step Summary recording commit SHA, deploy status, and deployment verification.

---

## 4. End-to-End Pipeline Verification Record

The pipeline verification was conducted on branch `CD-testing` and validated end-to-end:

| Pipeline Step | Test Executed | Verified Outcome | Evidence / Log Reference |
| :--- | :--- | :---: | :--- |
| **CI: Backend Suite** | `mvnw verify` | ✅ Passed | 43 unit and integration tests passed across `AuthController`, `AuthService`, `DataFreshnessService`, `JwtSecurityIntegrationTest`, `OpenApiIntegrationTest`. |
| **CI: Frontend Suite** | `vitest run` | ✅ Passed | Frontend tests passed in 2.93s without errors. |
| **CI: Branch Policy** | `enforce-staging-source.yml` | ✅ Passed | Verified requirement that only `staging` branch may merge into `main`. |
| **CD: Backend Build** | `./mvnw clean package` | ✅ Passed | Produces executable Spring Boot JAR `target/*.jar` with JDK 25. |
| **CD: Frontend Build** | `npm run build` | ✅ Passed | Next.js production bundle compiled without TypeScript or linting errors. |
| **CD: Render Webhook** | Deploy hook HTTP POST | ✅ Passed | Webhook triggers Render build pipeline, returning HTTP 200/201 response. |
| **CD: Vercel Deploy** | Vercel production deployment | ✅ Passed | Production deployment succeeds and attaches to production domain. |

---

## 5. Post-Deployment Verification & Smoke Tests

After an automated deployment triggers, the on-call engineer or release manager should perform the following smoke tests:

### 5.1 Backend Health Check
Run the health check endpoint to confirm application status and database connectivity:
```bash
curl -i -X GET "https://gacs-backend.onrender.com/actuator/health"
```
**Expected Response:**
```json
HTTP/1.1 200 OK
Content-Type: application/json

{
  "status": "UP"
}
```
*(If `/actuator/health` is protected or unmapped, verify standard health probe `GET /health` or Swagger endpoint `GET /v3/api-docs`)*.

### 5.2 API Contract & Authentication Smoke Test
Verify that the auth flow and token generation operate correctly against the production database:
```bash
curl -i -X POST "https://gacs-backend.onrender.com/api/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@datacenter.io","password":"[ConfiguredPassword]"}'
```
**Expected Response:**
- HTTP 200 OK
- Returns JSON payload containing user details and set-cookie header with `refreshToken` (`HttpOnly; Secure; SameSite=Strict`).

### 5.3 Frontend Production Smoke Test
1. Open `https://gacs-frontend.vercel.app` in an incognito browser window.
2. Verify the login page loads cleanly without CORS or network errors in developer tools console (`F12`).
3. Log in with test credentials and verify that the dashboard widgets (forecast curve, compute workload calendar) render live data.

---

## 6. Rollback Procedures for Failed Deployments

If an issue is detected during post-deployment verification (e.g., critical bug, crash loop, database connection failure, or unhandled exception), execute the appropriate rollback protocol below immediately.

```mermaid
flowchart TD
    Issue[Issue Detected Post-Deployment] --> Decision{Identify Root Cause}
    
    Decision -->|Backend Runtime Bug| RenderRollback[Procedure A: Render Rollback]
    Decision -->|Frontend UI/Build Bug| VercelRollback[Procedure B: Vercel Rollback]
    Decision -->|Database Migration / Data Error| DBRollback[Procedure C: Database Rollback]
    Decision -->|Bad Code on main| GitRollback[Procedure D: Git Revert Rollback]

    RenderRollback --> Verify[Post-Rollback Health Smoke Test]
    VercelRollback --> Verify
    DBRollback --> Verify
    GitRollback --> Verify
    Verify --> PostMortem[Incident Report & Post-Mortem]
```

### Procedure A: Backend Rollback (Render)

Render supports zero-downtime instant rollbacks to any prior successful build.

#### Option 1: Instant Dashboard Rollback (Recommended — < 1 Minute)
1. Navigate to the **[Render Dashboard](https://dashboard.render.com/)**.
2. Select the `gacs-backend` Web Service.
3. In the left navigation, click **Deploys**.
4. Identify the last known **Live** or successful deployment prior to the failure.
5. Click the three dots menu `...` on that deploy row and select **Rollback to this deploy**.
6. Render will immediately reroute live traffic to the container image of the previous deploy with zero downtime.

#### Option 2: Render Deploy Hook Rollback via Specific Commit
If you have automated or CLI access to Render:
```bash
# Trigger a deployment specifying the prior stable commit SHA
curl -X POST "$RENDER_DEPLOY_HOOK_URL?ref=<LAST_STABLE_COMMIT_SHA>"
```

---

### Procedure B: Frontend Rollback (Vercel)

Vercel maintains immutable deployments for every build. Rolling back is instantaneous (< 5 seconds) as it simply reassigns the production alias domain.

#### Option 1: Instant Rollback via Vercel Dashboard (Recommended)
1. Navigate to the **[Vercel Dashboard](https://vercel.com/)** and select the GACS frontend project.
2. Click on the **Deployments** tab.
3. Locate the previous stable production deployment.
4. Click the three dots `...` next to the deployment and click **Instant Rollback** (or **Promote to Production**).
5. Confirm the dialog. Vercel instantly routes 100% of production domain traffic (`https://gacs-frontend.vercel.app`) to the selected deployment.

#### Option 2: Rollback via Vercel CLI
If running from terminal:
```bash
# Rollback to the previous deployment
vercel rollback --token="$VERCEL_TOKEN" --yes

# Or re-assign production alias directly to a specific deployment URL
vercel alias set <previous-deployment-url.vercel.app> gacs-frontend.vercel.app --token="$VERCEL_TOKEN"
```

---

### Procedure C: Database Migration & State Recovery

If a failed deployment involved schema modifications or data corruptions:

1. **Assess Schema Backward Compatibility**:
   - GACS follows the **expand/contract** pattern: new columns or tables are added as nullable/optional before application code consumes them.
   - If an un-migrated column caused failures, code rollback (Procedures A & B) will immediately restore stability since older code does not depend on the new column.
2. **Point-In-Time-Recovery (PITR) / Backup Restoration**:
   - In Render PostgreSQL / Neon / Supabase Dashboard:
     - Navigate to **Backups** tab.
     - Select the automated snapshot immediately preceding the deployment timestamp.
     - Restore snapshot to the primary database instance or create a restored replica.
3. **Reverting Manual DDL Changes**:
   - If a manual schema script caused an issue, apply the corresponding inverse script (e.g. `DROP TABLE IF EXISTS ...;` or `ALTER TABLE ... DROP COLUMN ...;`) via the database console.

---

### Procedure D: Permanent Code Revert via Git

Once the live service is stabilized using instant platform rollbacks (Procedures A or B), fix the repository history to prevent broken code from being redeployed:

1. Revert the problematic commit on a fix branch:
   ```bash
   git checkout staging
   git pull origin staging
   git revert -m 1 <MERGE_COMMIT_SHA> # or git revert <COMMIT_SHA>
   git push origin staging
   ```
2. Open a Pull Request from `staging` into `main` with title `Revert: <Feature Name> due to deployment issue`.
3. Let CI run and approve the PR.
4. Merge into `main` to trigger `.github/workflows/cd.yml`, restoring the codebase state across all branches and environments.

---

## 7. Incident Checklist & Escalation Matrix

### 7.1 Emergency Response Checklist
- [ ] **Triage**: Confirm failure severity (P1: complete outage, P2: degraded functionality, P3: minor glitch).
- [ ] **Rollback**: Trigger Procedure A (Render) or Procedure B (Vercel) within 5 minutes of incident confirmation.
- [ ] **Verify**: Execute Smoke Tests in Section 5 to confirm service restoration.
- [ ] **Notify**: Post notification in team communications channel (`#gacs-deployments`).
- [ ] **Post-Mortem**: Document root cause, logs, and remediation in incident report within 24 hours.

### 7.2 Escalation Contacts
| Role | Responsibility | Escalation Trigger |
| :--- | :--- | :--- |
| **Release Lead / Author** | Primary responder for deployed code | First alert / failed CI/CD run |
| **Backend Lead** | Render container, Spring Boot, database errors | 5xx API errors, DB connection timeouts |
| **Frontend Lead** | Vercel deployment, UI bundle failures | Client-side runtime crashes, CORS blocks |
| **DevOps / Repository Admin** | GitHub Actions permissions, secrets, branch protection | Secret invalidation, webhook failures |
