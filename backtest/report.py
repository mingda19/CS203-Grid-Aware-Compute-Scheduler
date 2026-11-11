"""
Write the backtest report (docs/backtest-plan.md ticket 3) from the runs backtest/run_report.sh makes:

  <runs>/main                 headline run: four profiles, LEAR + two naive forecasts + perfect foresight
  <runs>/a2-flexibility       mixed with flexibility fixed at 0-24 h
  <runs>/a3-{cpu,gpu}-kwh-*   kWh factors halved and doubled
  <runs>/a4-capacity-*        facility capacity halved and doubled
  <runs>/a8-heuristic         the heuristic alone

Output: <out>/backtest-<date>.md and charts in <out>/backtest-<date>/. Every number is read from the run files,
whose manifests (commit, input hashes, settings) are listed in the report's Method section.

Usage (from the repo root; run_report.sh calls it):
  .venv/bin/python3 backtest/report.py --runs backtest/output/report --prices backtest/data/y0/actual_prices.csv \
      --forecast-eval backtest/data/walkforward_y0 --out docs/reports
"""

from __future__ import annotations

import argparse
import json
import math
from datetime import date
from pathlib import Path

import numpy as np
import pandas as pd

REPO = Path(__file__).resolve().parent.parent

PROFILES = ["business-hours-training", "mixed", "nightly-batch", "tight"]
REALISTIC = ["business-hours-training", "mixed", "nightly-batch"]  # tight is a deliberate lower bound
SOURCES = ["perfect", "lear", "naive_last_known", "naive_w1"]
LABEL = {"perfect": "Perfect foresight", "lear": "LEAR (served model)",
         "naive_last_known": "Naive: last known day", "naive_w1": "Naive: same day last week"}
TARGET = (15.0, 25.0)

# Thresholds for the assumption verdicts. A3 and A8 come from the plan; the others are this report's reading of
# the plan's wording ("near 0", "rare", "dominate") and are listed in the report so the team can change them.
A3_MAX_MOVE = 2.0          # percentage points
A6_MAX_GAP = 2.0           # percentage points, projected minus realised
A7_INVESTIGATE = 0.05      # share of days
A8_MAX_MEDIAN_GAP = 1.0    # % of the exact plan's cost
A9_SPIKE_SHARE = 0.01      # top 1% of hours
A9_DOMINATES = 0.5         # share of savings from spike days

BOOT_BLOCK, BOOT_DRAWS, BOOT_SEED = 7, 2000, 20261029  # same settings as BacktestMetrics / model/evaluate.py

# Chart style (dataviz reference palette; model colours match model/evaluate.py)
INK, INK2, MUTED, GRID, AXIS, SURFACE = "#0b0b0b", "#52514e", "#898781", "#e1e0d9", "#c3c2b7", "#fcfcfb"
COLOR = {"perfect": MUTED, "lear": "#eb6834", "naive_last_known": "#1baf7a", "naive_w1": "#eda100"}
MARKER = {"perfect": "D", "lear": "o", "naive_last_known": "s", "naive_w1": "^"}
BAND = "#f0efec"


def name(profile_id: str) -> str:
    return profile_id.split("@")[0]


# --------------------------------------------------------------------------- #
# Loading
# --------------------------------------------------------------------------- #

class Run:
    def __init__(self, folder: Path):
        self.id = folder.name
        self.folder = folder
        self.manifest = json.loads((folder / "manifest.json").read_text())
        self.metrics = json.loads((folder / "metrics.json").read_text())
        self.summary = pd.read_csv(folder / "metrics_summary.csv")
        self.summary["profile"] = self.summary["profile"].map(name)
        self.daily = pd.read_csv(folder / "metrics_daily.csv", parse_dates=["date"])
        self.daily["profile"] = self.daily["profile"].map(name)
        self.days = pd.read_csv(folder / "days.csv", parse_dates=["date"])
        self.days["profile"] = self.days["profile"].map(name)
        self.timings = pd.read_csv(folder / "timings.csv")

    def row(self, profile: str, source: str) -> pd.Series:
        hit = self.summary[(self.summary["profile"] == profile) & (self.summary["source"] == source)]
        if hit.empty:
            raise KeyError(f"{self.id}: no summary for {profile}/{source}")
        return hit.iloc[0]

    def savings(self, profile: str, source: str = "lear") -> float:
        return float(self.row(profile, source)["realised_savings_pct"])


def load_runs(folder: Path) -> dict[str, Run]:
    return {d.name: Run(d) for d in sorted(folder.iterdir()) if (d / "metrics.json").exists()}


# --------------------------------------------------------------------------- #
# Statistics
# --------------------------------------------------------------------------- #

def block_bootstrap(numerator: np.ndarray, denominator: np.ndarray) -> tuple[float, float]:
    """95% interval of 100 * sum(numerator) / sum(denominator), resampling 7-day moving blocks of days."""
    n = len(numerator)
    rng = np.random.default_rng(BOOT_SEED)
    block = min(BOOT_BLOCK, n)
    starts = rng.integers(0, n - block + 1, size=(BOOT_DRAWS, math.ceil(n / block)))
    idx = (starts[:, :, None] + np.arange(block)).reshape(BOOT_DRAWS, -1)[:, :n]
    ratios = 100 * numerator[idx].sum(axis=1) / denominator[idx].sum(axis=1)
    lo, hi = np.quantile(ratios, [0.025, 0.975])
    return float(lo), float(hi)


def savings_days(daily: pd.DataFrame, profile: str, source: str) -> pd.DataFrame:
    rows = daily[(daily["profile"] == profile) & (daily["source"] == source) & daily["counts_for_savings"]]
    return rows.sort_values("date").set_index("date")


def pct(frame: pd.DataFrame) -> float:
    base = frame["baseline_cost"].sum()
    return float(100 * (base - frame["realised_cost"].sum()) / base) if base else float("nan")


