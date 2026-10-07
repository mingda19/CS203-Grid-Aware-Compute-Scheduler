# LZ_NORTH DAM Price Forecasting — EDA & Modeling Plan

Scope: predict ERCOT day-ahead market (DAM) settlement point price for **LZ_NORTH** specifically.
No code yet — this is the plan, written after pushing back on the original ask. See the "Resolved
issues" section for what changed and why.

## 1. What "predict" means here (needs to be nailed down before anything else)

DAM clears once a day: bids close ~10:00 Central, all 24 hours of next-day prices post by ~13:30
Central. So the model's job is: **as of the DAM bid deadline for day D-1, predict all 24 hourly
LZ_NORTH prices for day D.** Every feature must be something that would actually be known by that
deadline. This is the single most important constraint on everything below — see §3.1.

Open question for you: is the target *only* DAM, or do we also want RTM later? This plan assumes
DAM-only for now (that's what you have via `gridstatus_pull.py` / the HistoricDAM xlsx files);
RTM would need its own leakage analysis since it clears continuously, not once a day.

## 2. Data inventory (what we actually have)

| Source | Grain | Geography | Publication lag | Notes |
|---|---|---|---|---|
| HistoricDAM xlsx / `gridstatus_pull.py` | Hourly | All 8 LZ zones | Known 1 day ahead (it's the target) | Target variable. `ercot_dam_lz_north.csv` or the xlsx backfill. |
| OpenMeteo — load_demand (21 sites) | Hourly | Points near DFW-area load centers | **Actual/reanalysis, not forecast** | See §3.1 |
| OpenMeteo — datacenters (7 sites) | Hourly | Datacenter sites | Actual/reanalysis | Relevance to price unclear, see §3.2 |
| OpenMeteo — solar (14 sites) | Hourly | Solar farm sites | Actual/reanalysis | No farm metadata (area, efficiency) |
| OpenMeteo — wind (17 sites) | Hourly | Wind farm sites | Actual/reanalysis | No farm metadata (rotor area, Cp); no pressure variable |
| EIA `fuel_generation_monthly` | Monthly | **US-wide**, not ERCOT | 2-3 months | Low value here — see §3.3 |
| EIA/ERCOT `fuel_pct_hourly` | Hourly | ERCOT (ERCO) | ~1 day | Actual generation by fuel type — settled, not a day-ahead input |
| EIA `hourly_demand_forecast` | Hourly | ERCOT | ~1 day | Has both actual demand AND ERCOT's own forecast demand — the forecast column may be usable same-day |
| EIA `fuel_price` (Henry Hub) | Daily | National benchmark | Several days, trading days only | Legitimate lagged feature — gas is the ERCOT marginal fuel much of the time |

No location→settlement-zone mapping exists yet for any OpenMeteo table. That's a dependency, not
a detail — see §3.4.

## 3. Resolved issues (the pushback, turned into decisions)

### 3.1 Leakage: split every feature into "available at bid time" vs "settled/actual"
This is the organizing principle for the whole feature set:

- **Available at bid time (safe):** anything lagged by ≥1 full day relative to delivery day D.
  LZ_NORTH's own price history (t-24h, t-48h, t-168h, rolling means), yesterday's actual fuel mix,
  yesterday's actual demand, Henry Hub price as of the most recent trading day, calendar features
  (hour, day-of-week, month, holiday flags).
- **Not available at bid time as actuals, but a forecast exists (needs substitution):** weather
  for delivery day D. We currently only store actual/reanalysis weather. For a real day-ahead
  model this needs to be weather *forecast* data (Open-Meteo has a separate forecast endpoint).
  **Decision needed from you:** do we (a) switch the weather pull to store forecasts going
  forward and accept we can't properly backtest further than when that starts, or (b) treat this
  first model as a same-day "nowcast" explanatory exercise rather than a true day-ahead
  forecaster, and fix this before anything gets near production? I'd default to (b) for the EDA
  phase and flag it loudly in the model writeup, but this needs your call before modeling starts.
- **Never usable as a same-hour feature:** `fuel_pct_hourly` actual generation, `fuel_generation_monthly`,
  same-hour prices from other zones (they clear in the same auction, see §3.6).

### 3.2 Datacenter weather: parked, not dropped
No clear causal path from datacenter-site weather to LZ_NORTH price that isn't already captured
by `hourly_demand_forecast` (system-wide actual/forecast demand). Excluding it from the price
model's feature set for now. It likely belongs to a separate compute/cooling-load model that
*consumes* this price forecast, not one that feeds it. Revisit if EDA shows a correlation we can't
explain via demand.

### 3.3 `fuel_generation_monthly`: excluded from the price model
US-wide, monthly, 2-3 month lag — dominated by the ERCOT-specific, hourly `fuel_pct_hourly` for
anything happening at the Texas/hourly scale we care about. Keeping it available for a sanity-check
EDA plot (does national mix correlate with anything at all) but not planning to engineer it into
model features.

### 3.4 Location handling: aggregate to zone/system level, not per-location, until mapping exists
Rather than carrying 17 wind-site columns, 14 solar-site columns, etc. into the model:
- Build one **engineered proxy feature per fuel type per hour**, aggregated across that dataset's
  sites (mean, or mean of per-site proxy-power — see §4), rather than per-site raw features.
- This sidesteps needing a location→zone mapping for v1, at the cost of not capturing e.g.
  "wind in the Panhandle vs. wind on the coast" separately. If EDA shows the aggregate proxy is
  too noisy, the next step is clustering sites by behavior (not necessarily by true zone, since we
  don't have that mapping) rather than a blind per-site feature explosion.
- Getting a real location→zone mapping (or at least lat/long → ERCOT weather zone) is a prerequisite
  for doing this properly later; flagging as a backlog item, not blocking this first pass.

### 3.5 Wind/solar formulas: proxy features, not physical units
You don't have farm area, panel efficiency, γ, rotor area, or Cp — none of that metadata exists
anywhere in the schema. So:
- These are **relative engineered features**, scaled arbitrarily, not literal MW. That's fine for
  a predictive model (it only needs to correlate with price), but we should not present outputs as
  "predicted generation in MW."
- **Air density** needs real pressure, which was never fetched (`temperature_1000hPa` is a
  temperature at an isobaric level, not site pressure). Two options: (a) go back and add
  `surface_pressure`/`pressure_msl` to the wind weather pull (real fix, but means re-backfilling),
  or (b) use a standard-atmosphere constant pressure and let ρ vary with temperature only (coarse
  approximation, usable for v1). Recommending (b) for now, flagging (a) as a data-pipeline TODO.
- **Turbulence intensity** proxied as a gust factor (`wind_gusts_10m / wind_speed_100m`), not true
  std/mean TI, since we only have one reading per hour.
- **Wind veer** computed with circular difference: `((dir2 - dir1 + 180) % 360) - 180`, never plain
  subtraction.
- **Wind power proxy** will be piecewise-capped using generic turbine-curve assumptions (cut-in
  ~3 m/s, rated plateau from ~12-13 m/s, cutout ~25 m/s) instead of raw unbounded v³, since real
  turbines don't scale indefinitely. These thresholds are generic assumptions, not this fleet's
  actual spec — documented as such in the feature code when it's written.
- **Solar cell temperature** estimated from ambient temp + irradiance via a standard NOCT-style
  approximation (`T_cell ≈ T_air + (NOCT-20)/800 × irradiance`, NOCT≈45°C as a generic default),
  rather than treating ambient air temperature as if it were the panel's own temperature.

### 3.6 Cross-zone prices: lagged only
Other zones' DAM prices are legitimate features only when lagged (e.g., yesterday's LZ_SOUTH
price). Same-hour cross-zone prices are mutually simultaneous outputs of the same auction and
cannot be used to predict each other.

### 3.7 Target distribution and extreme events
LZ_NORTH's history includes Winter Storm Uri (Feb 2021) and ERCOT's scarcity/price-cap behavior.
EDA will quantify this explicitly (§5) before any model is chosen; likely candidates are a
log(price - min + ε) transform, winsorizing at a high percentile for training stability, and/or a
separate binary "price spike" classifier feeding into the regression. Decision deferred to after
EDA, not assumed now.

**Decided**: winsorizing at a fixed 4-std band, computed once from the full 2020-2026 training
history (mean=56.35, std=362.55 → band [-1393.85, 1506.55]). `lz_north_price_winsorized` is the
model's actual training target (`lz_north_price_winsorized` in train.csv/val.csv); raw
`lz_north_price` is kept alongside it for reference. The same fixed band (not one recomputed from
a recent window) must be used to clip any live price-lag feature at serving time, or predictions
would be computed on a different scale than the model was trained on - see
`predictions.py`'s `WINSORIZE_LOWER`/`WINSORIZE_UPPER`.

### 3.8 DST and sequence continuity
Spring-forward (23h) and fall-back (25h) days exist in every year of this data. Any fixed-length
"24 steps = 1 day" windowing scheme for LSTM/GRU/TCN needs explicit handling — either drop/flag
those two days per year from daily-aligned windows, or window purely on elapsed-hour count rather
than calendar-day boundaries. Deciding this at pipeline-build time, not after a model complains.

## 4. Data pipeline design

Single master table, one row per UTC hour, built as:

1. **Target + autoregressive features**: LZ_NORTH DAM price, plus lags (t-24h, t-48h, t-168h) and
   rolling stats (24h, 168h mean/std). Other zones' prices, lagged only (§3.6).
2. **Weather → zone/system-level proxy features** (§3.4): per dataset (wind, solar, load-demand),
   aggregate across sites into a handful of engineered features per hour (§3.5 formulas), not raw
   per-site columns. Datacenter weather excluded (§3.2).
3. **EIA features, joined "as-of" with real publication lag** — not the same-period value filled
   backwards. For `fuel_pct_hourly`: shift so day D's features use the latest data *actually
   published* by the bid deadline for day D-1 (i.e. roughly D-1's actuals). For `fuel_price`
   (Henry Hub): most recent trading-day close as of bid time. `fuel_generation_monthly` excluded
   from features (§3.3), kept only for an exploratory plot. (EIA's demand/`hourly_demand_forecast`
   was dropped after implementation - see §8.3; GridStatus's `load_forecast_dam` replaced it.)
4. **Calendar features**: hour-of-day, day-of-week, month, US federal holidays, cyclical encodings
   (sin/cos) for hour and day-of-year.
5. **Train/val/test split: strictly temporal**, no shuffling. Something like train through 2024,
   validate 2025, test 2026 (exact cutoffs TBD once we see how much history is usable after the
   weather-leakage decision in §3.1). Winter Storm Uri's period should be explicitly identified and
   its treatment (included/excluded/stress-test-only) decided consciously, not left to fall
   wherever the date split happens to put it.
6. **For XGBoost**: the master table above, used directly (lag/rolling features already encode the
   sequence; no separate windowing needed).
7. **For LSTM/GRU/TCN**: sliding windows over the same master table, windowed by elapsed-hour count
   (§3.8), with a consistent scaler fit only on the training split.

## 5. EDA task list

**Target (LZ_NORTH price):**
- Distribution (raw and log), extreme-value identification (confirm Uri and any other price-cap
  events, quantify how many hours are affected), autocorrelation/PACF, seasonality (hour-of-day,
  day-of-week, month), stationarity check.

**Weather vs. price:**
- Correlation of raw weather and engineered proxy features (per §3.5) against price, at various
  lags (0h, same-day, 1-day, since §3.1 means same-day weather may not be usable as a feature even
  if it correlates).
- Day/night masking sanity check for solar features (irradiance should be ~0 at night; confirm the
  data reflects that before trusting derived features).

**EIA vs. price:**
- `fuel_pct_hourly` vs. price (as a lagged/explanatory signal, explicitly not same-hour).
- Henry Hub price vs. price, at various lags — expect a meaningfully positive relationship given
  gas is frequently the marginal fuel in ERCOT.
- Sanity-check only: does the US-wide monthly generation mix show any visible relationship at all,
  to confirm the decision to exclude it (§3.3) isn't throwing away something unexpectedly useful.

**Cross-zone:**
- Correlation structure across all 8 LZ zones' prices (lagged), to see whether other zones add
  information beyond LZ_NORTH's own history.

**Data quality:**
- Missing-data audit per source (the wind data in particular has known NaNs for some fields).
- Confirm DST handling (§3.8) produces the expected 23/25-hour days with no silent duplicates or
  drops.

## 6. Modeling plan

- **Baseline first: XGBoost** on the engineered master table. Given the data volume (~45-55k
  hourly rows for LZ_NORTH) and the spiky/heavy-tailed target (§3.7), this is likely to be a strong
  baseline and may be hard for LSTM/GRU/TCN to beat without a lot of tuning — establishing this
  before investing in sequence models, not after.
- **LSTM / GRU / TCN** evaluated only once the baseline + EDA show temporal structure a
  lag-feature tree model isn't capturing (e.g., the autocorrelation/PACF analysis in §5 suggests
  dependencies beyond what's hand-engineered). Whichever is tried, needs the DST-aware windowing
  from §3.8 and a target transform decided from §3.7.
- **Evaluation**: needs to be meaningful under the heavy-tailed target — plain RMSE will be
  dominated by spike hours. Plan to report both an overall metric and a spike-hours-only /
  non-spike-hours-only breakdown, plus a naive baseline (e.g., same-hour-last-week) for context.

## 7. Open questions for you before implementation starts

1. DAM-only, or do we also want RTM eventually? (Affects §1.)
2. How do we want to handle the weather-forecast-vs-actual leakage gap (§3.1) — fix the data
   pipeline to store forecasts going forward, or treat this first pass explicitly as a same-day
   explanatory model rather than a deployable day-ahead forecaster?
3. OK with excluding datacenter weather and `fuel_generation_monthly` from the feature set for now
   (§3.2, §3.3)?
4. OK with zone/system-level aggregated proxy features instead of per-location features until a
   real location→zone mapping exists (§3.4)?
5. Any preference on how to treat Winter Storm Uri specifically — include normally, exclude from
   training, or hold out as a dedicated stress-test case (§3.7, §4.5)?

## 8. Serving-time feature validation (post-EDA)

The EDA above picked candidate features by correlation with price. That is necessary but not
sufficient: a feature can correlate well in a training table built from history, yet still hurt
the model once it is actually computed at prediction time from whatever is live in Supabase at
that moment. Three features were built, measured, and in two of three cases rejected this way
after implementation was already underway. Recorded here so the reasoning survives independently
of the chat session that produced it.

### 8.1 Methodology: realistic-serving simulation
`predictions.py` builds one 24-hour batch per day-ahead run, all at once, before any of those 24
hours have become "actuals." That constrains what a feature is allowed to depend on:
- A **lag feature** (t-24h, t-48h, ...) is fine as long as the hour it reaches back to is already
  in the past relative to the *whole batch's* bid time, not just relative to the specific target
  hour — true for lag_24h/lag_48h/lag_168h here, since even the batch's last hour reaches back to
  an already-known day.
- A **rolling-window feature** (roll_mean_24h, etc.) is different: hour 2 through hour 24 of the
  batch would each need a window that partly overlaps the *also-being-predicted* hours before
  them, which don't exist yet. `build_features()` handles this by freezing every rolling stat at
  the last known-actual hour and broadcasting that one frozen value across all 24 target hours,
  rather than recomputing it per hour.

To test whether a candidate feature actually survives this constraint, the evaluation harness
simulates it directly: features are computed the same frozen/broadcast way a live batch would see
them, not recomputed per-row from the full (freely-available-in-training) history, and the
resulting RMSE/MAE on `val.csv` is compared against the same model without the feature. This
caught two features that looked fine in ordinary (non-simulated) backtesting but degraded sharply
under the real serving constraint.

### 8.2 `lag_1h`: dropped
Reaches back only 1 hour. For hour 2+ of a day-ahead batch, "1 hour before the target hour" falls
*inside* the same not-yet-known batch, so it cannot be a real lag at serving time — the realistic
simulation freezes it at the batch's last known-actual value instead (same treatment as the
rolling features in §8.1). Measured head-to-head: frozen `lag_1h` gave val RMSE 30.0 vs. 16.6
without it. Dropped entirely rather than kept frozen, since freezing didn't recover the value a
true per-hour lag_1h would have had in training — it just added noise.

### 8.3 EIA `demand_forecast_mwh`: dropped, replaced by GridStatus `load_forecast_dam`
EIA's `electricity/rto/region-data` "DF" (day-ahead demand forecast) field looked usable from its
name, but a live call to the real EIA API confirmed "DF" publishes on essentially the same delay
as the actuals ("D") — it is not actually a forecast available ahead of the delivery day, despite
the name. Val RMSE: naive (same-period value, no lag) 23.2, lagged by 1 day to make it honestly
pre-bid-time 18.2, dropped entirely 16.9 (best of the three). Dropped, and the
`hourly_demand_forecast` table + EIA puller for it were removed (§1 pivot point). Replaced by
GridStatus's `ercot_load_forecast_dam` dataset, which publishes once/day at 14:30 CT covering the
*entire* next delivery day — genuinely forward-looking, confirmed live against the API, unlike the
EIA field it replaced.

Naming trap worth flagging explicitly: this dataset's `zone` column uses ERCOT's **weather-zone**
breakdown (`north`/`south`/`west`/`houston`/`system_total`), a completely different naming scheme
from the LZ_ **settlement-point** names (`LZ_NORTH`, etc.) used everywhere else in this project for
`electrical_price`. The two schemes partition the grid differently and are not interchangeable
strings — querying `load_forecast_dam` with `zone = 'LZ_NORTH'` is not "the wrong case," it matches
zero rows outright, silently producing an all-NaN feature with no error. `predictions.py` keeps a
separate `DEFAULT_LOAD_FORECAST_ZONE = "north"` constant specifically so this can't be confused
with `DEFAULT_LOCATION = "LZ_NORTH"` again.

### 8.4 `load_forecast_dam_north_mwh`: kept raw, as a deliberate baseline weakness
GridStatus's north-zone load forecast, used raw (not lagged — it is published before the delivery
day it covers, so no lag is needed). Measured against val.csv:

| Variant | RMSE | MAE |
|---|---|---|
| Without this feature (baseline) | 16.894 | 8.561 |
| With raw `load_forecast_dam_north_mwh` | 32.099 | 13.533 |
| With detrended (deviation from trailing 90-day mean) | 17.231 | 9.119 |

Root cause: a multi-year secular upward trend in Texas load (train-period mean 16,976 MWh, rising
from ~15,346 in 2020 to ~18,987 in the 2026 training tail, vs. a 2026 validation-period mean of
19,777) that a raw-value tree model's splits calibrate against the training range and then
miscalibrate against val's higher range. The detrended variant removes this and roughly matches
the baseline.

**Decision: keep the raw (not detrended) version anyway**, as a deliberately-imperfect baseline.
XGBoost cannot model the trend's shape, but a sequence model (LSTM/GRU, §6) should be able to use
raw load growth over time much better than a tree model can — keeping it raw here, rather than
quietly fixing it with detrending, preserves a real, visible gap for later models to close instead
of papering over a weakness that a better architecture is specifically expected to address. A
calendar "year" feature was considered as a cheaper partial fix for the same trend but intentionally
left out for the same reason: it would only narrow the gap the baseline is meant to demonstrate.
