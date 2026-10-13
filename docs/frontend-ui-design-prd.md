# Frontend UI Design PRD (Product Requirements Document)
**Grid Aware Compute Scheduler (GACS)**  
**Document Status:** Approved Baseline & Enhancement Roadmap  
**Document Version:** 2.0.0  
**Classification:** Product Requirements & Frontend Engineering Standard  
**Last Updated:** October 2026 (SGT)  

---

## 1. Executive Summary & Product Vision

The **Grid Aware Compute Scheduler (GACS)** is an enterprise operational cockpit for datacenter operators managing flexible, deadline-tolerant workloads (e.g., LLM training batches, crypto mining, Monte Carlo simulations, and datacenter HVAC pre-cooling) in volatile wholesale electricity markets (ERCOT).

GACS solves the fundamental tension between compute performance and electricity cost by answering three mission-critical operator questions:
1. **What is happening right now across the grid and datacenter?** (Real-time LMP, clean energy mix, battery storage, and active compute load).
2. **What optimization decision requires human approval?** (Solver-recommended schedule changes to capture negative/cheap renewable pricing while dodging expensive thermal price spikes).
3. **What is the exact financial, operational, and carbon impact of that decision?** (Schedule diffs, dollar savings, SLA margin, peak megawatts shaved, and carbon avoided).

This document establishes the UI/UX architecture, visual design standards, component specifications, and implementation roadmap for the GACS frontend application.

---

## 2. Key Architecture & Design Upgrades (v2.0)

### 2.1 Unified Continuous Price Timeline (Historical + Forecast)
- **Problem:** Previously, historical settlement prices and upcoming model forecasts were split across separate cards and charts. Operators had to scroll between two disconnected views, mentally bridging the transition between past actuals and future predictions.
- **Solution:** A **single, continuous time-series chart** that unifies past actual settlement prices (solid emerald line with subtle area fill) and upcoming ML predictions (dashed violet line with shaded confidence envelope). A vertical **"Current Time / Now (SGT)"** reference divider separates history from forecast, providing a seamless continuum for dispatch decisions.

### 2.2 Singapore Time (SGT / UTC+8) Standardization
- **Problem:** The interface previously displayed timestamps in raw UTC or US Central time, forcing Singapore-based operators to perform mental timezone math (+8 hours) when reviewing batch schedules, deadlines, peak spikes, and audit logs.
- **Solution:** Global standardization to **Singapore Time (SGT / UTC+8)** across all UI modules:
  - Header badge displaying `🕒 SGT (UTC+8) · Singapore Standard Time`.
  - Chart X-axes formatted cleanly in SGT (e.g., `10 Oct 09:00`, `18:00`, `11 Oct 02:00`).
  - Workload scheduling windows, deadlines, and countdowns displayed in SGT.
  - Interactive Google Calendar view aligned strictly to SGT operating cycles.
  - Audit logs and data-freshness badges stamped in SGT with ISO-8601 UTC provenance in tooltips.

### 2.3 Elevated Control-Room Visual Design Language
- **Aesthetic:** High-contrast Dark Mode OLED (`#090d16` background) with glassmorphism (`backdrop-blur-xl bg-card/80`), refined emerald/cyan accents, and amber alert signals.
- **Micro-Sparklines:** Trend preview sparklines embedded in core KPI cards.
- **Visual Schedule Diff:** Visual before-and-after timeline comparison bars in the Human-in-the-Loop decision panel.
- **Calendar Tariff Bands:** Shaded tariff bands in the calendar view highlighting peak pricing windows to visually explain solver decisions.

---

## 3. Detailed Feature Specifications

### 3.1 Feature Spec 1: Unified Continuous Price Timeline Graph

#### 3.1.1 Overview & Placement
The Unified Price Timeline serves as the primary visual decision instrument on both `/overview` (compact preview) and `/forecasts` (full interactive workstation).

#### 3.1.2 Visual Encoding & Layering
1. **Historical Actual LMP Line:**
   - **Stroke:** Solid emerald (`#10b981`), `strokeWidth: 2.5`.
   - **Area Fill:** Gradient emerald-to-transparent (`fill="url(#emeraldGradient)"`, opacity 0.15 → 0.0).
   - **Data Source:** ERCOT Settlement Point Prices (via Spring Boot `/api/prices/history`).
2. **Current Time Divider ("Now" Boundary):**
   - **Line:** Vertical reference line (`ReferenceLine` in Recharts) at the latest historical interval / current timestamp.
   - **Color:** Bright cyan/white (`#38bdf8`), dotted/dashed (`strokeDasharray="3 3"`).
   - **Label:** `● Now (SGT)` badge positioned at the top of the reference line.