def paired_difference(daily: pd.DataFrame, profile: str, a: str, b: str) -> dict:
    """Realised savings of source a minus source b, in points of the baseline, on the days both count."""
    da, db = savings_days(daily, profile, a), savings_days(daily, profile, b)
    common = da.index.intersection(db.index)
    da, db = da.loc[common], db.loc[common]
    numerator = (db["realised_cost"] - da["realised_cost"]).to_numpy()
    denominator = da["baseline_cost"].to_numpy()
    lo, hi = block_bootstrap(numerator, denominator)
    return {"diff": float(100 * numerator.sum() / denominator.sum()), "lo": lo, "hi": hi, "days": len(common)}


# --------------------------------------------------------------------------- #
# Formatting
# --------------------------------------------------------------------------- #

def table(rows: list[dict]) -> str:
    cols = list(rows[0])

    def numeric(col: str) -> bool:
        return all(str(r[col]).lstrip("+-−$[(").replace(",", "")[:1].isdigit() or str(r[col]) == "-" for r in rows)

    out = ["| " + " | ".join(cols) + " |", "|" + "|".join("---:" if numeric(c) else "---" for c in cols) + "|"]
    out += ["| " + " | ".join(str(r[c]) for c in cols) + " |" for r in rows]
    return "\n".join(out)


def p(value: float, digits: int = 1, sign: bool = False) -> str:
    if value is None or (isinstance(value, float) and math.isnan(value)):
        return "-"
    return f"{value:+.{digits}f}%" if sign else f"{value:.{digits}f}%"


def pts(value: float, digits: int = 1) -> str:
    return "-" if value is None or math.isnan(value) else f"{value:+.{digits}f} pts"


def usd(value: float) -> str:
    return f"${value:,.2f}"


def verdict(passed: bool | None, text_pass: str = "Pass", text_fail: str = "Fail") -> str:
    return "**Reported**" if passed is None else (f"**{text_pass}**" if passed else f"**{text_fail}**")


# --------------------------------------------------------------------------- #
# Charts
# --------------------------------------------------------------------------- #

def style(plt) -> None:
    plt.rcParams.update({
        "font.family": "sans-serif", "font.sans-serif": ["Helvetica Neue", "Helvetica", "Arial", "DejaVu Sans"],
        "font.size": 10, "text.color": INK, "axes.labelcolor": INK2, "axes.edgecolor": AXIS,
        "axes.facecolor": SURFACE, "figure.facecolor": SURFACE, "savefig.facecolor": SURFACE,
        "xtick.color": MUTED, "ytick.color": MUTED, "axes.grid": True, "grid.color": GRID, "grid.linewidth": 0.6,
        "grid.linestyle": "-", "axes.spines.top": False, "axes.spines.right": False, "axes.titlesize": 11,
        "axes.titleweight": "bold", "axes.titlelocation": "left", "legend.frameon": False,
    })


def chart_headline(main: Run, path: Path, plt) -> None:
    fig, ax = plt.subplots(figsize=(8.2, 4.4))
    ax.axvspan(*TARGET, color=BAND, zorder=0, lw=0)
    ax.text(sum(TARGET) / 2, len(PROFILES) - 0.35, "PRD target 15–25%", ha="center", va="bottom", color=INK2,
            fontsize=9)
    offsets = dict(zip(SOURCES, np.linspace(0.24, -0.24, len(SOURCES))))
    for i, profile in enumerate(PROFILES):
        y = len(PROFILES) - 1 - i
        for source in SOURCES:
            r = main.row(profile, source)
            yy = y + offsets[source]
            ax.plot([r["realised_savings_ci_low"], r["realised_savings_ci_high"]], [yy, yy], color=COLOR[source],
                    lw=2, solid_capstyle="round", zorder=2)
            ax.plot(r["realised_savings_pct"], yy, MARKER[source], color=COLOR[source], ms=8, zorder=3,
                    markeredgecolor=SURFACE, markeredgewidth=1.5, label=LABEL[source] if i == 0 else None)
        lear = main.row(profile, "lear")
        ax.annotate(f"LEAR {lear['realised_savings_pct']:.1f}%", (max(main.row(profile, s)["realised_savings_ci_high"] for s in SOURCES), y + offsets["lear"]),
                    xytext=(6, 0), textcoords="offset points", ha="left", va="center", fontsize=8.5, color=INK)
    ax.set_yticks(range(len(PROFILES)), list(reversed(PROFILES)))
    ax.tick_params(axis="y", colors=INK, length=0)
    ax.axvline(0, color=AXIS, lw=1, zorder=1)
    ax.set_xlabel("Realised savings vs the fixed schedule (% of baseline cost), with 95% interval")
    ax.grid(axis="y", visible=False)
    ax.set_ylim(-0.6, len(PROFILES) - 0.15)
    ax.set_title("Realised savings by workload profile and planning forecast")
    ax.legend(loc="upper center", bbox_to_anchor=(0.5, -0.16), ncol=4, fontsize=8.5, handletextpad=0.3)
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)


def monthly(daily: pd.DataFrame, profile: str, source: str) -> pd.Series:
    rows = savings_days(daily, profile, source).reset_index()
    by_month = rows.groupby(rows["date"].dt.to_period("M"))
    return by_month.apply(lambda g: pct(g), include_groups=False)


def chart_monthly(main: Run, path: Path, plt) -> None:
    fig, axes = plt.subplots(1, len(PROFILES), figsize=(11, 3.4), sharey=True)
    for ax, profile in zip(axes, PROFILES):
        for source in ("perfect", "lear"):
            series = monthly(main.daily, profile, source)
            x = series.index.to_timestamp()
            ax.plot(x, series.to_numpy(), color=COLOR[source], lw=2, label=LABEL[source])
        ax.axhspan(*TARGET, color=BAND, zorder=0, lw=0)
        ax.axhline(0, color=AXIS, lw=1)
        ax.set_title(profile, fontsize=10)
        ax.tick_params(axis="x", labelrotation=0, labelsize=8)
        for label in ax.get_xticklabels():
            label.set_horizontalalignment("center")
        ax.xaxis.set_major_locator(plt.matplotlib.dates.YearLocator())
        ax.xaxis.set_major_formatter(plt.matplotlib.dates.DateFormatter("%Y"))
    axes[0].set_ylabel("Realised savings per month (%)")
    axes[0].legend(loc="upper left", fontsize=8)
    fig.suptitle("Monthly realised savings: LEAR vs perfect foresight (shaded: 15–25% target)", x=0.01,
                 ha="left", fontsize=11, fontweight="bold")
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)


