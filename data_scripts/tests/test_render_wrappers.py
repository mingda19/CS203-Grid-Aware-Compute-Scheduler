"""Exercise real Bash control flow with a fake Python command; no external writes."""
import os
from pathlib import Path
import shutil
import subprocess

import pytest


ROOT = Path(__file__).resolve().parents[2]
BASH = (str(Path(os.environ.get("ProgramFiles", "C:/Program Files")) / "Git/bin/bash.exe")
        if os.name == "nt" else shutil.which("bash"))
pytestmark = pytest.mark.skipif(not BASH or not Path(BASH).exists(), reason="Bash required")


def run_wrapper(name, **overrides):
    env = os.environ.copy()
    for key in ("ONLY", "ANCHOR_DATE", "HORIZON", "TARGET", "KIND", "DRY_RUN"):
        env.pop(key, None)
    env.update({key: str(value) for key, value in overrides.items()})
    # An exported function intercepts every Python invocation in the child shell.
    harness = '''
python() {
    printf 'CALL'; printf ' <%s>' "$@"; printf '\\n'
    case "$1" in
        ingestion.py) return "${INGEST_STATUS:-0}" ;;
        predict_lstm.py) return "${LSTM_STATUS:-0}" ;;
        predict_lear.py) return "${LEAR_STATUS:-0}" ;;
        update.py) return "${UPDATE_STATUS:-0}" ;;
    esac
}
export -f python
bash "$1"
'''
    return subprocess.run([BASH, "-c", harness, "test", f"data_scripts/{name}"],
                          cwd=ROOT, env=env, capture_output=True, text=True)


@pytest.mark.parametrize("status", [0, 2, 1, 3, 137])
def test_ingestion_gate(status):
    result = run_wrapper("render_cron.sh", INGEST_STATUS=status)
    assert result.returncode == (0 if status in (0, 2) else status), result.stderr
    assert ("<predict_lstm.py>" in result.stdout) == (status in (0, 2))
    assert ("<predict_lear.py>" in result.stdout) == (status in (0, 2))


@pytest.mark.parametrize("failed", ["LSTM_STATUS", "LEAR_STATUS"])
def test_both_predictors_attempted(failed):
    result = run_wrapper("render_cron.sh", **{failed: 1})
    assert result.returncode == 1
    assert "<predict_lstm.py>" in result.stdout
    assert "<predict_lear.py>" in result.stdout


def test_daily_manual_inputs():
    result = run_wrapper("render_cron.sh", ONLY="weather,eia",
                         ANCHOR_DATE="2026-11-06", HORIZON="y0")
    assert result.returncode == 0, result.stderr
    assert "<ingestion.py> <--only> <weather,eia>" in result.stdout
    assert result.stdout.count("<--date> <2026-11-06> <--horizon> <y0>") == 2


@pytest.mark.parametrize("status", [0, 1, 137])
def test_weekly_status_and_inputs(status):
    result = run_wrapper("render_weekly_tune.sh", TARGET="y1", KIND="lear",
                         DRY_RUN="true", UPDATE_STATUS=status)
    assert result.returncode == status, result.stderr
    assert "<update.py> <--target> <y1> <--kind> <lear> <--dry-run>" in result.stdout


def test_weekly_defaults():
    result = run_wrapper("render_weekly_tune.sh")
    assert result.returncode == 0, result.stderr
    assert result.stdout.strip() == "CALL <update.py>"


def test_invalid_dry_run_stops_before_tuning():
    result = run_wrapper("render_weekly_tune.sh", DRY_RUN="tru")
    assert result.returncode == 2
    assert "<update.py>" not in result.stdout