3. **Upcoming Forecast LMP Line:**
   - **Stroke:** Dashed violet/indigo (`#8b5cf6`), `strokeWidth: 2.5`, `strokeDasharray="5 5"`.
   - **Area Fill / Confidence Band:** Shaded uncertainty corridor (P10–P90 percentile or ±12% forecast spread) using low-opacity violet (`#8b5cf6`, opacity 0.10).
   - **Data Source:** ML price forecast engine (`/api/prices/forecast`).
4. **Workload Schedule Overlay Lane (Toggleable):**
   - Interactive bar or shaded window along the bottom/background of the graph showing scheduled workload execution windows.
   - Green highlight for active/scheduled runs during price valleys; amber striped highlight during throttled peak spike intervals.

#### 3.1.3 Controls & Horizon Selectors
- **Horizon Preset Pills:**
  - `Past 24h + 24h Forecast` (Default operational window).
  - `Past 48h + 48h Forecast` (Weekend/extended planning).
  - `Past 7d + 72h Forecast` (Macro trend & model validation).
- **Location Selector:** Dropdown for ERCOT Hubs (`LZ_NORTH`, `LZ_HOUSTON`, `LZ_SOUTH`, `LZ_WEST`).
- **Interactive Tooltip:**
  - Standardized SGT timestamp: `[Date] [HH:mm] SGT`.
  - Type indicator: `Historical Settlement` or `ML Forecast (Model v2.4)`.
  - LMP Value: `$XX.XX / MWh`.
  - Contextual renewable share: `% Clean generation` estimate.

---

### 3.2 Feature Spec 2: Singapore Time (SGT / UTC+8) Standardization

#### 3.2.1 Centralized Timezone Formatting Utility
All date and time transformations must use a centralized frontend utility (`src/lib/date-utils.ts`):
```typescript
export const SGT_TIMEZONE = "Asia/Singapore"

// Format full datetime: "10 Oct 2026, 09:30 SGT"
export function formatSgtDateTime(date: Date | string | number): string

// Format time only: "09:30 SGT" or "09:30"
export function formatSgtTime(date: Date | string | number, includeZone = true): string

// Format date only: "10 Oct 2026"
export function formatSgtDate(date: Date | string | number): string

// Format short chart axis time: "10 Oct 09:00"
export function formatSgtChartTick(isoUtcString: string): string

// Convert SGT datepicker string (YYYY-MM-DD) to ISO UTC boundary for API querying
export function sgtDateToUtcIso(sgtDateString: string, isEndOfDay = false): string
```

#### 3.2.2 UI Modules Requiring SGT Alignment
| UI Component | Previous State | Target SGT Standard |
|---|---|---|
| **App Shell Header** | No timezone indicator | Sticky badge: `🕒 SGT (UTC+8) · Singapore Standard Time` |
| **Unified Price Chart** | X-axis labeled in UTC | Formatted in `HH:mm SGT` with date rollover headers |
| **Decision Panel** | `01:30 – 05:30 UTC` | `09:30 – 13:30 SGT (Today)` |
| **Decision Deadline** | `Decision needed before 01:15 UTC` | `Decision needed before 09:15 SGT (in 24 mins)` |
| **Workload Queue Table** | Scheduled windows in UTC | SGT windows + relative time indicator (`Starts in 1.5h`) |
| **Google Calendar View** | UTC slot positioning | Full SGT 24h schedule with primary work hours (08:00–20:00 SGT) highlighted |
| **Data Status Badges** | `updated 12:44:01 UTC` | `updated 20:44:01 SGT · 32s ago` |
| **Audit Logs** | UTC timestamps | SGT display with tooltip revealing exact UTC ISO string for regulatory audit |

---

### 3.3 Feature Spec 3: Control-Room Visual Design System (UI-UX Pro Max)

#### 3.3.1 Palette & Surface Tokens
- **Background Base:** `hsl(222, 47%, 6%)` (`#090d16` deep space dark mode).
- **Card Surfaces:** `hsl(222, 40%, 10%)` with `backdrop-blur-md` and `bg-card/80`.
- **Card Borders:** `hsl(217, 33%, 18%)` with subtle hover transitions (`hover:border-emerald-500/40`).
- **Semantic Accents:**
  - **Emerald (`#10b981`):** Optimal low-price valley, approved plan, clean energy target met.
  - **Teal / Cyan (`#06b6d4`):** Wind/solar renewable generation, secondary telemetry.
  - **Violet (`#8b5cf6`):** Machine learning forecasts, solver optimization recommendations.
  - **Amber (`#f59e0b`):** Approaching peak tariff spike, throttled batch, review required.
  - **Rose / Red (`#f43f5e`):** SLA violation risk, endpoint disconnection, rejected plan.