def chart_flexibility(flex: pd.DataFrame, path: Path, plt) -> None:
    fig, ax = plt.subplots(figsize=(6.4, 3.8))
    ax.axhspan(*TARGET, color=BAND, zorder=0, lw=0)
    for source, column in (("perfect", "perfect"), ("lear", "lear")):
        ax.plot(flex["hours"], flex[column], color=COLOR[source], lw=2, marker=MARKER[source], ms=7,
                markeredgecolor=SURFACE, markeredgewidth=1.5)
        last = flex.iloc[-1]
        ax.annotate(LABEL[source], (last["hours"], last[column]), xytext=(6, 0), textcoords="offset points",
                    va="center", fontsize=8.5, color=INK)
    ax.axhline(0, color=AXIS, lw=1)
    ax.set_xticks(flex["hours"])
    ax.set_xlim(-1, flex["hours"].max() + 9)
    ax.set_xlabel("Flexibility given to every job (hours; capped at the end of the UTC day)")
    ax.set_ylabel("Realised savings (%)")
    ax.set_title("A2: savings grow with flexibility (profile 'mixed')")
    fig.tight_layout()
    fig.savefig(path, dpi=160)
    plt.close(fig)


# --------------------------------------------------------------------------- #
# Report
# --------------------------------------------------------------------------- #

def forecast_accuracy(folder: Path) -> pd.DataFrame:
    fc = pd.read_parquet(folder / "forecasts_walkforward_y0.parquet")
    err = fc["pred"] - fc["actual"]
    out = fc.assign(abs_err=err.abs(), sq_err=err ** 2, err=err).groupby("model").agg(
        mae=("abs_err", "mean"), rmse=("sq_err", lambda s: float(np.sqrt(s.mean()))), bias=("err", "mean"),
        hours=("abs_err", "size"))
    return out


