"""
LEAR (Lasso Estimated AutoRegressive) forecaster for LZ_NORTH DAM price - the
standard day-ahead price benchmark of Lago et al. (2021), "Forecasting
day-ahead electricity prices: A review of state-of-the-art algorithms, best
practices and an open-access benchmark", Applied Energy 293, 116983.
Implemented against our hourly UTC frames; feature layout and transform follow
epftoolbox's LEAR class.

For target day D (hours D 00:00..23:00 UTC), one row per day:
  - past prices: all 24 hours of D-1, D-2, D-3, D-7;
  - each hourly exogenous column: all 24 hours of D (known ahead), D-1, D-7
    (solar: D only unless solar_lags=True);
  - each daily exogenous column (gas): its value for D;
  - calendar: day-of-week one-hot, is_us_holiday of D.
24 LASSO models (one per hour of D) share that feature row. Each is fitted by
LARS with the regularisation picked by AIC, on a rolling calibration window of
the most recent N days; the forecast is the plain average over the windows in
WINDOWS. Prices and exogenous inputs go through a median/MAD + asinh transform
fitted on each calibration window, which keeps spikes from dominating the fit.

Information cutoff: price is only ever read before D 00:00 (before
D-(horizon_days-1) 00:00 in general) - whatever the frame holds at or after it
is discarded before any feature is built. No precomputed lag or rolling-window
column is used; the CSV's rolling columns include the target hour
(model/model.md S8.2).

horizon_days > 1 forecasts further out: the newest price day used is
D-horizon_days, and an exogenous day profile is only used if it is published
by then (KNOWN_AHEAD_DAYS - the DAM load forecast only exists one day ahead).
An exogenous group that is absent or has gaps for the target day is dropped
(and logged) rather than failing the forecast.

To add a feature (e.g. battery data): append its column name to EXOG_COLS, or
pass exog_cols=[*EXOG_COLS, ...].
"""

from __future__ import annotations

import logging
import warnings

import numpy as np
import pandas as pd
from joblib import Parallel, delayed
from sklearn.linear_model import LassoLarsIC
from sklearn.preprocessing import StandardScaler

logger = logging.getLogger("lear_model")

RAW_PRICE_COL = "lz_north_price"
EXOG_COLS = [
    "load_forecast_dam_north_mwh",
    "wind_power_output_proxy_total",
    "solar_power_output_proxy_total",
    "henry_hub_price_usd_mmbtu_lag1d",
]
SOLAR_COL = "solar_power_output_proxy_total"
# One value per day rather than a 24-hour profile.
DAILY_COLS = ("henry_hub_price_usd_mmbtu_lag1d",)
HOLIDAY_COL = "is_us_holiday"
# Days past the newest price day for which a column is already published.
# Columns not listed are treated as known for the target day at any horizon.
KNOWN_AHEAD_DAYS = {"load_forecast_dam_north_mwh": 1, "henry_hub_price_usd_mmbtu_lag1d": 1}

WINDOWS = (56, 84, 1092, 1456)  # calibration windows, days: 8 weeks, 12 weeks, 3 years, 4 years
EXOG_DAY_OFFSETS = (0, 1, 7)
MIN_TRAIN_DAYS = 28  # a window left with fewer usable days than this is skipped
MAD_SCALE = 1.4826
LARS_MAX_ITER = 2500
# Standardised inputs are held within this many standard deviations of their
# calibration-window mean. A column that barely moved in the window (solar at
# an hour that was still dark) can otherwise land hundreds of deviations out
# on a later day, and sinh() turns that into a forecast of 1e20 $/MWh.
FEATURE_CLIP_SIGMA = 5.0
PRICE = "price"  # name of the price variable in feature names / transform params


class MissingInputError(ValueError):
    """A required model input is missing for the requested day."""


# --------------------------------------------------------------------------- #
# Variance-stabilising transform
# --------------------------------------------------------------------------- #

def fit_transform_params(values) -> tuple[float, float]:
    """(median, scaled MAD) of `values`. Falls back to the standard deviation,
    then to 1, when the MAD is zero (e.g. solar output: zero more than half
    the time)."""
    values = np.asarray(values, dtype=float)
    values = values[np.isfinite(values)]
    median = float(np.median(values))
    scale = MAD_SCALE * float(np.median(np.abs(values - median)))
    if scale < 1e-12:
        scale = float(values.std())
    return median, scale if scale >= 1e-12 else 1.0


def transform(x, median: float, scale: float):
    return np.arcsinh((np.asarray(x, dtype=float) - median) / scale)


