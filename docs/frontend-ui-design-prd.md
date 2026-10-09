# GACS Frontend Review

**Reviewed URL:** https://cs-203-grid-aware-compute-scheduler.vercel.app/

**Access:** Yes. The public login page loaded successfully, and the prefilled demo login opened the authenticated System Administrator dashboard.

**Scope:** Visual and interaction review of the login screen and the main operations dashboard. This review focuses on frontend structure, information hierarchy, operator workflow, trust, accessibility, and implementation priorities.

## Overall assessment

The product already communicates a credible control-room concept: ERCOT market context, flexible compute workloads, energy mix, savings, approvals, and auditability are all present. The emerald/teal visual language also fits energy and infrastructure operations.

The main problem is **information architecture rather than styling**. The dashboard currently presents many sections, status cards, operational controls, tables, and system-health messages in one long experience. An operator can see a lot, but it is not immediately obvious what requires attention first, what is live versus mocked or unavailable, or what action is safest to take next.

I would prioritize a workflow-led redesign around three questions:

1. **What is happening right now?**
2. **What decision needs operator approval?**
3. **What will happen if I approve, reject, or reschedule it?**

## Priority changes

### P0 — Fix trust and operational clarity first

#### 1. Separate live data, demo data, and unavailable data

The dashboard displays useful operational values such as `$28.40 / MWh`, `58.6% Green`, projected savings, workload schedules, and a human-in-the-loop recommendation. At the same time, the pipeline panel reports `API endpoints 0/2 reachable` and `No telemetry API`.

That combination can make an operator unsure whether the visible numbers are current, simulated, cached, or stale.

**Change:** Add a consistent data-status treatment to every live metric:

- `Live · updated 32 sec ago`
- `Cached · updated 12 min ago`
- `Demo data`
- `Unavailable`

Use the same status component for cards, charts, tables, and recommendations. Add a tooltip or details drawer explaining the source and timestamp. Do not show a “live” green indicator when the underlying endpoint is unavailable.

#### 2. Make the approval workflow the primary dashboard action

The recommendation card is the most consequential part of the page, but it competes with many metrics and navigation items. The primary action should be easier to locate and easier to evaluate.

**Change:** Promote the pending recommendation into a dedicated decision panel near the top of the page:

- A clear `Pending approval` status and approval deadline.
- A short “Recommended change” summary.
- Current schedule versus proposed schedule.
- Expected savings, SLA impact, power impact, and confidence in a compact comparison.
- Affected workloads listed as removable or expandable rows.
- `Approve & dispatch` as the primary action.
- `Reject` and `Request changes` as secondary actions.
- A required confirmation step that summarizes the exact dispatch effect before execution.

The current `Approve & Dispatch Workloads` label is strong, but it should not be a one-click action without a final impact review.

#### 3. Remove production-looking demo credentials from the login form

The login screen visibly contains prefilled email and password values and offers “Demo Persona” buttons. This is useful for a prototype, but it is risky and confusing for a production-facing interface.

**Change:** Split the experience into two explicit modes:

- **Production sign-in:** empty fields, password manager support, SSO/OTP options, and normal validation.
- **Demo mode:** a clearly labeled `Launch demo` entry point that selects a persona without exposing a password in the form.

If demo credentials must remain, label the screen `Demo environment` and ensure the credentials cannot be mistaken for real operational access.

### P1 — Improve scanability and navigation

#### 4. Replace the long anchor-based dashboard with task-oriented sections

The left navigation contains many items: Live Grid & Overview, Workload Scheduler, Price & Wind Forecast, Battery & Energy Mix, Pending Approvals, Historical Audit Logs, Facility Constraints, and System Settings. Most appear to point to sections on one long page.

This creates two problems:

- The page becomes difficult to scan and maintain as more modules are added.
- Operators may lose context when jumping between sections.

**Recommended information architecture:**

- `/overview` — current grid state, alerts, active recommendation, key metrics.
- `/workloads` — scheduling queue, filters, workload details, schedule changes.
- `/forecasts` — price, wind, solar, confidence intervals, forecast comparison.
- `/energy` — battery, facility load, clean-energy mix, constraints.
- `/approvals` — pending and historical operator decisions.
- `/audit` — searchable audit trail and export.
- `/admin` — users, roles, integrations, and system configuration.

Keep a compact overview page, but give dense workflows their own routes and URL state.

#### 5. Create a stronger visual hierarchy for the first viewport

The top of the dashboard should prioritize:

1. System status and data freshness.
2. Current market conditions.
3. Pending operator decision.
4. Immediate operational risk.
5. Supporting metrics.

The current layout gives similar visual weight to many cards. Use one dominant alert/decision region, a smaller row of key metrics, and a lower-density supporting area.

A useful top-level arrangement would be:

- **Header:** market, facility, last refresh, notifications, profile.
- **Alert strip:** stale data, endpoint errors, or unresolved approvals.
- **Decision panel:** pending recommendation.
- **Metric row:** LMP, compute load, battery state, projected savings.
- **Main content:** chart and workload schedule.

#### 6. Replace ambiguous labels with action-specific language

A few labels are too vague for an operational product:

- `Toggle` should become `Pause`, `Resume`, `Throttle`, or `View details` depending on the actual action.
- `Refresh` should show whether it refreshes the page, a data source, or pipeline checks.
- `Re-run Solver` should specify what is recalculated and whether it can change the current recommendation.
- `System Administrator` appears both as a persona and a signed-in user. Make the current identity and role visually distinct.

