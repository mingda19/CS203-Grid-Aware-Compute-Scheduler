"""
Seq2seq LSTM for LZ_NORTH DAM price, plus the input preparation it needs.

Shared by model/train_lstm.py (training), model/evaluate.py (evaluation) and
data_scripts/predictions.py (serving): all three build model inputs through
assemble_inputs() below, so the information cutoff, clipping, target
transform and scaling cannot drift between training and serving.

Information cutoff for target day D (hours D 00:00..23:00 UTC):
  - price: actuals up to and including D-1 23:00 only (the encoder window);
  - exogenous + calendar columns: the encoder window AND the 24 hours of D
    ("known-ahead" values: DAM load forecast, weather-derived proxies, gas).
No precomputed rolling/lag column is used - the CSV's roll_* columns include
the target hour (model/model.md S8.2), so the encoder sees raw price history
instead.

Everything needed to rebuild inputs lives in the saved artifact (exog_cols,
calendar_cols, scalers, price_clip, target_transform), so adding a feature
only means appending its column name to EXOG_COLS in train_lstm.py.
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pandas as pd
import torch
from torch import nn

PAST_WINDOW = 168
HORIZON = 24
PRICE_COL = "price"
DEFAULT_QUANTILES = [0.1, 0.5, 0.9]
CALENDAR_COLS = ["hour_sin", "hour_cos", "dow_sin", "dow_cos", "month_sin", "month_cos", "is_us_holiday"]
# Longest price gap (hours) that serving/evaluation will forward-fill.
MAX_PRICE_FFILL_HOURS = 3
PRICE_CLIP_QUANTILES = (0.005, 0.995)


class MissingInputError(ValueError):
    """A required model input is missing for the requested day."""


# --------------------------------------------------------------------------- #
# Target transform
# --------------------------------------------------------------------------- #

def signed_log1p(x):
    return np.sign(x) * np.log1p(np.abs(x))


def signed_expm1(y):
    return np.sign(y) * np.expm1(np.abs(y))


TRANSFORMS = {"signed_log1p": (signed_log1p, signed_expm1)}


# --------------------------------------------------------------------------- #
# Input preparation
# --------------------------------------------------------------------------- #

def add_cyclic_calendar(df: pd.DataFrame) -> pd.DataFrame:
    """Add CALENDAR_COLS from the hour/day_of_week/month/is_us_holiday columns
    (as produced by predictions.add_calendar_features, and present in
    train.csv/val.csv)."""
    df = df.copy()
    for name, col, period in [("hour", "hour", 24), ("dow", "day_of_week", 7), ("month", "month", 12)]:
        offset = 1 if col == "month" else 0
        angle = 2 * np.pi * (df[col] - offset) / period
        df[f"{name}_sin"] = np.sin(angle)
        df[f"{name}_cos"] = np.cos(angle)
    return df


def past_feature_names(cfg: dict) -> list[str]:
    return [PRICE_COL, *cfg["exog_cols"], *cfg["calendar_cols"]]


def future_feature_names(cfg: dict) -> list[str]:
    return [*cfg["exog_cols"], *cfg["calendar_cols"]]


def fit_preprocessing(frame: pd.DataFrame, exog_cols: list[str], calendar_cols: list[str],
                      target_transform: str = "signed_log1p") -> dict:
    """Clip bounds and per-feature (mean, std) from `frame` - pass the fitting
    rows only. Returned as plain floats so the artifact holds no pickled
    objects."""
    forward, _ = TRANSFORMS[target_transform]
    lo, hi = (float(frame[PRICE_COL].quantile(q)) for q in PRICE_CLIP_QUANTILES)
    columns = {PRICE_COL: forward(frame[PRICE_COL].clip(lo, hi))}
    columns.update({c: frame[c] for c in [*exog_cols, *calendar_cols]})
    scalers = {}
    for name, values in columns.items():
        std = float(values.std(ddof=0))
        scalers[name] = (float(values.mean()), std if std > 1e-8 else 1.0)
    return {"price_clip": (lo, hi), "scalers": scalers, "target_transform": target_transform}


def _scaled_price(price: pd.Series, cfg: dict) -> pd.Series:
    forward, _ = TRANSFORMS[cfg["target_transform"]]
    mean, std = cfg["scalers"][PRICE_COL]
    return (forward(price.clip(*cfg["price_clip"])) - mean) / std


def assemble_inputs(frame: pd.DataFrame, day_start, cfg: dict,
                    price_ffill_limit: int = MAX_PRICE_FFILL_HOURS, log=None) -> tuple[np.ndarray, np.ndarray]:
    """Scaled encoder/decoder inputs for the day starting at `day_start`.

    `frame` is indexed by naive-UTC hour and holds PRICE_COL ($/MWh, unscaled)
    plus cfg's exog and calendar columns in their raw units. Returns
    (past, future) float32 arrays shaped (past_window, n_past) and
    (horizon, n_future).

    Price is only ever read from the encoder window, i.e. strictly before
    `day_start` - whatever `frame` holds at or after it is ignored.
    Raises MissingInputError naming every input that has missing hours.
    """
    day_start = pd.Timestamp(day_start)
    past_idx = pd.date_range(end=day_start - pd.Timedelta(hours=1), periods=cfg["past_window"], freq="h")
    future_idx = pd.date_range(day_start, periods=cfg["horizon"], freq="h")
    known_ahead = future_feature_names(cfg)

    absent = [c for c in [PRICE_COL, *known_ahead] if c not in frame.columns]
    if absent:
        raise MissingInputError(f"inputs not provided at all: {absent}")

    price = frame[PRICE_COL].reindex(past_idx)
    n_gaps = int(price.isna().sum())
    if n_gaps and price_ffill_limit:
        price = price.ffill(limit=price_ffill_limit)
        if log is not None and price.notna().all():
            log.warning("Forward-filled %d missing price hour(s) in the %dh history before %s",
                        n_gaps, cfg["past_window"], day_start)

    past = frame[known_ahead].reindex(past_idx)
    past.insert(0, PRICE_COL, price)
    future = frame[known_ahead].reindex(future_idx)

    missing = {f"{c} (history)": int(n) for c, n in past.isna().sum().items() if n}
    missing.update({f"{c} (target day)": int(n) for c, n in future.isna().sum().items() if n})
    if missing:
        detail = ", ".join(f"{name}: {n}h" for name, n in missing.items())
        raise MissingInputError(f"missing inputs for {day_start.date()} - {detail}")

    past[PRICE_COL] = _scaled_price(past[PRICE_COL], cfg)
    for col in known_ahead:
        mean, std = cfg["scalers"][col]
        past[col] = (past[col] - mean) / std
        future[col] = (future[col] - mean) / std
    return past.to_numpy(dtype=np.float32), future.to_numpy(dtype=np.float32)


def target_array(frame: pd.DataFrame, day_start, cfg: dict) -> np.ndarray:
    """Scaled, transformed prices of the day starting at `day_start` (NaN where
    the actual is missing)."""
    idx = pd.date_range(pd.Timestamp(day_start), periods=cfg["horizon"], freq="h")
    return _scaled_price(frame[PRICE_COL].reindex(idx), cfg).to_numpy(dtype=np.float32)


def inverse_target(scaled: np.ndarray, cfg: dict) -> np.ndarray:
    """Model output space -> $/MWh."""
    _, inverse = TRANSFORMS[cfg["target_transform"]]
    mean, std = cfg["scalers"][PRICE_COL]
    return inverse(scaled * std + mean)


# --------------------------------------------------------------------------- #
# Model
# --------------------------------------------------------------------------- #

class LSTMForecaster(nn.Module):
    """Encoder LSTM over the past window; decoder LSTM over the known-ahead
    inputs of the target day, initialised from the encoder's final state.
    Non-autoregressive: no predicted price is fed back in."""

    def __init__(self, n_past_feats: int, n_future_feats: int, hidden: int = 128, layers: int = 2,
                 dropout: float = 0.2, quantiles: list[float] | None = None):
        super().__init__()
        self.quantiles = list(quantiles or DEFAULT_QUANTILES)
        self.encoder = nn.LSTM(n_past_feats, hidden, num_layers=layers,
                               dropout=dropout if layers > 1 else 0.0, batch_first=True)
        self.decoder = nn.LSTM(n_future_feats, hidden, num_layers=1, batch_first=True)
        self.head = nn.Linear(hidden, len(self.quantiles))

    def forward(self, past: torch.Tensor, future: torch.Tensor) -> torch.Tensor:
        _, (h, c) = self.encoder(past)
        out, _ = self.decoder(future, (h[-1:].contiguous(), c[-1:].contiguous()))
        return self.head(out)  # (batch, horizon, n_quantiles)


def pinball_loss(pred: torch.Tensor, target: torch.Tensor, quantiles: list[float]) -> torch.Tensor:
    """Sum over quantiles, mean over batch and horizon."""
    q = torch.tensor(quantiles, dtype=pred.dtype, device=pred.device)
    diff = target.unsqueeze(-1) - pred
    return torch.maximum(q * diff, (q - 1) * diff).sum(dim=-1).mean()


def predict_quantiles(model: LSTMForecaster, past, future, cfg: dict) -> np.ndarray:
    """$/MWh predictions shaped (batch, horizon, n_quantiles), in the order of
    cfg["arch"]["quantiles"], sorted so quantiles never cross."""
    model.eval()
    device = next(model.parameters()).device
    with torch.no_grad():
        out = model(torch.as_tensor(past, dtype=torch.float32, device=device),
                    torch.as_tensor(future, dtype=torch.float32, device=device))
    out = torch.sort(out, dim=-1).values.cpu().numpy()
    return inverse_target(out, cfg)


# --------------------------------------------------------------------------- #
# Artifact
# --------------------------------------------------------------------------- #

def build_model(artifact: dict) -> LSTMForecaster:
    arch = artifact["arch"]
    model = LSTMForecaster(arch["n_past_feats"], arch["n_future_feats"], hidden=arch["hidden"],
                           layers=arch["layers"], dropout=arch["dropout"], quantiles=arch["quantiles"])
    model.load_state_dict(artifact["state_dict"])
    return model.eval()


def save_artifact(path: Path, artifact: dict) -> None:
    torch.save(artifact, path)


def load_artifact(path: Path) -> tuple[LSTMForecaster, dict]:
    # weights_only: the artifact is tensors + plain Python values by design, so
    # loading never has to unpickle arbitrary objects.
    artifact = torch.load(path, map_location="cpu", weights_only=True)
    if artifact.get("kind") != "lstm":
        raise ValueError(f"{path} is not an LSTM artifact (kind={artifact.get('kind')!r})")
    return build_model(artifact), artifact