def inverse_transform(z, median: float, scale: float):
    return np.sinh(np.asarray(z, dtype=float)) * scale + median


# --------------------------------------------------------------------------- #
# Design matrix
# --------------------------------------------------------------------------- #

def price_day_offsets(horizon_days: int) -> list[int]:
    """Past-price days as offsets before the target day: the three newest known
    days plus the same weekday. [1, 2, 3, 7] for a day-ahead forecast."""
    newest = [horizon_days, horizon_days + 1, horizon_days + 2]
    return [*newest, 7 * (newest[-1] // 7 + 1)]


def _daily_matrix(series: pd.Series, days: pd.DatetimeIndex) -> np.ndarray:
    """Hourly series -> (n_days, 24), NaN where an hour is absent."""
    hours = pd.date_range(days[0], periods=len(days) * 24, freq="h")
    return series.reindex(hours).to_numpy(dtype=float).reshape(len(days), 24)


def _shift_days(matrix: np.ndarray, k: int) -> np.ndarray:
    """Row i of the result is row i-k of `matrix` (NaN where that is before the start)."""
    out = np.full_like(matrix, np.nan)
    if k < len(matrix):
        out[k:] = matrix[:len(matrix) - k]
    return out


class Design:
    """One row per day, the last row being the target day.

    X (n_days, n_features) raw feature values, Y (n_days, 24) that day's prices
    (NaN from the price cutoff on), names/variables one entry per feature
    column - variables holds the transform each column takes (PRICE, an
    exogenous column name, or None for calendar dummies)."""

    def __init__(self, days, X, Y, names, variables, groups, first_complete):
        self.days, self.X, self.Y = days, X, Y
        self.names, self.variables, self.groups = names, variables, groups
        self.first_complete = first_complete  # earliest day whose longest lag is inside the data

    def keep(self, mask: np.ndarray) -> None:
        self.X = self.X[:, mask]
        self.names = [n for n, m in zip(self.names, mask) if m]
        self.variables = [v for v, m in zip(self.variables, mask) if m]
        self.groups = [g for g, m in zip(self.groups, mask) if m]


def build_design(hourly_df: pd.DataFrame, day, exog_cols=EXOG_COLS, price_col: str = RAW_PRICE_COL,
                 horizon_days: int = 1, solar_lags: bool = False, max_history_days: int | None = None,
                 log=None) -> Design:
    """Feature rows for every day up to and including the target `day`.

    `hourly_df` is indexed by naive-UTC hour. Price at or after the cutoff
    (`day` 00:00 for horizon_days=1) is discarded here, before anything is
    built from it. Exogenous columns missing from the frame, or not published
    yet at this horizon, are left out and reported through `log`."""
    day = pd.Timestamp(day).normalize()
    cutoff = day - pd.Timedelta(days=horizon_days - 1)
    note = log or (lambda message: None)

    price = hourly_df[price_col]
    price = price[price.index < cutoff]
    if price.empty:
        raise MissingInputError(f"no price history before {cutoff}")
    if price.index.max() >= cutoff:  # the cutoff guarantee everything below relies on
        raise AssertionError(f"price at or after the {cutoff} cutoff reached the LEAR design matrix")

    offsets = price_day_offsets(horizon_days)
    first = hourly_df.index.min().ceil("D")
    if max_history_days is not None:
        first = max(first, day - pd.Timedelta(days=max_history_days + offsets[-1]))
    days = pd.date_range(first, day, freq="D")

    blocks, names, variables, groups = [], [], [], []

    def add(block: np.ndarray, group: str, variable, labels: list[str]) -> None:
        blocks.append(block)
        names.extend(labels)
        variables.extend([variable] * len(labels))
        groups.extend([group] * len(labels))

    prices = _daily_matrix(price, days)
    for k in offsets:
        add(_shift_days(prices, k), f"{PRICE} D-{k}", PRICE, [f"{PRICE}_d{k}_h{h:02d}" for h in range(24)])

    for col in exog_cols:
        if col not in hourly_df.columns:
            note(f"exogenous column {col} is not in the data - left out")
            continue
        values = _daily_matrix(hourly_df[col], days)
        # Day D-k is published once it is at most `lead` days past the newest price day D-horizon.
        earliest = horizon_days - KNOWN_AHEAD_DAYS.get(col, horizon_days)
        if col in DAILY_COLS:
            k = max(0, earliest)
            add(_shift_days(values[:, :1], k), f"{col} D-{k}", col, [f"{col}_d{k}"])
            continue
        wanted = (0,) if col == SOLAR_COL and not solar_lags else EXOG_DAY_OFFSETS
        usable = [k for k in wanted if k >= earliest]
        if len(usable) < len(wanted):
            unpublished = ", ".join(f"D-{k}" for k in wanted if k not in usable)
            note(f"{col}: {unpublished} not published {horizon_days} day(s) ahead - left out")
        for k in usable:
            add(_shift_days(values, k), f"{col} D-{k}", col, [f"{col}_d{k}_h{h:02d}" for h in range(24)])

    weekday = days.dayofweek.to_numpy()
    add((weekday[:, None] == np.arange(7)).astype(float), "day of week", None, [f"dow_{d}" for d in range(7)])
    if HOLIDAY_COL in hourly_df.columns:
        holiday = _daily_matrix(hourly_df[HOLIDAY_COL], days)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", RuntimeWarning)  # all-NaN day -> NaN
            add(np.nanmax(holiday, axis=1, keepdims=True), HOLIDAY_COL, None, [HOLIDAY_COL])
    else:
        note(f"{HOLIDAY_COL} is not in the data - left out")

    return Design(days, np.hstack(blocks), prices, names, variables, groups,
                  first_complete=first + pd.Timedelta(days=offsets[-1]))


# --------------------------------------------------------------------------- #
# Fitting
# --------------------------------------------------------------------------- #

def _fit_hours(X: np.ndarray, Y: np.ndarray) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """One LASSO per column of Y. Returns (coef (n_hours, n_features), intercept, alpha).

    noise_variance = var(y) is the AIC that LEAR was defined with (the
    scikit-learn LassoLarsIC criterion at the time of Lago et al.); it also
    stays defined when a short window has fewer days than features, where the
    current scikit-learn default (OLS residual variance) does not exist."""
    coef = np.zeros((Y.shape[1], X.shape[1]))
    intercept, alpha = Y.mean(axis=0), np.zeros(Y.shape[1])
    for h in range(Y.shape[1]):
        variance = float(Y[:, h].var())
        if variance < 1e-12:  # constant target: the mean is the model
            continue
        model = LassoLarsIC(criterion="aic", max_iter=LARS_MAX_ITER, noise_variance=variance)
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")  # LARS reports collinear regressors it drops; expected here
            model.fit(X, Y[:, h])
        coef[h], intercept[h], alpha[h] = model.coef_, model.intercept_, model.alpha_
    return coef, intercept, alpha


class LEARForecaster:
    """Day-ahead LEAR ensemble. predict_day() recalibrates as needed, so there
    is nothing to train up front and nothing random: same inputs, same forecast."""

    def __init__(self, windows=WINDOWS, exog_cols=EXOG_COLS, recal_every: int = 1, horizon_days: int = 1,
                 price_col: str = RAW_PRICE_COL, solar_lags: bool = False, n_jobs: int = 1):
        if recal_every < 1 or horizon_days < 1:
            raise ValueError("recal_every and horizon_days must be >= 1")
        self.windows = tuple(sorted(windows))
        self.exog_cols = list(exog_cols)
        self.recal_every, self.horizon_days = recal_every, horizon_days
        self.price_col, self.solar_lags, self.n_jobs = price_col, solar_lags, n_jobs
        self.fitted = None  # {"day", "names", "windows": {days: window model}}
        self.dropped_groups: list[str] = []  # exogenous groups left out of the latest forecast
        self._logged: set[str] = set()

    def _log(self, message: str) -> None:
        """Daily recalibration repeats the same notes for months - say each once."""
        if message not in self._logged:
            self._logged.add(message)
            logger.info(message)

    def _design(self, hourly_df: pd.DataFrame, day: pd.Timestamp) -> Design:
        """Design matrix for `day`, minus the exogenous groups that have gaps on the target row."""
        design = build_design(hourly_df, day, self.exog_cols, self.price_col, self.horizon_days,
                              self.solar_lags, max_history_days=self.windows[-1] + self.horizon_days,
                              log=self._log)
        gaps = np.isnan(design.X[-1])
        bad = list(dict.fromkeys(g for g, gap in zip(design.groups, gaps) if gap))
        missing_price = [g for g in bad if g.startswith(PRICE)]
        if missing_price:
            raise MissingInputError(f"missing price history for {day.date()}: {', '.join(missing_price)}")
        if bad:
            self._log(f"{day.date()}: missing for the target day, left out: {', '.join(bad)}")
            design.keep(np.array([g not in bad for g in design.groups]))
        self.dropped_groups = bad
        return design

    def fit(self, hourly_df: pd.DataFrame, day) -> "LEARForecaster":
        """Calibrate every window on the days before `day` whose prices are known at the cutoff."""
        day = pd.Timestamp(day).normalize()
        return self._fit(self._design(hourly_df, day), day)

    def _fit(self, design: Design, day: pd.Timestamp) -> "LEARForecaster":
        last_train = day - pd.Timedelta(days=self.horizon_days)
        complete = ~np.isnan(design.X).any(axis=1) & ~np.isnan(design.Y).any(axis=1)

        prepared = {}
        for window in self.windows:
            start = last_train - pd.Timedelta(days=window - 1)
            if start < design.first_complete:
                self._log(f"{window}-day window skipped: history only reaches back to "
                          f"{design.first_complete.date()}")
                continue
            in_window = (design.days >= start) & (design.days <= last_train)
            rows = in_window & complete
            n_dropped = int(in_window.sum() - rows.sum())
            if n_dropped:
                self._log(f"{window}-day window: dropped {n_dropped} day(s) with missing inputs")
            if rows.sum() < MIN_TRAIN_DAYS:
                self._log(f"{window}-day window skipped: only {int(rows.sum())} usable day(s)")
                continue
            X, Y = design.X[rows], design.Y[rows]
            params = {PRICE: fit_transform_params(Y)}
            for variable in dict.fromkeys(v for v in design.variables if v not in (None, PRICE)):
                params[variable] = fit_transform_params(X[:, [v == variable for v in design.variables]])
            scaler = StandardScaler().fit(self._transform_inputs(X, design.variables, params))
            model = {"params": params, "mean": scaler.mean_, "scale": scaler.scale_,
                     "n_train": int(rows.sum()), "n_dropped": n_dropped}
            prepared[window] = {**model, "X": self._model_inputs(X, design.variables, model),
                                "Y": transform(Y, *params[PRICE])}
        if self.windows[0] not in prepared:
            raise MissingInputError(f"not enough history before {day.date()} for the "
                                    f"{self.windows[0]}-day calibration window")

        # 24 hour-models per window, a few hours per task.
        tasks = [(window, hours) for window in prepared for hours in np.array_split(np.arange(24), 4)]
        results = Parallel(n_jobs=self.n_jobs)(
            delayed(_fit_hours)(prepared[window]["X"], prepared[window]["Y"][:, hours]) for window, hours in tasks)
        for window, model in prepared.items():
            parts = [r for (w, _), r in zip(tasks, results) if w == window]
            model["coef"], model["intercept"], model["alpha"] = (np.concatenate(p) for p in zip(*parts))
            del model["X"], model["Y"]
        self.fitted = {"day": day, "names": list(design.names), "windows": prepared}
        return self

    @staticmethod
    def _transform_inputs(X: np.ndarray, variables: list, params: dict) -> np.ndarray:
        out = X.astype(float, copy=True)
        for variable, (median, scale) in params.items():
            cols = [v == variable for v in variables]
            out[:, cols] = transform(out[:, cols], median, scale)
        return out

    @classmethod
    def _model_inputs(cls, X: np.ndarray, variables: list, model: dict) -> np.ndarray:
        """Raw feature rows -> what the hour-models are fitted on and fed: transformed, standardised, clipped."""
        z = (cls._transform_inputs(X, variables, model["params"]) - model["mean"]) / model["scale"]
        return np.clip(z, -FEATURE_CLIP_SIGMA, FEATURE_CLIP_SIGMA)

    def _needs_fit(self, design: Design, day: pd.Timestamp) -> bool:
        """Refit unless a calibration from one of the previous recal_every-1
        days is at hand for the same features. A repeat forecast of the same
        day refits too, so it never depends on what was asked before."""
        if self.fitted is None or self.fitted["names"] != design.names:
            return True
        return not 0 < (day - self.fitted["day"]).days < self.recal_every

    def predict_day(self, hourly_df: pd.DataFrame, day) -> np.ndarray:
        """24 prices ($/MWh) for the UTC day starting at `day`.

        hourly_df: hourly naive-UTC frame with the price column plus the
        exogenous and calendar columns. Price at or after the cutoff is never
        read, whatever the frame holds there."""
        day = pd.Timestamp(day).normalize()
        design = self._design(hourly_df, day)
        if self._needs_fit(design, day):
            self._fit(design, day)

        forecasts = []
        for model in self.fitted["windows"].values():
            z = self._model_inputs(design.X[-1:], design.variables, model) @ model["coef"].T + model["intercept"]
            forecasts.append(inverse_transform(z[0], *model["params"][PRICE]))
        return np.mean(forecasts, axis=0)

    @property
    def windows_used(self) -> list[int]:
        return list(self.fitted["windows"]) if self.fitted else []
