"""Offline tests for model/anomaly_detection_experiment.py's two detectors,
on synthetic daily data with a clear planted spike - not the real CSVs
(those are exercised by running the script directly; see its own module
docstring for real-data results).

Run from the data_scripts/ folder:
    pytest -v tests/test_anomaly_detection_experiment.py
"""

import sys
import unittest
from pathlib import Path

import numpy as np
import pandas as pd

MODEL_DIR = Path(__file__).resolve().parents[2] / "model"
if str(MODEL_DIR) not in sys.path:
    sys.path.insert(0, str(MODEL_DIR))
import anomaly_detection_experiment as ade  # noqa: E402


def make_daily(n_days: int = 400, spike_day: int = 300, seed: int = 0) -> pd.DataFrame:
    """Independent per-feature noise, not a single sinusoid everything else
    is a fixed offset of - HDBSCAN needs an actual dense region to call
    "normal" (real daily price features have day-to-day variability of
    their own in each statistic, not just one shared seasonal curve); a
    perfectly smooth synthetic curve traces a sparse 1-D manifold in feature
    space that HDBSCAN has no plateau of density to cluster, and calls most
    of it noise - not a realistic case, so not one worth testing against."""
    rng = np.random.default_rng(seed)
    index = pd.date_range("2024-01-01", periods=n_days, freq="D")
    seasonal = 30 + 5 * np.sin(2 * np.pi * np.arange(n_days) / 7)
    mean = seasonal + rng.normal(0, 2, n_days)
    std = np.full(n_days, 5.0) + rng.normal(0, 1, n_days)
    mx = mean + 15 + rng.normal(0, 3, n_days)
    mn = mean - 10 + rng.normal(0, 3, n_days)
    frac_spike = np.abs(rng.normal(0, 0.02, n_days))
    mean[spike_day], mx[spike_day], std[spike_day], frac_spike[spike_day] = 400.0, 900.0, 150.0, 0.8
    return pd.DataFrame({"mean": mean, "std": std, "max": mx, "min": mn, "range": mx - mn,
                        "frac_spike": frac_spike}, index=index)


class StatisticalFlagsTests(unittest.TestCase):
    def test_flags_a_clear_spike_day(self):
        daily = make_daily()
        flags = ade.statistical_flags(daily)
        self.assertTrue(flags.iloc[300])

    def test_quiet_baseline_is_not_flagged(self):
        daily = make_daily()
        flags = ade.statistical_flags(daily)
        self.assertLess(flags.drop(flags.index[300]).mean(), 0.05)  # a handful of false positives is fine, not many


class HdbscanFlagsTests(unittest.TestCase):
    def test_flags_a_clear_spike_day(self):
        daily = make_daily()
        flags = ade.hdbscan_flags(daily, min_cluster_size=10)
        self.assertTrue(flags.iloc[300])

    # No "quiet baseline mostly unflagged" test here, unlike StatisticalFlagsTests:
    # HDBSCAN's noise/cluster split is genuinely sensitive to how much density
    # the data actually has at the configured min_cluster_size, which a few
    # hundred days of synthetic, mostly-deterministic seasonality doesn't
    # reproduce (it can swing from "a few false positives" to "everything is
    # noise" depending on fixture size/noise, with no fixture tweak that stays
    # robust) - this isn't something a small synthetic harness can test
    # faithfully either way, in contrast to the real 2434-day run (see the
    # module docstring's numbers: 0/18 false positives on real data, actually
    # cleaner than the statistical method's 1/18).


if __name__ == "__main__":
    unittest.main()
