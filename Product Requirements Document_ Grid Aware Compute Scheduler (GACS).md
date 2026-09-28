# Product Requirements Document: Grid Aware Compute Scheduler (GACS)

**Document status:** Draft for product and engineering review  
**Version:** 1.0  
**Author:** Manus AI  
**Source brief:** [Grid Aware Compute Scheduler project brief](file:///home/ubuntu/upload/CS203-2.pdf) [1]

## 1. Product summary

Grid Aware Compute Scheduler (GACS) is a decision-support and scheduling platform for operators of flexible, deadline-tolerant compute. It forecasts wholesale electricity prices for a selected grid region, models the operator’s workloads and infrastructure constraints, proposes a lower-cost execution plan, and explains the recommendation in plain English. No workload is moved or started until an authorized human approves the proposed plan.

The first release will focus on ERCOT and a single operating organization. It will support machine-learning training, batch processing, crypto-mining workloads, HVAC pre-cooling, and battery-aware scheduling as configurable workload types. The product will optimize for energy cost while preserving deadlines, throughput, power limits, resource capacity, precedence rules, and minimum runtime blocks.

The target outcome is a **15–25% reduction in energy cost for flexible load without reducing total throughput**, measured against a fixed-schedule baseline over a representative evaluation period. This target is a product hypothesis and must be validated through historical backtesting and a controlled pilot.

## 2. Problem and opportunity

Wholesale electricity prices can move by an order of magnitude or more within a day. Flexible compute operators nevertheless commonly use fixed schedules that do not respond to the price, weather, generation mix, or grid-stress conditions driving those movements. The result is avoidable energy cost and limited operational visibility into the trade-offs between cost, deadlines, and infrastructure constraints.

Existing dashboards may expose price curves, but they generally do not connect those curves to workload deadlines and machine-level constraints. GACS addresses this gap by combining three capabilities in one workflow: probabilistic price forecasting, global workload optimization, and human-readable decision support.

The initial addressable market consists of operators in liberalized, price-transparent wholesale markets with sufficiently open data. The initial user segments are mid-size data centers, crypto-mining operations, and university or enterprise high-performance-computing clusters. Markets with administratively fixed tariffs or closed grid data are out of scope for the initial product.

## 3. Goals and non-goals

### 3.1 Goals

| Goal | Measure of success |
|---|---|
| Reduce flexible-load energy cost | 15–25% improvement against a fixed-schedule baseline in backtesting or pilot measurement, subject to comparable workload and throughput conditions |
| Preserve workload delivery | 100% of approved jobs meet their configured deadline and precedence constraints in the normal operating path |
| Preserve total throughput | No statistically significant decrease in completed work over the evaluation period |
| Improve operator trust | Every recommendation includes a plain-English rationale, projected cost, baseline comparison, assumptions, and uncertainty indicators |
| Keep humans in control | No schedule is executed without an explicit approval action by an authorized user |
| Support reliable planning | Generate a 24–72-hour forecast and schedule for the configured market and planning horizon, with clear data-freshness status |

### 3.2 Non-goals for the MVP

GACS will not directly trade electricity, submit bids to a wholesale market, or control utility infrastructure. It will not guarantee realized savings when forecasts are wrong or when workloads change after approval. It will not support markets that lack usable price and grid data. It will not autonomously override deadlines, safety limits, or user-configured operating policies.

The MVP will not attempt to optimize every facility-control system. Direct execution integrations will be limited to a small number of approved workload adapters, while other workloads may be exported as an approved schedule or consumed through an API.

## 4. Personas and primary use cases

### 4.1 Data-center operations manager

The operations manager wants to lower energy cost while keeping service-level commitments and avoiding power-cap violations. They need a concise view of forecast confidence, expected savings, affected workloads, and the consequences of approving or rejecting a recommendation.

### 4.2 HPC or ML platform administrator

The administrator manages jobs with GPU requirements, minimum runtime blocks, precedence relationships, and deadlines. They need to register workloads, define which jobs are flexible, and understand why a training run was moved without compromising its completion target.

### 4.3 Crypto-mining operations lead

The operations lead wants to pause or reduce mining during expensive intervals and resume it during favorable windows. They need policy controls for minimum uptime, ramp time, machine groups, and operational exclusions.

### 4.4 Energy or finance analyst

The analyst wants to compare planned cost with baseline cost, inspect forecast accuracy, and quantify realized savings. They need historical reports and an auditable record of forecasts, recommendations, approvals, changes, and execution outcomes.

## 5. Product scope and workflow

The end-to-end workflow is as follows:

1. GACS ingests market, generation, weather, natural-gas, battery-storage, facility, and workload data.
2. The forecasting service produces a regional price curve for the next 24–72 hours, including confidence information and data-quality indicators.
3. The workload service validates jobs, resources, deadlines, and operating constraints.
4. The optimization service evaluates candidate schedules and selects a plan that minimizes projected energy cost while satisfying hard constraints and applying configured soft preferences.
5. The explanation service summarizes the recommendation, the principal drivers, expected savings, uncertainty, and any unfulfilled soft preferences.
6. An authorized operator reviews the plan, compares it with the baseline, and approves, rejects, or edits it.
7. After approval, GACS sends the schedule to an execution adapter or exports it for manual execution.
8. GACS records actual execution, realized price, energy use, throughput, and exceptions for reporting and model evaluation.

## 6. Functional requirements

### 6.1 Market and data ingestion

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-01 | The system shall allow an administrator to configure a supported grid region and its market data sources. | P0 | A configured ERCOT region can be selected and displayed in the workspace. |
| FR-02 | The system shall ingest ERCOT day-ahead hourly prices and ERCOT real-time 15-minute prices. | P0 | Each series is stored with timestamp, market region, source, unit, ingestion time, and quality status. |
| FR-03 | The system shall ingest generation by fuel type from available EIA/ERCOT sources. | P0 | Generation series can be viewed and are available as forecasting features. |
| FR-04 | The system shall ingest weather observations or forecasts for wind-farm and solar-farm locations, including wind speed/direction, solar radiation, cloud coverage, temperature, and dew point where available. | P0 | Missing fields are marked explicitly and do not silently become zero values. |
| FR-05 | The system shall ingest EIA natural-gas prices and battery-storage data when available. | P1 | The data catalog identifies coverage dates and whether each feature was used in a model run. |
| FR-06 | The system shall detect stale, missing, duplicated, or anomalous records. | P0 | The forecast and schedule views show a data-quality warning when required inputs are stale or incomplete. |
| FR-07 | The system shall support a replaceable ingestion adapter so blocked or rate-limited public endpoints can be replaced by an AWS-hosted collection endpoint. | P1 | The forecasting pipeline can consume an equivalent normalized schema without changes to downstream services. |

### 6.2 Price forecasting

The forecasting service shall generate a price forecast at the market’s supported planning resolution for a 24–72-hour horizon. The initial model comparison shall include LSTM and XGBoost-style time-series approaches, with uncertainty bands and documented feature coverage. The system shall preserve model version, training window, input snapshot, and evaluation metrics for every forecast run.

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-08 | The system shall generate a forecast for the selected market region and planning horizon. | P0 | A successful run produces a timestamped forecast with point estimates and uncertainty intervals. |
| FR-09 | The system shall show the forecast together with historical actuals and the fixed-schedule baseline where available. | P0 | An operator can identify predicted low- and high-price periods without downloading raw data. |
| FR-10 | The system shall expose forecast confidence and data-freshness indicators. | P0 | The UI distinguishes high-confidence, low-confidence, and unavailable periods. |
| FR-11 | The system shall record forecast accuracy metrics, including at minimum MAE and RMSE, by horizon and market region. | P1 | Metrics can be filtered by model version and evaluation period. |
| FR-12 | The system shall support an ablation comparison between models with and without the battery-storage feature. | P1 | A report shows whether battery-storage data improves forecast or scheduling performance over the same evaluation window. |
| FR-13 | The system shall prevent a schedule from being represented as precise when required forecast inputs are unavailable. | P0 | The recommendation is blocked or marked provisional, with the missing inputs stated. |

### 6.3 Workload and infrastructure management

Users shall be able to register workloads manually, by API, or through an approved adapter. A workload record shall include workload type, resource requirements, estimated power profile, earliest start, deadline, expected runtime, minimum runtime block, preemption policy, precedence relationships, and operational priority. Users shall also be able to define facility-wide power limits, resource capacity, blackout windows, maintenance windows, and minimum or maximum operating levels.

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-14 | The system shall allow users to create, edit, validate, and retire workload definitions. | P0 | Invalid or contradictory fields are identified before a workload can be scheduled. |
| FR-15 | The system shall model GPU, CPU, memory, power, and machine-group capacity constraints. | P0 | A proposed schedule never exceeds configured hard capacities. |
| FR-16 | The system shall model deadlines, earliest starts, minimum runtime blocks, precedence constraints, and allowed interruption behavior. | P0 | Test scenarios demonstrate that each hard constraint is honored. |
| FR-17 | The system shall support configurable soft constraints and priorities. | P1 | The optimizer can trade a soft preference against cost and reports when it does so. |
| FR-18 | The system shall support battery storage as an optional resource with charge, discharge, efficiency, state-of-charge, and reserve constraints. | P1 | Battery decisions are visible in the plan and included in cost calculations when enabled. |

### 6.4 Schedule optimization

The optimizer shall minimize projected energy cost over the planning horizon while satisfying hard constraints. It shall account for the forecast curve, workload power profiles, resource capacities, deadlines, precedence, minimum runtime blocks, facility power limits, battery constraints, and user-defined policies. The optimizer shall return both the recommended schedule and the baseline schedule used for comparison.

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-19 | The system shall generate a globally evaluated schedule for all eligible workloads in the planning horizon. | P0 | The result includes a schedule status, solver run ID, objective value, and constraint-validation result. |
| FR-20 | The system shall identify workloads that cannot be moved and explain why. | P0 | Each excluded or unchanged workload has a machine-readable reason and a user-readable explanation. |
| FR-21 | The system shall calculate projected cost, baseline cost, projected savings, and projected energy use. | P0 | Values are shown per workload and in aggregate, with units and assumptions. |
| FR-22 | The system shall show the trade-off between cost savings and deadline slack. | P1 | The user can inspect how much timing flexibility remains after scheduling. |
| FR-23 | The system shall provide a deterministic or reproducible result for the same input snapshot, model version, and solver configuration. | P1 | Re-running an unchanged scenario produces the same result or records the reason for any difference. |
| FR-24 | The system shall fail safely when no feasible schedule exists. | P0 | No schedule can be approved as feasible; the system identifies the conflicting constraints and offers the baseline or a partial recommendation. |

### 6.5 Explanation and operator interaction

Every recommendation shall include a concise explanation generated from the structured forecast and optimization output. The explanation must not invent facts that are absent from the underlying result. It shall identify the main price drivers, the workloads moved, the expected cost effect, the relevant constraints, and the uncertainty that could affect the outcome. The operator may ask follow-up questions in natural language, but answers must be grounded in the current forecast, schedule, and audit record.

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-25 | The system shall generate a plain-English explanation for each schedule recommendation. | P0 | The explanation cites the affected time window, workloads, projected savings, and key constraints. |
| FR-26 | The system shall distinguish forecasted values from observed values and assumptions. | P0 | The explanation labels projected savings as projections until actual execution data is available. |
| FR-27 | The system shall allow operators to ask questions about the current recommendation. | P1 | Answers are scoped to the selected schedule and include a fallback when evidence is insufficient. |
| FR-28 | The system shall provide a structured explanation payload in addition to generated text. | P1 | The UI can render the rationale without relying on free-form text parsing. |

### 6.6 Approval, execution, and audit

Human approval is a mandatory gate. The approval view shall show the proposed schedule, baseline, expected savings, affected workloads, constraint status, forecast confidence, data freshness, and explanation. The user shall be able to approve, reject, or return the plan for revision. The system shall record who acted, when they acted, what version they reviewed, and what happened afterward.

| ID | Requirement | Priority | Acceptance criteria |
|---|---|---:|---|
| FR-29 | The system shall require explicit approval by an authorized user before execution. | P0 | An unapproved recommendation cannot be sent to an execution adapter. |
| FR-30 | The system shall support approve, reject, and revise actions. | P0 | Each action changes the recommendation state and records an audit event. |
| FR-31 | The system shall prevent approval of an expired or materially changed recommendation without re-review. | P0 | A change in forecast, constraints, or workload set invalidates the prior approval according to configured thresholds. |
| FR-32 | The system shall support an execution adapter or export format for approved schedules. | P0 | An approved schedule can be delivered to the configured adapter or downloaded in a documented format. |
| FR-33 | The system shall record execution outcomes and exceptions. | P1 | Actual start, stop, delay, failure, price, energy, and throughput data can be associated with the approved plan. |
| FR-34 | The system shall provide an immutable audit trail for forecasts, inputs, schedules, approvals, revisions, and execution results. | P0 | An auditor can reconstruct what was known and approved at each decision point. |

## 7. User experience requirements

The primary interface shall be a web application built with Next.js, React, and TypeScript. The home view should answer three questions immediately: **What will prices do? Which workloads can move? What should I approve?**

The dashboard shall contain a 72-hour price and confidence chart, a workload timeline, a facility-capacity view, a baseline-versus-recommended cost summary, and a clear recommendation status. The approval screen shall place the recommended schedule beside the baseline and expose the explanation before the approval control. Warning states shall be prominent when data is stale, forecast confidence is low, a constraint is near violation, or execution feedback is missing.

The interface must support keyboard navigation, readable color contrast, and non-color indicators for status. Time zones, currency, power units, and energy units must be explicit and configurable per market or organization.

## 8. Technical and integration requirements

The reference architecture is a Java/Spring Boot backend for REST APIs, authentication, authorization, and CRUD operations; a Python service for forecasting, anomaly detection, and optimization; PostgreSQL with TimescaleDB for relational and time-series storage; and a Next.js/React/TypeScript frontend. AWS shall provide the deployment and monitoring environment. GitHub and Jira shall support source control and delivery tracking.

Services shall communicate through versioned contracts. Long-running forecast and solver jobs shall be asynchronous and expose status, progress, logs, and result identifiers. The system shall use UTC internally and retain the source time zone for display and audit purposes. Every model output shall be traceable to an input snapshot and model version.

The API shall support organization-level isolation. Authentication shall use JSON Web Tokens or an equivalent secure mechanism. Authorization shall distinguish at minimum viewer, operator, approver, and administrator roles. Secrets and provider credentials shall be stored outside source control. Production data shall be encrypted in transit and at rest.

## 9. Data model overview

| Entity | Purpose | Key fields |
|---|---|---|
| Organization | Isolate a customer and its policies | ID, name, market regions, time zone, currency |
| MarketRegion | Define a forecast and scheduling region | ID, market, resolution, data sources |
| PriceSeries | Store day-ahead and real-time prices | timestamp, region, price, unit, source, quality |
| GridSignal | Store generation, fuel, weather, gas, and storage features | timestamp, region/location, feature, value, source |
| ForecastRun | Track a model output | model version, input snapshot, horizon, metrics, status |
| Workload | Represent flexible compute or facility work | type, resources, power profile, runtime, deadline, policy |
| ResourcePool | Represent GPUs, CPUs, machines, power, and batteries | capacity, availability, limits, operating rules |
| ScheduleRun | Track an optimization attempt | inputs, solver config, objective, status, feasibility |
| ScheduleItem | Represent a workload placement | workload, start, end, resource allocation, rationale IDs |
| Approval | Record human decision | actor, decision, timestamp, schedule version, comment |
| ExecutionEvent | Record actual outcome | event type, observed time, actual price, energy, throughput |
| AuditEvent | Reconstruct system activity | actor, action, entity, previous value, new value, timestamp |

## 10. Success measurement and evaluation plan

The baseline shall be a fixed schedule using the same workload set, resource configuration, and evaluation horizon. Backtesting shall use two years of ERCOT historical data where the required inputs are available. The evaluation shall report energy cost, energy consumed, completed workload units, deadline adherence, forecast error, schedule feasibility, and savings variance.

The main product metric is energy-cost reduction on eligible flexible load. Guardrail metrics are total throughput, deadline adherence, constraint violations, forecast data availability, recommendation latency, approval rate, and realized-versus-projected savings. Savings shall not be claimed when the baseline and optimized runs use materially different workload volumes or operating conditions.

A successful MVP should demonstrate that the forecasting pipeline runs reliably, the optimizer produces feasible schedules, operators can understand and approve those schedules, and the backtest shows the target savings range without throughput loss. A pilot should then compare projected and realized savings while preserving a clear rollback to the baseline schedule.

## 11. Reliability, security, and safety requirements

GACS is a decision-support system with operational consequences. It shall fail closed for execution: missing approval, invalid constraints, stale required data, or an unverified schedule must prevent execution. The baseline schedule shall remain available as a fallback. The system shall never silently change a workload’s deadline, resource requirement, or operating policy.

The platform shall provide health checks for ingestion, forecasting, optimization, explanation, database, and execution adapters. It shall alert on stale data, repeated job failure, infeasible schedules, large forecast drift, and missing execution feedback. Logs shall avoid exposing credentials or unnecessary workload payloads.

LLM-generated explanations shall be grounded in structured system outputs. The explanation service shall be isolated from the execution path, and a failure to generate text shall not block access to the numerical recommendation or the approval controls. Users must be able to inspect the underlying data and constraints used for a decision.

## 12. Release plan

### Phase 0: validation and data foundation

Confirm the ERCOT data contracts, build normalized schemas, establish the fixed-schedule baseline, and create a reproducible two-year backtesting dataset. Resolve endpoint access and rate-limit issues before model comparison.

### Phase 1: MVP decision-support workflow

Deliver ERCOT ingestion, price forecasting, workload registration, constraint validation, optimization, cost comparison, plain-English explanation, approval workflow, audit trail, and schedule export. Execution should remain limited to a safe adapter or manual export during this phase.

### Phase 2: pilot operations

Add execution feedback, realized-savings reporting, stronger monitoring, role-based approval policies, battery-aware optimization, and one or more controlled workload adapters. Run the pilot with a rollback procedure and compare projected with realized outcomes.

### Phase 3: market and integration expansion

Add additional markets only after data quality, forecast evaluation, unit handling, and operational policies are defined for each market. Expand adapters for HPC, ML platforms, mining orchestrators, and facility-management systems based on pilot demand.

## 13. Risks and mitigations

| Risk | Impact | Mitigation |
|---|---|---|
| Forecast error or low-quality source data | Projected savings may not materialize | Show uncertainty, validate freshness, retain baseline, and measure realized versus projected results |
| Public endpoint blocking or rate limiting | Forecast pipeline may fail | Use replaceable adapters, caching, backfill jobs, and an AWS-hosted collection endpoint where permitted |
| Optimizer produces infeasible or slow results | Operators cannot act within the planning cycle | Validate constraints independently, impose solver time limits, return the best feasible solution, and expose infeasibility reasons |
| Workload metadata is inaccurate | Jobs may miss deadlines or violate policies | Validate inputs, support conservative defaults, and require approval before execution |
| Operators do not trust recommendations | Low adoption | Provide baseline comparisons, rationale, uncertainty, auditability, and explainable edits |
| LLM explanation is misleading | Poor decisions or loss of trust | Ground text in structured fields, display source values, use safe fallbacks, and keep LLM output outside the execution gate |
| Battery data coverage is limited | Model comparison may be biased | Run explicit with/without-battery experiments and label coverage limitations |
| Market rules differ across regions | Incorrect cost or schedule assumptions | Treat market configuration as region-specific and expand only after validation |

## 14. Open questions and decisions required

1. Which ERCOT region or settlement-point granularity will the MVP support?
2. What is the authoritative source and licensing arrangement for each production data feed?
3. Which workload orchestrator or export format will be used for the first pilot?
4. Which constraints are hard by default, and which are configurable soft preferences?
5. What price components, transmission charges, taxes, or facility charges must be included in the cost model?
6. What threshold makes a forecast or schedule “materially changed” and therefore invalidates approval?
7. What approval roles and separation-of-duties rules are required for each target customer?
8. What is the maximum acceptable latency for ingestion, forecasting, optimization, explanation, and approval refresh?
9. How should negative prices, missing intervals, daylight-saving changes, curtailment, and real-time corrections be represented?
10. What battery-storage data and operating controls are available for the pilot?

## 15. Definition of done for MVP

The MVP is complete when an authorized user can configure an ERCOT region, view a fresh 24–72-hour forecast with uncertainty, register workloads and constraints, generate a feasible optimized schedule, compare it with a fixed-schedule baseline, read a grounded explanation, approve or reject the recommendation, export the approved schedule, and reconstruct the full decision from the audit trail.

The MVP must pass tests for deadline adherence, resource and power limits, precedence, minimum runtime blocks, approval enforcement, stale-data handling, infeasible schedules, role permissions, and reproducibility. It must also produce a backtesting report covering forecast accuracy, projected cost reduction, throughput, and constraint violations.

## References

[1]: file:///home/ubuntu/upload/CS203-2.pdf "Grid Aware Compute Scheduler (GACS) project brief"