Every destructive, expensive, or dispatch-related action should state its effect before confirmation.

### P2 — Polish usability, accessibility, and visual consistency

#### 7. Improve the login screen’s production readiness

The login page has a good compact structure and clear primary CTA, but it can be refined:

- Use a more explicit page title such as `Sign in to GACS` rather than mixing platform branding with product description.
- Make the environment visible: `Production`, `Sandbox`, or `Demo`.
- Add inline validation and a clear error region with `role="alert"`.
- Add a password visibility control.
- Make OTP sign-up a secondary flow with its own explanation, rather than a small inline link.
- Keep the compliance/security message, but link it to an actual security or compliance page. Avoid presenting `NERC-CIP & SOC2 Compliant` as an unsupported badge.
- Ensure keyboard focus states are clearly visible and that the demo persona buttons are reachable in a sensible tab order.

#### 8. Make dense tables easier to operate

The workload table contains important information, but its rows are visually dense and some values run together. Improve it with:

- More horizontal spacing and consistent column alignment.
- A responsive card layout on small screens.
- Sortable columns for power, deadline, savings, and status.
- Status chips with text and color, not color alone.
- Row expansion for hardware, precedence rules, SLA, and schedule rationale.
- A dedicated action menu per row instead of a generic `Toggle`.
- Sticky table headers for long queues.

#### 9. Treat charts as primary decision tools, not decoration

The current dashboard describes price and forecast information, but the operator needs to compare time windows directly.

Add a combined chart that shows:

- ERCOT price curve.
- Wind and solar generation or clean-energy availability.
- Facility load and battery charge/discharge.
- Current workload schedule.
- Proposed workload schedule.
- Peak-price windows and uncertainty bands.

Use a shared UTC/local-time toggle and make the selected timezone persistent. Every chart should have a text alternative or data table for accessibility.

#### 10. Add responsive mobile behavior deliberately

The desktop layout relies on a fixed sidebar and a dense multi-column dashboard. On smaller screens, use:

- A collapsible navigation drawer.
- A sticky header with market status and notifications.
- One metric card per row or a horizontal scroll region with clear affordances.
- Stacked approval comparison cards.
- Workload rows that open into details instead of forcing a wide table.
- No hidden actions that are available only on hover.

The most important actions—view recommendation, approve, reject, and inspect data freshness—must remain available without a desktop viewport.

## Suggested revised dashboard flow

### 1. Header

Show the product name, facility/market selector, current environment, last refresh time, notifications, theme control, and user menu. Keep the selected market and facility persistent across routes.

### 2. System status strip

Use a single compact strip for connection status, stale feeds, forecast freshness, and unresolved issues. It should tell the operator whether the dashboard is safe to use for a decision.

### 3. Decision panel

Place the pending recommendation first when one exists. Show current versus proposed schedule, savings, SLA risk, carbon/energy impact, confidence, and affected workloads. Require explicit confirmation before dispatch.

### 4. Current conditions

Use four to six metric cards only. Each card should include value, trend, timestamp, source status, and a link to details.

### 5. Timeline and queue

Put the price/energy/workload timeline beside or above the workload queue. The operator should be able to understand *why* a workload is scheduled at a certain time without navigating to another page.

### 6. History and audit

Keep audit history separate from current operations. It should support filtering by operator, action, workload, time range, recommendation ID, and outcome.

## Visual direction

Keep the existing emerald/teal accent, but reduce the number of competing treatments. Use color primarily for meaning:

- Emerald: healthy, approved, within target.
- Amber: review required, approaching threshold, stale but usable.
- Red: blocked, failed, SLA risk, or unsafe to dispatch.
- Neutral: informational or not yet evaluated.

Avoid making every positive metric green. If all cards are emphasized, none of them becomes the focal point.

Use a slightly lighter content surface and clearer borders for dense operational modules. Preserve the dark control-room theme, but provide a more explicit contrast between background, card, selected navigation, and interactive controls.

## Implementation order

1. **Clarify environment and data freshness.** Add live/demo/unavailable states and remove exposed demo credentials from production mode.
2. **Rebuild the approval panel.** Make the operator decision and its consequences the dominant workflow.
3. **Split dense dashboard sections into routes.** Keep the overview concise and move detailed workflows into dedicated pages.
4. **Improve workload interaction.** Replace generic toggles, add row details, sorting, filters, and responsive behavior.
5. **Upgrade the timeline visualization.** Align price, energy, battery, and workload schedules in one decision-oriented chart.
6. **Finish accessibility and responsive QA.** Test keyboard navigation, screen-reader labels, focus states, contrast, mobile layouts, and loading/error states.

## Definition of done

- An operator can identify the current market, data freshness, and system health within five seconds.
- A pending recommendation is visible without scrolling through unrelated modules.
- The operator can compare current and proposed schedules before approving dispatch.
- Every operational value indicates whether it is live, cached, demo, or unavailable.
- No production login screen exposes a password or ambiguous demo state.
- Workload actions describe their actual effect and require confirmation when they alter dispatch.
- The dashboard remains usable at mobile widths without horizontal scrolling for core actions.
- Loading, empty, stale, error, and permission-denied states are designed rather than left as generic placeholders.
- Charts provide a text/data-table alternative and use consistent timezone handling.

## References

[1]: https://cs-203-grid-aware-compute-scheduler.vercel.app/ "GACS Platform live frontend reviewed on 2026-10-09"