#### 3.3.2 Typography & Numbers
- **Heading & UI:** `Inter` / System Sans-Serif with clean hierarchy (`text-xl font-bold tracking-tight`).
- **Data & Metrics:** `font-mono tracking-tight` (`Fira Code` / monospace) for all prices, megawatts, percentages, and timestamps to eliminate layout shifts on live poll refreshes.

#### 3.3.3 Enhanced KPI Cards with Micro-Sparklines
Each of the 4 primary overview KPI cards is upgraded from static text to interactive data widgets:
1. **Real-Time LMP Card:**
   - Big metric: `$28.40 / MWh`.
   - Mini sparkline: 12-interval rolling price trend.
   - Peak window alert pill: `Peak spike $142.50 at 01:00 SGT`.
2. **Clean Energy Mix Card:**
   - Big metric: `58.6% Green`.
   - Mini stacked bar: Wind (41.2%) · Solar (17.4%) · Gas/Other (41.4%).
   - Carbon intensity: `298 gCO2/kWh`.
3. **Compute Load & BESS Card:**
   - Big metric: `3.45 MW / 5.0 MW`.
   - Feeder capacity progress bar with safe headroom marker (`1.55 MW Headroom`).
   - Battery SoC sub-badge: `78% (Ready to discharge)`.
4. **Projected Savings Card:**
   - Big metric: `$4,120 today (+22.4%)`.
   - Zero-SLA risk validation badge (`100% On-Time SLA Adherence`).

---

### 3.4 Feature Spec 4: Human-in-the-Loop Decision Cockpit

#### 3.4.1 Decision Hierarchy
The decision panel on `/overview` and `/approvals` is the most consequential interactive surface. It must deliver total clarity within 5 seconds:
1. **Status Header:** Plan status (`Pending Approval`), solver model version (`XGBoost-LSTM v2.4`), and SGT decision cutoff countdown.
2. **Visual Timeline Diff (Before vs. After):**
   - **Baseline Track:** Shows where workloads would run under static scheduling (coinciding with the $142.50/MWh peak spike).
   - **Optimized Track:** Shows workloads shifted into the low-cost 09:30–13:30 SGT wind corridor, with flexible crypto mining throttled during peak hours.
3. **Three-Pillar Impact Summary:**
   - **Financial:** Net savings `$4,120` (+22.4% cost reduction).
   - **Grid / Peak Shaving:** `-1.85 MW` peak demand shed during grid stress.
   - **Carbon Abatement:** `1,240 kg CO2` avoided via West Texas wind capture.
4. **Action Workflow:**
   - Primary: `Approve & Dispatch Workloads` (triggers confirmation dialog summarizing impacted clusters).
   - Secondary: `Request Changes` (opens parameter tuning drawer).
   - Danger/Dismiss: `Reject Plan` (opens structured reason modal for model feedback).

---

### 3.5 Feature Spec 5: Workload Scheduler & Google Calendar Overlay

#### 3.5.1 Dual-View Modality
- **Queue Table Mode:** Dense, filterable, and sortable table with row expansion for hardware specs, cluster placement, and SLA buffer.
- **Calendar Mode:** Google Calendar-style visual grid representing scheduled compute batches across time.

#### 3.5.2 Peak Tariff & Clean Energy Shading on Calendar
- In the calendar grid, background columns/cells during high LMP hours (01:00–04:00 SGT / peak evening ramp) feature subtle amber vertical shading (`bg-amber-500/10` with striped pattern).
- Green valley hours (curtailed wind / low LMP) feature subtle emerald shading (`bg-emerald-500/10`).
- This makes the optimization intuition instantly obvious: operator sees batches automatically arranged inside green lanes, avoiding amber lanes.

#### 3.5.3 Quick Workload Actions & Safety
- Workload status changes (`Pause`, `Resume`, `Throttle`, `Cancel`) trigger explicit action dialogs rather than ambiguous toggles.
- Actions display expected cost and deadline impact before execution.

---

### 3.6 Feature Spec 6: System Trust, Freshness & Auditability

#### 3.6.1 Data Status Standard
Every live data card and chart must include the standardized `DataStatusBadge`:
- `Live · updated [X]s ago` (Green pulse).
- `Cached · updated [X]m ago` (Amber indicator).
- `Demo Sandbox` (Neutral blue badge for simulated telemetry).
- `Unavailable` (Red warning with tooltip explaining endpoint status).

#### 3.6.2 Audit Trail & Governance
- Every dispatch decision, manual throttle, and status change is logged to `/audit`.
- Audit logs capture: Operator ID, Role, SGT Timestamp, Action Type, Target Workloads, and Solver Recommendation ID.
- One-click CSV/JSON export for compliance reporting.

---

## 4. Anti-Patterns to Avoid