def build(args) -> str:
    runs = load_runs(args.runs)
    main = runs["main"]
    today = date.today().isoformat()
    figures = args.out / f"backtest-{today}"
    figures.mkdir(parents=True, exist_ok=True)

    import matplotlib
    matplotlib.use("Agg")
    import matplotlib.pyplot as plt
    import matplotlib.dates  # noqa: F401  (used through plt.matplotlib.dates)
    style(plt)

    daily = main.daily
    lines: list[str] = []
    add = lines.append
    cfg = main.manifest["config"]

    # ---------------- A1 headline
    head_rows, a1_ok = [], {}
    for profile in PROFILES:
        r = main.row(profile, "lear")
        lo, hi = r["realised_savings_ci_low"], r["realised_savings_ci_high"]
        overlaps = hi >= TARGET[0] and lo <= TARGET[1]
        if profile in REALISTIC:
            a1_ok[profile] = overlaps
        head_rows.append({
            "Profile": profile, "LEAR realised": p(r["realised_savings_pct"]),
            "95% interval": f"[{lo:.1f}, {hi:.1f}]",
            "Perfect foresight": p(main.savings(profile, "perfect")),
            "Naive last known": p(main.savings(profile, "naive_last_known")),
            "Naive last week": p(main.savings(profile, "naive_w1")),
            "Share of possible savings captured": f"{r['share_of_possible_savings_captured']:.0%}",
            "Overlaps 15–25%": ("yes" if overlaps else "no") + ("" if profile in REALISTIC else " (lower bound)"),
        })
    chart_headline(main, figures / "headline.png", plt)
    chart_monthly(main, figures / "monthly.png", plt)

    # ---------------- assumption computations
    assumptions = []

    # A1
    n_ok = sum(a1_ok.values())
    assumptions.append(("A1", "Savings of 15–25% are achievable",
                        f"LEAR-planned savings interval overlaps 15–25% for {n_ok} of {len(a1_ok)} realistic "
                        f"profiles ({', '.join(p_ for p_, ok in a1_ok.items() if ok) or 'none'}).",
                        verdict(n_ok == len(a1_ok), "Pass", "Partly met" if n_ok else "Fail")))

    # A2
    flex_run = runs["a2-flexibility"]
    flex = pd.DataFrame([{"hours": int(prof.rsplit("-", 1)[1]), "lear": flex_run.savings(prof, "lear"),
                          "perfect": flex_run.savings(prof, "perfect"),
                          "lear_lo": flex_run.row(prof, "lear")["realised_savings_ci_low"],
                          "lear_hi": flex_run.row(prof, "lear")["realised_savings_ci_high"],
                          "jobs": int(flex_run.row(prof, "lear")["jobs"])}
                         for prof in sorted(flex_run.summary["profile"].unique())]).sort_values("hours")
    chart_flexibility(flex, figures / "flexibility.png", plt)
    kept_start_rows = []
    for label, run, profiles in (("headline", main, PROFILES),
                                 ("A2, 0 h flexibility", flex_run, [q for q in flex_run.summary["profile"].unique()
                                                                     if q.endswith("-0")])):
        jobs = pd.read_csv(run.folder / "jobs.csv")
        jobs = jobs[(jobs["source"] == "perfect") & (jobs["status"] != "NO_FEASIBLE_SLOT")]
        jobs["profile"] = jobs["profile"].map(name)
        jobs["saved"] = jobs["baseline_cost_actual"] - jobs["optimised_cost_actual"]
        kept = jobs["scheduled_start"] == jobs["original_start"]
        for q in profiles:
            mine = jobs["profile"] == q
            total = jobs.loc[mine, "saved"].sum()
            kept_start_rows.append({"Run": label, "Profile": q, "Jobs that kept their start": f"{kept[mine].mean():.0%}",
                                    "Their share of savings": f"{jobs.loc[mine & kept, 'saved'].sum() / total:.0%}"
                                    if total else "-"})
    rises = bool((np.diff(flex["perfect"]) >= -0.5).all() and (np.diff(flex["lear"]) >= -0.5).all())
    zero_at_0 = abs(flex.iloc[0]["perfect"]) < 1.0
    assumptions.append(("A2", "Flexibility is what drives savings",
                        f"Perfect-foresight savings go from {p(flex.iloc[0]['perfect'])} at 0 h to "
                        f"{p(flex.iloc[-1]['perfect'])} at 24 h; LEAR from {p(flex.iloc[0]['lear'])} to "
                        f"{p(flex.iloc[-1]['lear'])}. Rising throughout: {'yes' if rises else 'no'}; about 0 at 0 h: "
                        f"{'yes' if zero_at_0 else 'no'}"
                        + ("" if zero_at_0 else " (deadline slack still lets jobs reshape their work, section 6)")
                        + ".", verdict(rises and zero_at_0, "Pass", "Partly met" if rises else "Fail")))

    # A3
    a3_rows, a3_worst = [], 0.0
    for run_id in ("a3-cpu-kwh-0.5x", "a3-cpu-kwh-2x", "a3-gpu-kwh-0.5x", "a3-gpu-kwh-2x"):
        run = runs[run_id]
        opt = run.manifest["optimiser"]
        row = {"Run": run_id.removeprefix("a3-"), "CPU kWh/h": opt["cpuKwhPerHour"], "GPU kWh/h": opt["gpuKwhPerHour"]}
        for profile in PROFILES:
            move = run.savings(profile) - main.savings(profile)
            a3_worst = max(a3_worst, abs(move))
            row[profile] = pts(move, 2)
        a3_rows.append(row)
    assumptions.append(("A3", "Results don't hinge on the kWh factors (0.05 / 0.40)",
                        f"Halving or doubling either factor moves LEAR savings by at most {a3_worst:.2f} points "
                        f"(limit ±{A3_MAX_MOVE:.0f}).", verdict(a3_worst < A3_MAX_MOVE)))

    # A4
    a4_rows, a4_violations = [], 0
    for run_id, scale in (("a4-capacity-0.5x", "50%"), ("main", "100%"), ("a4-capacity-2x", "200%")):
        run = runs[run_id]
        c = run.manifest["config"]
        a4_violations += int(run.summary["violations"].sum())
        row = {"Capacity": f"{scale} ({c['capacityCpuHoursPerHour']:.0f} CPU / {c['capacityGpuHoursPerHour']:.0f} GPU)"}
        for profile in PROFILES:
            r = run.row(profile, "lear")
            row[profile] = f"{r['realised_savings_pct']:.1f}% ({int(r['jobs']):,} jobs)"
        a4_rows.append(row)
    assumptions.append(("A4", "Capacity doesn't distort the result",
                        f"See the capacity table: {a4_violations} rule violations across the three capacities. "
                        "Savings move with how many jobs fit and how much room they have, as expected.",
                        verdict(a4_violations == 0, "Pass (explained)", "Fail")))

    # A5
    a5_rows, a5_ok, a5_diffs = [], True, []
    for profile in PROFILES:
        row = {"Profile": profile}
        for naive in ("naive_last_known", "naive_w1"):
            d = paired_difference(daily, profile, "lear", naive)
            row[f"LEAR − {LABEL[naive].lower()}"] = f"{d['diff']:+.1f} pts [{d['lo']:+.1f}, {d['hi']:+.1f}]"
            a5_diffs.append(d)
            if profile in REALISTIC and d["diff"] < 0:
                a5_ok = False
        gap = main.savings(profile, "perfect") - main.savings(profile, "lear")
        row["Gap to perfect foresight"] = f"{gap:.1f} pts"
        a5_rows.append(row)
    assumptions.append(("A5", "The forecast is good enough to act on",
                        f"LEAR saves {min(x['diff'] for x in a5_diffs):+.1f} to {max(x['diff'] for x in a5_diffs):+.1f} "
                        f"points more than the naive forecasts; the 95% interval is above 0 in "
                        f"{sum(x['lo'] > 0 for x in a5_diffs)} of {len(a5_diffs)} comparisons. "
                        "It leaves "
                        f"{min(main.savings(q, 'perfect') - main.savings(q) for q in REALISTIC):.1f}–"
                        f"{max(main.savings(q, 'perfect') - main.savings(q) for q in REALISTIC):.1f} points of the "
                        "possible savings on the table (section 4)."
                        + ("" if a5_ok else " LEAR does not beat both naive forecasts on every realistic profile."),
                        verdict(a5_ok)))

    # A6
    a6_rows, a6_ok = [], True
    for profile in PROFILES:
        r = main.row(profile, "lear")
        d = savings_days(daily, profile, "lear")
        over_days = float((d["projection_gap_pct"] > 0).mean())
        gap = float(r["projection_gap_pct"])
        if profile in REALISTIC and gap > A6_MAX_GAP:
            a6_ok = False
        a6_rows.append({"Profile": profile, "Projected (what the app shows)": p(r["projected_savings_pct"]),
                        "Realised": p(r["realised_savings_pct"]), "Gap (projected − realised)": pts(gap),
                        "Mean daily gap": pts(float(d["projection_gap_pct"].mean())),
                        "Days projected > realised": f"{over_days:.0%}"})
    assumptions.append(("A6", "The app's projected savings are honest",
                        "Projected minus realised savings over the period ranges from "
                        f"{min(main.row(q, 'lear')['projection_gap_pct'] for q in PROFILES):+.1f} to "
                        f"{max(main.row(q, 'lear')['projection_gap_pct'] for q in PROFILES):+.1f} points (negative = the app "
                        "under-promises, positive = it over-promises; limit: no more "
                        f"than {A6_MAX_GAP:.0f} points of over-promise on a realistic profile). Planning on the naive "
                        "forecasts would over-promise by "
                        f"{min(main.row(q, s)['projection_gap_pct'] for q in REALISTIC for s in ('naive_last_known', 'naive_w1')):+.1f} to "
                        f"{max(main.row(q, s)['projection_gap_pct'] for q in REALISTIC for s in ('naive_last_known', 'naive_w1')):+.1f} "
                        "points on the realistic profiles.", verdict(a6_ok)))

    # A7
    a7_rows, a7_worst = [], 0.0
    for profile in PROFILES:
        d = savings_days(daily, profile, "lear")
        negative = d["realised_savings_usd"] < -1e-12
        share = float(negative.mean())
        a7_worst = max(a7_worst, share if profile in REALISTIC else 0.0)
        lost = float(-d.loc[negative, "realised_savings_usd"].sum())
        gained = float(d.loc[~negative, "realised_savings_usd"].sum())
        a7_rows.append({"Profile": profile, "Days with negative savings": f"{share:.1%}",
                        "Money lost on those days": usd(lost), "Saved on the other days": usd(gained),
                        "Forecast MAE on those days ($/MWh, hours used)":
                            f"{d.loc[negative, 'forecast_mae_used'].mean():.1f}",
                        "on the other days": f"{d.loc[~negative, 'forecast_mae_used'].mean():.1f}"})
    assumptions.append(("A7", "Negative savings are rare",
                        f"Up to {a7_worst:.0%} of days on a realistic profile cost more than the fixed schedule "
                        f"(investigate above {A7_INVESTIGATE:.0%}); see the investigation under Savings.",
                        verdict(a7_worst <= A7_INVESTIGATE, "Pass", "Fail (investigated)")))

    # A8
    heur = runs["a8-heuristic"].days
    exact = main.days[main.days["source"].isin(["lear", "perfect"])]
    merged = exact.merge(heur, on=["profile", "date", "source"], suffixes=("_exact", "_heur"))
    proven = merged[merged["proven_optimal_exact"] & (merged["optimised_cost_planned_exact"] > 1e-9)
                    & (merged["scheduled_exact"] == merged["scheduled_heur"])]
    gap = 100 * (proven["optimised_cost_planned_heur"] - proven["optimised_cost_planned_exact"]) \
        / proven["optimised_cost_planned_exact"]
    a8_rows = []
    for profile in PROFILES:
        g = gap[proven["profile"] == profile]
        a8_rows.append({"Profile": profile, "Proven-optimal days compared": f"{len(g):,}",
                        "Median gap": f"{g.median():.3f}%", "90th percentile": f"{g.quantile(0.9):.2f}%",
                        "Days the heuristic matched the exact plan": f"{(g.abs() < 1e-4).mean():.0%}",
                        "Worst day": f"{g.max():.1f}%"})
    a8_median = float(gap.median())
    a8_better = int((gap < -1e-4).sum())  # below 0.0001%: rounding and tie-break weights, not a better plan
    assumptions.append(("A8", "The heuristic is close to optimal",
                        f"On {len(gap):,} proven-optimal plans the heuristic alone costs a median {a8_median:.3f}% "
                        f"more than the exact plan (limit {A8_MAX_MEDIAN_GAP:.0f}%)"
                        + (", and is never meaningfully cheaper (differences below 0.0001% are rounding)."
                           if a8_better == 0 else f", but is cheaper on {a8_better} plans, which needs a look.")
                        + f" Its worst single day is {gap.max():.1f}% more expensive, which "
                        "is why production also runs the exact solver.", verdict(a8_median < A8_MAX_MEDIAN_GAP and a8_better == 0)))

    # A9
    prices = pd.read_csv(args.prices, parse_dates=["interval_start_utc"])
    period = prices[(prices["interval_start_utc"] >= pd.Timestamp(cfg["from"]))
                    & (prices["interval_start_utc"] < pd.Timestamp(cfg["to"]) + pd.Timedelta(days=1))]
    threshold = float(period["price"].quantile(1 - A9_SPIKE_SHARE))
    spike_days = set(period.loc[period["price"] > threshold, "interval_start_utc"].dt.floor("D"))
    a9_rows, a9_worst = [], 0.0
    for profile in PROFILES:
        d = savings_days(daily, profile, "lear")
        is_spike = d.index.isin(list(spike_days))
        total = d["realised_savings_usd"].sum()
        share = float(d.loc[is_spike, "realised_savings_usd"].sum() / total) if total else float("nan")
        if profile in REALISTIC:
            a9_worst = max(a9_worst, share)
        a9_rows.append({"Profile": profile, "All days": p(pct(d)), "Without spike days": p(pct(d[~is_spike])),
                        "Spike days": f"{int(is_spike.sum())}",
                        "Share of savings ($) from spike days": f"{share:.0%}"})
    assumptions.append(("A9", "Price spikes don't dominate",
                        f"The top 1% of hours (above ${threshold:,.0f}/MWh) fall on {len(spike_days)} days. Those "
                        f"days give up to {a9_worst:.0%} of a realistic profile's savings (flag above "
                        f"{A9_DOMINATES:.0%}).", verdict(a9_worst <= A9_DOMINATES, "Pass", "Flagged")))

    # ---------------- write
    add(f"# Backtest report: grid-aware scheduling vs a fixed schedule ({today})")
    add("")
    add("Generated by `backtest/run_report.sh` (ticket 3 of [docs/backtest-plan.md](../backtest-plan.md)). "
        "Every number below comes from the run files listed under [Method](#2-method).")
    add("")
    add("## 1. Headline")
    add("")
    best = max(REALISTIC, key=lambda q: main.savings(q))
    add(f"Planning on the **served LEAR 48-hour forecast**, the optimiser cut energy cost against the fixed "
        f"schedule by **{p(main.savings('mixed'))}** for the `mixed` workload, from "
        f"{p(min(main.savings(q) for q in REALISTIC))} to {p(main.savings(best))} across the realistic profiles. "
        f"With perfect price foresight the same jobs would have saved "
        f"{p(min(main.savings(q, 'perfect') for q in REALISTIC))}–{p(max(main.savings(q, 'perfect') for q in REALISTIC))}. "
        f"Period: {cfg['from']} to {cfg['to']} ({int(main.row('mixed', 'lear')['days'])} days), ERCOT LZ_NORTH "
        "day-ahead prices.")
    add("")
    add(table(head_rows))
    add("")
    add("Realised = plan made on the forecast, paid at actual prices. Intervals: 7-day moving-block bootstrap, "
        "2,000 draws. `tight` (0–2 h flexibility) was meant as a lower bound and is not judged against the "
        "target. It isn't a strict one: deadline slack still lets jobs reshape their work (section 6, A2).")
    add("")
    add(f"![Realised savings by profile](backtest-{today}/headline.png)")
    add("")
    mixed_days = savings_days(daily, "mixed", "lear")
    by_season = {s: pct(mixed_days[mixed_days.index.month.isin(months)])
                 for s, months in (("winter", [12, 1, 2]), ("summer", [6, 7, 8]))}
    add("**Key findings**")
    add("")
    hourly = period.assign(month=period["interval_start_utc"].dt.month, hour=period["interval_start_utc"].dt.hour)
    winter = hourly[hourly["month"].isin([12, 1, 2])].groupby("hour")["price"].median()
    summer = hourly[hourly["month"].isin([6, 7, 8])].groupby("hour")["price"].median()
    add(f"- **Seasonal.** For `mixed`, savings are {p(by_season['winter'])} in winter and {p(by_season['summer'])} "
        f"in summer. In winter the median price falls through the afternoon (${winter[13]:.0f}/MWh at 13:00 UTC "
        f"to ${winter.loc[17:21].min():.0f} by evening), so delaying daytime jobs pays. In summer they already "
        f"start in the cheapest hours (${summer.loc[13:15].min():.0f}–{summer.loc[13:15].max():.0f} at 13–15 UTC) "
        f"and prices only rise until midnight UTC (${summer[23]:.0f}), past which jobs can't move (section 7).")
    add(f"- **The forecast captures about {main.row('mixed', 'lear')['share_of_possible_savings_captured']:.0%} of "
        f"what was possible** (`mixed`). LEAR beats the naive forecasts by "
        f"{min(main.savings('mixed') - main.savings('mixed', n) for n in ('naive_last_known', 'naive_w1')):.1f} to "
        f"{max(main.savings('mixed') - main.savings('mixed', n) for n in ('naive_last_known', 'naive_w1')):.1f} "
        "points (A5).")
    add(f"- **About {main.row('mixed', 'lear')['negative_days_share']:.0%} of days cost slightly more** than the fixed "
        "schedule: days with little to gain where the forecast saw a spread that wasn't there (A7).")
    add(f"- **The app's projected savings are off** by "
        f"{min(main.row(q, 'lear')['projection_gap_pct'] for q in REALISTIC):+.1f} to "
        f"{max(main.row(q, 'lear')['projection_gap_pct'] for q in REALISTIC):+.1f} points on the realistic profiles (A6).")
    add("- **The optimiser itself is sound:** 0 rule violations, 100% of jobs scheduled by their deadline, "
        f"{main.manifest['summary']['provenOptimalShare']:.2%} of plans proven optimal.")
    add("")
    add("**Assumption verdicts** (details in section 6):")
    add("")
    add(table([{"#": a, "Assumption": t, "Verdict": v} for a, t, _, v in assumptions]))
    add("")

    # Method
    add("## 2. Method")
    add("")
    add("- **Workloads:** four synthetic, versioned profiles (`backend/src/test/resources/backtest/profiles/`), "
        "jobs drawn with fixed seeds per day. Every accepted day's fixed schedule fits the facility (decision D2).")
    add(f"- **Facility:** {cfg['capacityCpuHoursPerHour']:.0f} CPU-hours and {cfg['capacityGpuHoursPerHour']:.0f} "
        f"GPU-hours per hour; {main.manifest['optimiser']['cpuKwhPerHour']} kWh per CPU-hour and "
        f"{main.manifest['optimiser']['gpuKwhPerHour']} kWh per GPU-hour.")
    add("- **Planning:** one plan per UTC day per price source, by the production allocation code (exact solver "
        f"with a {cfg['exactBudgetSeconds']} s budget, else the deterministic heuristic). Every plan is settled at "
        "actual prices and checked against the contract rules.")
    add("- **Forecasts:** walk-forward, out of sample: the y0 (48 h ahead) LEAR model, refitted every 7 days "
        "on past data only by `model/evaluate.py` (`model/lear_model.py`, no pretrained file), plus two naive "
        "forecasts. Each day is planned with the forecast made two days before it, which is what the app has. "
        "The training files were rebuilt in the current feature layout by `backtest/build_features.py`.")
    add("- **Savings rule:** days with an unscheduled job are left out of savings figures (none were).")
    add("")
    add("Runs (all under `backtest/output/report/`, not committed):")
    add("")
    run_rows = []
    for run in runs.values():
        m = run.manifest
        c = m["config"]
        run_rows.append({"Run": run.id, "Commit": m["git"]["commit"][:7] + ("+changes" if m["git"]["uncommittedChanges"] else ""),
                         "Solver": c.get("solver", "repeatable"), "Profiles": len(m["profiles"]),
                         "Day rows": f"{m['summary']['dayRows']:,}", "Violations": m["summary"]["violations"],
                         "Proven optimal": f"{m['summary']['provenOptimalShare']:.2%}",
                         "actual_prices.csv": m["inputs"]["sha256"]["actual_prices.csv"][:12],
                         "forecast_lear.csv": m["inputs"]["sha256"].get("forecast_lear.csv", "")[:12]})
    add(table(run_rows))
    add("")
    add("Rerun everything: `backtest/run_report.sh` from the repo root (needs `model/train.csv` and `model/val.csv`). "
        "Reproduce one run exactly: `mvn test -Dtest=BacktestRun -Dbacktest.run=true "
        "-Dbacktest.reproduce=../backtest/output/report/<run>/manifest.json` from `backend/`.")
    add("")

    # Savings
    add("## 3. Savings")
    add("")
    add("### Daily distribution (LEAR)")
    add("")
    add(table([{"Profile": q, "Mean daily": p(r["daily_savings_mean_pct"]), "Median daily": p(r["daily_savings_median_pct"]),
                "Std dev": f"{r['daily_savings_std_pct']:.1f}", "10th pct": p(r["daily_savings_p10_pct"]),
                "90th pct": p(r["daily_savings_p90_pct"]), "Negative days": f"{r['negative_days_share']:.1%}"}
               for q in PROFILES for r in [main.row(q, "lear")]]))
    add("")
    add("The headline uses the ratio of totals (money saved / baseline money). Daily percentages swing much more "
        "because some days have a tiny baseline cost.")
    add("")
    add("### By month and season")
    add("")
    add(f"![Monthly savings](backtest-{today}/monthly.png)")
    add("")
    seasons = {12: "Winter", 1: "Winter", 2: "Winter", 3: "Spring", 4: "Spring", 5: "Spring", 6: "Summer",
               7: "Summer", 8: "Summer", 9: "Autumn", 10: "Autumn", 11: "Autumn"}
    season_rows = []
    for season in ("Winter", "Spring", "Summer", "Autumn"):
        row = {"Season": season}
        for q in PROFILES:
            d = savings_days(daily, q, "lear")
            row[q] = p(pct(d[d.index.month.map(seasons) == season]))
        season_rows.append(row)
    add(table(season_rows))
    add("")
    add("### By price volatility")
    add("")
    vol = period.assign(day=period["interval_start_utc"].dt.floor("D")).groupby("day")["price"].std()
    quartile = pd.qcut(vol, 4, labels=["Calmest 25%", "2nd", "3rd", "Most volatile 25%"])
    vol_rows = []
    for label in quartile.cat.categories:
        days_in = set(quartile[quartile == label].index)
        row = {"Days by intraday price std dev": label,
               "Std dev range ($/MWh)": f"{vol[quartile == label].min():.0f}–{vol[quartile == label].max():.0f}"}
        for q in PROFILES:
            d = savings_days(daily, q, "lear")
            row[q] = p(pct(d[d.index.isin(list(days_in))]))
        vol_rows.append(row)
    add(table(vol_rows))
    add("")
    add("### Worst days (`mixed`, LEAR)")
    add("")
    d = savings_days(daily, "mixed", "lear").sort_values("realised_savings_usd").head(8)
    add(table([{"Date": i.date(), "Baseline": usd(r["baseline_cost"]), "Realised": usd(r["realised_cost"]),
                "Saved": usd(r["realised_savings_usd"]), "Perfect foresight saved": usd(r["baseline_cost"] - r["perfect_cost"]),
                "Forecast MAE, hours used ($/MWh)": f"{r['forecast_mae_used']:.0f}"} for i, r in d.iterrows()]))
    add("")
    add("### Negative-savings days (A7 investigation)")
    add("")
    add(table(a7_rows))
    add("")
    perfect_negative = float(main.summary.loc[main.summary["source"] == "perfect", "negative_days_share"].max())
    neg_rows = []
    for q in PROFILES:
        d = savings_days(daily, q, "lear")
        neg = d["realised_savings_usd"] < -1e-12
        share = lambda g, col: 100 * g[col].sum() / g["baseline_cost"].sum()  # noqa: E731
        d = d.assign(perfect_usd=d["baseline_cost"] - d["perfect_cost"])
        neg_rows.append({"Profile": q, "Possible savings on negative days (perfect foresight)": p(share(d[neg], "perfect_usd")),
                         "on other days": p(share(d[~neg], "perfect_usd")),
                         "Savings LEAR expected on negative days": p(share(d[neg], "projected_savings_usd"))})
    add("Negative days are mostly days with little to gain. On them, perfect foresight could only have saved a few "
        "percent, yet the forecast still saw a price difference worth moving jobs for, and it wasn't there:")
    add("")
    add(table(neg_rows))
    add("")
    add("Forecast error is not larger on these days (table above); the difference is that there is no real price "
        "spread to capture, so any error turns into a loss. The losses are small next to the savings on other "
        "days. "
        + ("Planning on actual prices never loses money (0 negative days for perfect foresight) and no rule was "
           "violated, so these are forecast misses, not solver faults."
           if perfect_negative == 0 and int(main.summary["violations"].sum()) == 0 else
           "Perfect foresight also has negative days or there were rule violations: check the solver before "
           "reading these as forecast misses."))
    add("")

    # Forecast
    add("## 4. Forecast")
    add("")
    acc = forecast_accuracy(args.forecast_eval)
    add("Accuracy of the walk-forward forecasts over every scored hour ($/MWh against the actual price):")
    add("")
    add(table([{"Forecast": LABEL[m], "MAE": f"{acc.loc[m, 'mae']:.2f}", "RMSE": f"{acc.loc[m, 'rmse']:.2f}",
                "Bias": f"{acc.loc[m, 'bias']:+.2f}", "Hours": f"{int(acc.loc[m, 'hours']):,}"}
               for m in ("lear", "naive_last_known", "naive_w1") if m in acc.index]))
    add("")
    add("Savings when planning on each forecast, against perfect foresight (realised savings difference in points "
        "of baseline cost, with a paired 95% block-bootstrap interval):")
    add("")
    add(table(a5_rows))
    add("")
    add("Lost savings vs forecast error (`mixed`, LEAR): days grouped by the forecast's error over the hours "
        "the plan used.")
    add("")
    d = savings_days(daily, "mixed", "lear")
    bins = pd.qcut(d["forecast_mae_used"], 4, labels=["Smallest error 25%", "2nd", "3rd", "Largest error 25%"])
    add(table([{"Days": label, "MAE range ($/MWh)": f"{g['forecast_mae_used'].min():.1f}–{g['forecast_mae_used'].max():.1f}",
                "Realised": p(pct(g)), "Perfect foresight": p(float(100 * (g['baseline_cost'].sum() - g['perfect_cost'].sum()) / g['baseline_cost'].sum())),
                "Lost to forecast error": usd(g["forecast_error_cost_usd"].sum())}
               for label, g in d.groupby(bins, observed=True)]))
    add("")
    add("### Projected vs realised (A6)")
    add("")
    add(table(a6_rows))
    add("")

    # Guardrails
    add("## 5. Guardrails")
    add("")
    timing = main.timings["solve_ms"]
    add(table([{"Profile": q, "Jobs": f"{int(r['jobs']):,}", "Scheduled": f"{r['scheduled'] / r['jobs']:.1%}",
                "Deadline adherence": f"{r['deadline_adherence']:.1%}", "Rule violations": int(r["violations"]),
                "Proven optimal": f"{r['proven_optimal_share']:.2%}",
                "Energy, baseline = plan": "yes" if abs(r["energy_kwh_baseline"] - r["energy_kwh_optimised"]) < 1e-6 else "NO"}
               for q in PROFILES for r in [main.row(q, "lear")]]))
    add("")
    add(f"Solve time per plan (all profiles and sources, {len(timing):,} plans): median {timing.median():.0f} ms, "
        f"95th percentile {timing.quantile(0.95):.0f} ms, max {timing.max() / 1000:.1f} s. Not repeatable, so kept "
        "out of the hashed results.")
    add("")

    # Assumptions
    add("## 6. Assumptions")
    add("")
    add(table([{"#": a, "Assumption": t, "Result": res, "Verdict": v} for a, t, res, v in assumptions]))
    add("")
    add("A failed assumption is a finding, not a bug. Thresholds: A3 and A8 are the plan's. A6 (≤ "
        f"{A6_MAX_GAP:.0f} points of over-promise), A7 (investigate above {A7_INVESTIGATE:.0%}) and A9 (flag above "
        f"{A9_DOMINATES:.0%} of savings) turn the plan's wording into numbers; the team can change them in "
        "`backtest/report.py`.")
    add("")
    add("### A2: flexibility (`mixed`, every job given the same flexibility)")
    add("")
    add(f"![Flexibility](backtest-{today}/flexibility.png)")
    add("")
    add(table([{"Flexibility (h)": int(r["hours"]), "LEAR": p(r["lear"]),
                "95% interval": f"[{r['lear_lo']:.1f}, {r['lear_hi']:.1f}]", "Perfect foresight": p(r["perfect"]),
                "Jobs": f"{int(r['jobs']):,}"} for _, r in flex.iterrows()]))
    add("")
    add("Flexibility is capped at the end of the UTC day (decision D5), so 12 h and 24 h mostly mean \"until "
        "midnight UTC\" (19:00 in Texas).")
    add("")
    add("**Where savings come from when a job doesn't move.** Each job's deadline also has 0–3 h of slack, so a job "
        "that keeps its start time can still spread its work differently over the hours before its deadline, "
        "putting more into the cheaper hour. Share of perfect-foresight savings from jobs that kept their original "
        "start:")
    add("")
    add(table(kept_start_rows))
    add("")
    add("### A3: kWh factors (change in LEAR savings vs the headline)")
    add("")
    add(table(a3_rows))
    add("")
    add("Only the ratio of the two factors matters: halving the CPU factor gives the same result as doubling the "
        "GPU one. Scaling both together changes every cost by the same factor and leaves savings % unchanged. "
        "`nightly-batch` has only CPU work and `business-hours-training` is GPU-heavy, so they barely move. "
        "`mixed` and `tight` move because the ratio changes how much the CPU-only batch jobs (which save little) "
        "weigh against the GPU training jobs (which save a lot).")
    add("")
    add("### A4: facility capacity (LEAR savings, jobs accepted)")
    add("")
    add(table(a4_rows))
    add("")
    add("Jobs are only accepted when the fixed schedule fits, so a smaller facility accepts fewer and smaller "
        "jobs, and a larger one leaves more room to move them.")
    add("")
    add("### A8: heuristic gap on proven-optimal plans (LEAR and perfect-foresight plans, at planning prices)")
    add("")
    add(table(a8_rows))
    add("")
    add("### A9: price spikes (LEAR)")
    add("")
    add(table(a9_rows))
    add("")

    # Limits
    add("## 7. Limits")
    add("")
    add("- **Synthetic workloads.** There is no job history, so results depend on the profiles. Per-profile results "
        "and the sensitivity runs show how much.")
    add("- **Jobs stay inside one UTC day** (D5). The UTC day ends at 19:00 Texas time, so daytime jobs can't "
        "move into the cheap overnight hours. This understates what the optimiser could do with jobs that cross "
        "midnight, which would need the 72 h (y1) forecast as well.")
    add("- **One zone (LZ_NORTH), day-ahead prices only**; no real-time prices.")
    add("- **The app plans only on model forecasts.** Next-day day-ahead prices are published the day before, so "
        "for jobs submitted after they clear, planning on them would be close to the perfect-foresight result. "
        "The app doesn't do that today.")
    add("- **Rebuilt training data.** The 48 h features were rebuilt from the old CSVs "
        "(`backtest/build_features.py`) because the original build script isn't in the repo. In the 1 November "
        "run they reproduced the saved XGBoost model's held-out error exactly; that check was not repeated for LEAR, "
        "so the data/model owner should confirm them.")
    add("- **LSTM not tested.** Only the served LEAR model and two naive forecasts were run.")
    add("- **Job priority** is not exercised: the generated jobs don't set one, so every job has the default.")
    add("- **Walk-forward LEAR, not the stored model.** The forecasts come from the current LEAR code refitted on "
        "past data only. The model stored in Supabase was trained on data up to January 2026, so it can't be "
        "tested fairly on earlier days.")
    add("")
    return "\n".join(lines) + "\n", today


def main() -> None:
    parser = argparse.ArgumentParser(description="Write the backtest report.")
    parser.add_argument("--runs", type=Path, default=REPO / "backtest" / "output" / "report")
    parser.add_argument("--prices", type=Path, default=REPO / "backtest" / "data" / "y0" / "actual_prices.csv")
    parser.add_argument("--forecast-eval", type=Path, default=REPO / "backtest" / "data" / "walkforward_y0")
    parser.add_argument("--out", type=Path, default=REPO / "docs" / "reports")
    args = parser.parse_args()
    text, today = build(args)
    path = args.out / f"backtest-{today}.md"
    path.write_text(text)
    print(f"Report: {path}")


if __name__ == "__main__":
    main()
