"""
anomaly_detection_experiment.py

Point 3 of model/model.md's continuous-learning design: which anomaly-
detection method cleanly flags grid-emergency scarcity events (the
EXCLUDED_PERIODS train_xgboost_baseline.py/train_lstm.py hand-maintain -
Winter Storm Uri, Elliott, Juneteenth 2023, MLK 2024, Memorial Day 2024)
without ALSO flagging ordinary demand swings like an uneventful Christmas
(a calendar effect is_us_holiday already accounts for) - so
data_scripts/update.py's weekly tune could exclude a newly-arrived anomaly
automatically, instead of waiting for someone to notice an RMSE spike and
hand-add a date range the way EXCLUDED_PERIODS has been maintained until now.

Both methods work on daily price-shape features, not raw hourly price - a
day is the unit EXCLUDED_PERIODS already operates on, and every known event
below spans multiple consecutive days:
  statistical   Per-day z-score of mean price against a trailing seasonal-
                naive baseline (the same day-of-week, the prior
                BASELINE_WEEKS weeks) - simple, explainable, no fitting.
  hdbscan       sklearn.cluster.HDBSCAN density clustering on standardized
                daily features (mean/std/max/range/frac-of-hours-above-spike).
                Noise points (label == -1) are the candidate anomalies.

Evaluated two ways against EXCLUDED_PERIODS as ground truth:
  recall         for each event window, did the method flag >= 1 day inside it?
  false positives  across a control set of ordinary (non-emergency) holidays
                (the same holidays EXCLUDED_PERIODS' events happen to fall
                on, in years that were NOT emergencies) - a method that
                flags Christmas every year regardless of whether it was an
                emergency is only reproducing is_us_holiday, not finding
                anything new.

Result on the real data (model/train.csv + val.csv, 2020-01-01..2026-08-30,
2434 days - rerun this script to reproduce): both methods catch all 5/5
known events. HDBSCAN is the cleaner of the two - 0/18 false positives on
the ordinary-holiday control set and a lower overall flag rate (3.6% of all
days vs 7.6%), against the statistical method's 1/18 (MLK Day 2025-01-20).
Recommendation: HDBSCAN for the automatic exclusion this was built to
support - same recall, no false positives on days that are already handled
some other way (is_us_holiday).

Usage:
  python model/anomaly_detection_experiment.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.cluster import HDBSCAN
from sklearn.preprocessing import StandardScaler

OUT_DIR = Path(__file__).resolve().parent

# The ground truth this script evaluates against - same dates as
# train_xgboost_baseline.EXCLUDED_PERIODS/train_lstm.EXCLUDED_PERIODS,
# duplicated rather than imported (same reasoning those two already give for
# not sharing with each other: importing from train_xgboost_baseline would
# also drag in its xgboost dependency for a script that otherwise needs only
# pandas/scikit-learn - keep both lists in sync if this is ever revised).
EXCLUDED_PERIODS = [
    ("2021-02-12", "2021-02-20"),  # Winter Storm Uri (incl. Presidents' Day)
    ("2022-12-23", "2022-12-25"),  # Winter Storm Elliott (incl. Christmas)
    ("2023-06-19", "2023-06-21"),  # Juneteenth 2023 scarcity event
    ("2024-01-15", "2024-01-17"),  # MLK Day 2024 cold-snap scarcity event
    ("2024-05-24", "2024-05-28"),  # Memorial Day 2024 scarcity event
]

RAW_PRICE_COL = "lz_north_price"
BASELINE_WEEKS = 8
SPIKE_THRESHOLD_USD = 200.0
Z_SCORE_THRESHOLD = 3.0

# Ordinary holidays that happen to share a calendar slot with an
# EXCLUDED_PERIODS event, in a DIFFERENT (non-emergency) year - the false-
# positive control set. Picked by what each event's date overlaps:
# Elliott/Juneteenth/MLK/Memorial Day all land on or next to a federal
# holiday; Uri does not (Presidents' Day is incidental to it, not its cause),
# so there's no non-emergency "Uri-shaped" control day to check.
CONTROL_HOLIDAYS = {
    "Christmas (non-emergency years)": [f"{y}-12-25" for y in (2020, 2021, 2023, 2024, 2025) ],
    "Juneteenth (non-emergency years)": [f"{y}-06-19" for y in (2020, 2021, 2022, 2024, 2025)],
    "MLK Day (non-emergency years)": ["2021-01-18", "2022-01-17", "2023-01-16", "2025-01-20"],
    "Memorial Day (non-emergency years)": ["2021-05-31", "2022-05-30", "2023-05-29", "2025-05-26"],
}


def load_daily_frame(data_dir: Path) -> pd.DataFrame:
    parts = [pd.read_csv(data_dir / f"{name}.csv", parse_dates=["time"]) for name in ("train", "val")]
    hourly = pd.concat(parts, ignore_index=True).sort_values("time").set_index("time")[RAW_PRICE_COL].asfreq("h")
    daily = hourly.groupby(hourly.index.floor("D")).agg(
        mean="mean", std="std", max="max", min="min",
        frac_spike=lambda s: float((s > SPIKE_THRESHOLD_USD).mean()),
    )
    daily["range"] = daily["max"] - daily["min"]
    return daily


def statistical_flags(daily: pd.DataFrame, baseline_weeks: int = BASELINE_WEEKS,
                      threshold: float = Z_SCORE_THRESHOLD) -> pd.Series:
    """z-score of each day's mean price against the same weekday's trailing
    `baseline_weeks` occurrences (a seasonal-naive baseline, not a flat
    trailing average - Tuesdays and Saturdays have different normal price
    shapes, so comparing a Tuesday only to other Tuesdays avoids flagging
    every Saturday as "anomalous" relative to a weekday-dominated average)."""
    by_weekday = daily.groupby(daily.index.dayofweek)["mean"]
    baseline_mean = by_weekday.transform(lambda s: s.shift(1).rolling(baseline_weeks, min_periods=3).mean())
    baseline_std = by_weekday.transform(lambda s: s.shift(1).rolling(baseline_weeks, min_periods=3).std())
    z = (daily["mean"] - baseline_mean) / baseline_std.replace(0, np.nan)
    return z.abs() > threshold


def hdbscan_flags(daily: pd.DataFrame, min_cluster_size: int = 10) -> pd.Series:
    features = daily[["mean", "std", "max", "range", "frac_spike"]].dropna()
    scaled = StandardScaler().fit_transform(features)
    labels = HDBSCAN(min_cluster_size=min_cluster_size, copy=True).fit_predict(scaled)
    return pd.Series(labels == -1, index=features.index).reindex(daily.index, fill_value=False)


def days_in(start: str, end: str) -> pd.DatetimeIndex:
    return pd.date_range(start, pd.Timestamp(end) , freq="D")


def evaluate(flags: pd.Series, label: str) -> None:
    print(f"\n--- {label}: {int(flags.sum())} day(s) flagged out of {len(flags)} ({flags.mean():.1%}) ---")

    print("Recall on known grid-emergency events (EXCLUDED_PERIODS):")
    hits = 0
    for start, end in EXCLUDED_PERIODS:
        window = days_in(start, end)
        caught = flags.reindex(window, fill_value=False)
        hit = bool(caught.any())
        hits += hit
        flagged_dates = [d.date().isoformat() for d, v in caught.items() if v]
        print(f"  {start}..{end}: {'CAUGHT' if hit else 'MISSED'}"
             f"{' (' + ', '.join(flagged_dates) + ')' if flagged_dates else ''}")
    print(f"  -> {hits}/{len(EXCLUDED_PERIODS)} events caught")

    print("False positives on ordinary (non-emergency) holidays:")
    total_fp, total_checked = 0, 0
    for group, dates in CONTROL_HOLIDAYS.items():
        in_range = [d for d in dates if pd.Timestamp(d) in flags.index]
        fp = [d for d in in_range if flags.get(pd.Timestamp(d), False)]
        total_fp += len(fp)
        total_checked += len(in_range)
        print(f"  {group}: {len(fp)}/{len(in_range)} flagged" + (f" ({', '.join(fp)})" if fp else ""))
    print(f"  -> {total_fp}/{total_checked} ordinary holidays incorrectly flagged")


def main() -> None:
    data_dir = OUT_DIR
    daily = load_daily_frame(data_dir)
    print(f"{len(daily)} days, {daily.index.min().date()} .. {daily.index.max().date()}")

    evaluate(statistical_flags(daily), "Statistical (seasonal-naive z-score)")
    evaluate(hdbscan_flags(daily), "HDBSCAN (density clustering)")


if __name__ == "__main__":
    main()