| Anti-Pattern | Why It Fails | What GACS Does Instead |
|---|---|---|
| **Separated Forecast & History Charts** | Forces mental timeline stitching; operator cannot see forecast continuity. | **Single unified timeline** with solid history, dashed forecast, and "Now" line. |
| **Raw UTC Times in Singapore Context** | Causes cognitive strain and error-prone manual calculations (+8 hours). | **Standardized SGT (UTC+8)** across all UI views, tooltips, and calendars. |
| **One-Click Instant Dispatch Without Diff** | High risk of dispatching unintended cluster throttles without impact check. | **Two-step confirmation modal** showing exact affected jobs and cost diff. |
| **Generic "Toggle" Buttons** | Ambiguous whether action pauses, throttles, or deletes a batch. | **Explicit action labels**: `Throttle to 50%`, `Pause Task`, `Resume Task`. |
| **All-Green Aesthetic** | When every metric is green, no focal point exists; status alerts lose urgency. | **Strict color semantics**: Emerald for low-cost/success, Violet for ML, Amber for review/spikes. |
| **Unresponsive Wide Tables on Mobile** | Horizontal overflow breaks scanability during on-call incidents. | **Adaptive card layout** on viewports < 768px with full details drawer. |

---

## 5. Implementation Roadmap & Milestones

### Phase 1: Core Timeline & Timezone Standardization (Sprint 1)
- [x] Implement `src/lib/date-utils.ts` with comprehensive SGT formatting helpers.
- [x] Add SGT timezone indicator badge in the App Shell header.
- [x] Build the `UnifiedPriceTimeline` component combining historical prices and ML forecast into a continuous Recharts chart.
- [x] Replace separate forecast and history cards on `/forecasts` and `/overview` with the unified timeline.
- [x] Convert all table timestamps, calendar slots, and decision panel windows to SGT.

### Phase 2: Visual Polish & Decision Cockpit Elevation (Sprint 2)
- [ ] Embed micro-sparklines into the 4 overview KPI cards.
- [ ] Implement the visual Before vs. After schedule diff timeline in `DecisionPanel`.
- [ ] Add the peak tariff amber background shading in `GoogleCalendarView`.
- [x] Add quick status filter tabs (`All`, `Running`, `Scheduled`, `Throttled`) to `WorkloadQueue`.

### Phase 3: Interactive Polish & Edge Cases (Sprint 3)
- [ ] Add toggleable "Workload Execution Windows" overlay directly onto the Unified Price Timeline.
- [ ] Add keyboard shortcut (`⌘K`) command palette for fast search and navigation.
- [ ] Ensure full WCAG 2.1 AA accessibility and contrast validation for both dark and light modes.
- [ ] End-to-end testing with Vitest and Playwright.

---

## 6. Definition of Done (DoD)

1. **Continuous Timeline:** The `/forecasts` page and `/overview` page render historical prices and forecast predictions on a single continuous chart with a clear `Now (SGT)` separator.
2. **SGT Consistency:** 100% of user-facing timestamps, dates, calendar headers, and table rows display in Singapore Time (SGT / UTC+8) with explicit timezone labeling.
3. **Scanability:** An operator can identify current LMP, peak spike risk, and pending recommendation within 5 seconds of loading the overview page.
4. **Safe Dispatch:** Dispatches require a confirmation review displaying the financial and workload diff before executing.
5. **Data Transparency:** Every metric displays its data freshness status (`live`, `cached`, `demo`, `unavailable`) without misleading indicators.
6. **Responsiveness:** All pages are fully functional at 375px, 768px, 1024px, and 1440px viewports without horizontal table overflow.
7. **Type Safety & Test Coverage:** TypeScript compiles with zero errors, and date-utility tests verify timezone conversions.

---

## 7. References & Architecture Links

- **API Contract:** [api-contract.md](file:///c:/Users/looil/Desktop/Y2S1/CS203/project/CS203-Grid-Aware-Compute-Scheduler/docs/api-contract.md)
- **Frontend Source Root:** [frontend/src](file:///c:/Users/looil/Desktop/Y2S1/CS203/project/CS203-Grid-Aware-Compute-Scheduler/frontend/src)
- **Forecast Page:** [forecasts/page.tsx](file:///c:/Users/looil/Desktop/Y2S1/CS203/project/CS203-Grid-Aware-Compute-Scheduler/frontend/src/app/(dashboard)/forecasts/page.tsx)
- **Overview Cockpit:** [overview/page.tsx](file:///c:/Users/looil/Desktop/Y2S1/CS203/project/CS203-Grid-Aware-Compute-Scheduler/frontend/src/app/(dashboard)/overview/page.tsx)
- **Decision Panel:** [decision-panel.tsx](file:///c:/Users/looil/Desktop/Y2S1/CS203/project/CS203-Grid-Aware-Compute-Scheduler/frontend/src/components/ui/efferd-dashboard-2-utils/decision-panel.tsx)
