# LEAR vs LSTM: price model comparison

Snapshot taken **2026-11-11**, from the production Supabase database (read-only queries).
Location: `LZ_NORTH`. Error is in $/MWh; lower is better.

**Summary:** the retrained LEAR beats the current LSTM. Its validation error is about 30% lower at both
horizons, and early live forecasts point the same way. In a 970-day backtest, planning on LEAR saved
4.8% to 23.4% of energy cost depending on the workload (14.7% for the mixed workload). This is why the
optimiser now prices with LEAR and falls back to LSTM (PR #93).

## Models compared

The models active in `model_artifacts` (`is_active = true`) at the time of the snapshot:

| Model | Horizon | Version | Retrained (created) | Trained on data up to |
|---|---|---|---|---|
| LEAR | y0 (48h ahead) | `lear_y0_2026-11-09` | 2026-11-09 | 2026-01-31 |
| LEAR | y1 (72h ahead) | `lear_y1_2026-11-09` | 2026-11-09 | 2026-01-31 |
| LSTM | y0 (48h ahead) | `lstm_y0_2026-11-04` | 2026-11-04 | 2026-01-31 |
| LSTM | y1 (72h ahead) | `lstm_y1_2026-11-04` | 2026-11-04 | 2026-01-31 |

## 1. Validation error (main result)

Each retrain stores its error on a validation window in `model_artifacts.metrics`. Both models were
trained only on data up to 2026-01-31, so this window is data neither has seen.

| Horizon | LEAR MAE | LSTM MAE | LEAR improvement |
|---|---|---|---|
| y0 (48h ahead) | **8.22** | 12.06 | 32% lower |
| y1 (72h ahead) | **8.76** | 12.07 | 27% lower |

Validation windows: LEAR 2026-02-01 to 2026-11-05 (y0) and 2026-11-04 (y1); LSTM 2026-02-01 to
2026-11-01 (y0) and 2026-10-31 (y1). They end a few days apart because the models were retrained on
different dates, so the comparison is close but not on identical days.

## 2. Live forecasts (early signal only)

Forecasts the active models actually published, scored against real prices. Only hours where **both**
models made a forecast and the real price is known are counted, so neither model gets easier days.

| Horizon | Hours compared | LEAR MAE | LSTM MAE | LEAR RMSE | LSTM RMSE |
|---|---|---|---|---|---|
| y0 (48h ahead) | 30 | **12.43** | 27.63 | **16.18** | 33.14 |
| y1 (72h ahead) | 6 | **32.29** | 52.99 | **33.29** | 54.71 |

**Too few hours to rely on.** The new LEAR has only been live since 2026-11-09, so this covers
2026-11-11 to 2026-11-12. Errors are higher than in validation, which suggests these days were harder to
predict. Rerun the query below after a week or two for a meaningful live result.

## 3. Backtest: what LEAR would have saved

The backtest replays the production optimiser over 970 days (2024-01-03 to 2026-08-29) of ERCOT
`LZ_NORTH` day-ahead prices. Each day is planned on the 48-hour forecast, then paid at the actual
prices and compared with running every job at its normal time. Full report:
[reports/backtest-2026-11-11.md](reports/backtest-2026-11-11.md).

| Workload | Saved with LEAR | 95% interval | Perfect foresight | Share of possible savings captured | XGBoost (1 Nov report) |
|---|---:|---:|---:|---:|---:|
| business-hours-training | **23.4%** | [14.1, 32.0] | 28.0% | 84% | 20.6% |
| mixed | **14.7%** | [9.7, 20.8] | 18.6% | 79% | 12.1% |
| tight | **15.8%** | [8.2, 23.7] | 18.2% | 87% | 14.2% |
| nightly-batch | **4.8%** | [4.1, 5.4] | 7.0% | 68% | 3.4% |

- LEAR beats both naive forecasts by 1.7 to 3.8 points of savings; the 95% interval is above 0 in all
  8 comparisons.
- Savings are seasonal: 32.1% in winter and 2.1% in summer for the mixed workload.
- About 14% of days (mixed) cost slightly more than the fixed schedule.
- Forecast error over the period: LEAR MAE 10.61, against 18.25 and 18.86 for the naive forecasts.

**How to read this:**
- **LSTM was not backtested**, so this section compares LEAR with XGBoost and the naive forecasts, not
  with LSTM.
- **The LEAR here is the current code refitted every 7 days on past data only**, not the model stored
  in Supabase. That model was trained on data up to 2026-01-31, so it can't be tested fairly on earlier
  days.
- **The XGBoost column is from the 1 November report**
  ([reports/backtest-2026-11-01.md](reports/backtest-2026-11-01.md)), which ran on older optimiser code.
  The comparison is close but not like-for-like.
- **The workloads are synthetic**, so the percentages depend on the workload profiles.

## How to reproduce

### Active models and their validation error
```sql
SELECT model_kind, target, model_version, is_active, created_at, trained_through,
       valid_from_date, valid_to_date, metrics
FROM model_artifacts
ORDER BY model_kind, target, created_at DESC;
```

### Live head-to-head
Update the version list when a model is retrained.
```sql
WITH latest AS (
  SELECT interval_start_utc,
         split_part(model_version, '_', 1) AS kind,      -- lear / lstm
         split_part(model_version, '_', 2) AS target,    -- y0 / y1
         predicted_price,
         row_number() OVER (PARTITION BY interval_start_utc, model_version ORDER BY generated_at DESC) AS rn
  FROM predicted_price
  WHERE location = 'LZ_NORTH'
    AND model_version IN ('lear_y0_2026-11-09', 'lear_y1_2026-11-09',
                          'lstm_y0_2026-11-04', 'lstm_y1_2026-11-04')
),
scored AS (
  SELECT l.*, e.spp_usd_mwh AS actual
  FROM latest l
  JOIN electrical_price e
    ON e.interval_start_utc = l.interval_start_utc AND e.location = 'LZ_NORTH'
  WHERE rn = 1
),
both_models AS (
  SELECT interval_start_utc, target FROM scored
  GROUP BY 1, 2 HAVING count(DISTINCT kind) = 2
)
SELECT s.target, s.kind, count(*) AS hours,
       round(avg(abs(predicted_price - actual))::numeric, 2)            AS mae,
       round(sqrt(avg(power(predicted_price - actual, 2)))::numeric, 2) AS rmse
FROM scored s JOIN both_models b USING (interval_start_utc, target)
GROUP BY 1, 2 ORDER BY 1, 2;
```

### Backtest
```bash
backtest/run_report.sh     # from the repo root; writes docs/reports/backtest-<date>.md
```
Needs `model/train.csv` and `model/val.csv` locally (not in the repo). See `backtest/README.md`.

## Notes

- **Not the same as `model/evaluate.py`.** That script rebuilds LEAR from the code in
  `model/lear_model.py` and loads LSTM from local files in `model/models/`, so it doesn't test the
  models stored in Supabase. Its reports in `model/reports/` are excluded by `.gitignore` and stay on
  whoever ran it.
- **Older local reports are out of date.** The 2026-10-09 `evaluate.py` reports predate the LEAR fixes
  and retraining. They also favoured LEAR (holdout MAE 5.48 vs 7.56; walk-forward 8.34 vs 11.91, better
  in 5 of 5 periods), but should not be quoted for the current models.
