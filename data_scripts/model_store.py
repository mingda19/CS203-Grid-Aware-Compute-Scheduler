"""
model_store.py

Persistence for trained model snapshots in `model_artifacts` (table owned by the backend's
ModelArtifact JPA entity, backend/src/main/java/com/gacs/backend/model/ModelArtifact.java -
ddl-auto=update creates it; this module only reads/writes rows, like ingestion.py does for
electrical_price/wind/solar/fuel_price/predicted_price) - the thing that lets
predict_lstm.py/predict_lear.py and update.py survive a
Render Cron container being ephemeral (render_cron.sh: nothing on disk
carries over between runs). One table for both model kinds (LSTM and LEAR):
from this module's point of view they're both just "a blob plus metadata
about when it's valid and how it scored" - model/lear_model.py and
model/lstm_model.py each already know how to serialize/deserialize their own
artifact dict, so this module never looks inside the blob.

Usage:
    from model_store import save_model_artifact, load_active_artifact

    blob = <lstm_model.save_artifact or lear_model.save_artifact into a BytesIO>
    save_model_artifact(engine, model_kind="lstm", target="y0", blob=blob, format="torch",
                        model_version="lstm_y0_2026-11-09", trained_through=..., metrics={...},
                        valid_from_date=..., valid_to_date=..., promote=True)

    row = load_active_artifact(engine, "lstm", "y0")   # None if nothing has been promoted yet
    model, artifact = lstm_model.load_artifact(io.BytesIO(row["blob"]))
"""

from __future__ import annotations

import json
from datetime import date, datetime

import sqlalchemy as sa

TABLE = "model_artifacts"


def _iso(dt) -> str:
    """Same reasoning as predictions._iso: bind an explicit ISO string, not a
    raw date/datetime, so sqlite's deprecated default adapter and Postgres's
    native cast both parse it the same way."""
    import pandas as pd
    return pd.Timestamp(dt).isoformat()


def save_model_artifact(engine: sa.Engine, *, model_kind: str, target: str, blob: bytes, format: str,
                        model_version: str, trained_through: datetime, valid_from_date: date,
                        valid_to_date: date, metrics: dict, promote: bool = False) -> int:
    """Insert a new snapshot row. Returns its id. `promote=True` also makes it
    the active one for (model_kind, target) in the same transaction - the
    caller decides this (update.py's validate-before-promote check), it's
    never implicit just because a row was written."""
    with engine.begin() as conn:
        result = conn.execute(sa.text(
            f"INSERT INTO {TABLE} (model_kind, target, model_version, format, blob, trained_through, "
            "valid_from_date, valid_to_date, metrics, is_active) "
            "VALUES (:model_kind, :target, :model_version, :format, :blob, :trained_through, "
            ":valid_from_date, :valid_to_date, :metrics, false) "
            "RETURNING id"
        ), {
            "model_kind": model_kind, "target": target, "model_version": model_version, "format": format,
            "blob": blob, "trained_through": _iso(trained_through), "valid_from_date": _iso(valid_from_date),
            "valid_to_date": _iso(valid_to_date), "metrics": json.dumps(metrics),
        })
        new_id = result.scalar_one()
        if promote:
            _promote(conn, new_id, model_kind, target)
    return int(new_id)


def promote_artifact(engine: sa.Engine, artifact_id: int, model_kind: str, target: str) -> None:
    """Make an already-saved row the active one for (model_kind, target),
    deactivating whatever was active before it - in that order, within one
    transaction, so the one-active-row-per-(kind,target) unique index
    (model_artifacts_one_active) is never violated even momentarily."""
    with engine.begin() as conn:
        _promote(conn, artifact_id, model_kind, target)


def _promote(conn: sa.Connection, artifact_id: int, model_kind: str, target: str) -> None:
    conn.execute(sa.text(
        f"UPDATE {TABLE} SET is_active = false WHERE model_kind = :model_kind AND target = :target AND is_active"
    ), {"model_kind": model_kind, "target": target})
    conn.execute(sa.text(f"UPDATE {TABLE} SET is_active = true WHERE id = :id"), {"id": artifact_id})


def load_active_artifact(engine: sa.Engine, model_kind: str, target: str) -> dict | None:
    """The currently-serving row for (model_kind, target), or None if nothing
    has ever been promoted (a brand-new deployment, or a kind/target that
    hasn't had its first update.py tune cycle yet)."""
    with engine.begin() as conn:
        row = conn.execute(sa.text(
            f"SELECT id, model_version, format, blob, trained_through, valid_from_date, valid_to_date, "
            f"metrics, created_at FROM {TABLE} WHERE model_kind = :model_kind AND target = :target "
            f"AND is_active"
        ), {"model_kind": model_kind, "target": target}).mappings().first()
    if row is None:
        return None
    out = dict(row)
    out["blob"] = bytes(out["blob"])
    out["metrics"] = json.loads(out["metrics"]) if isinstance(out["metrics"], str) else out["metrics"]
    return out


def latest_metrics(engine: sa.Engine, model_kind: str, target: str, limit: int = 1) -> list[dict]:
    """The `limit` most recent snapshots' metrics (newest first), active or
    not - update.py's candidate-vs-current comparisons read a candidate's
    metrics before it's ever promoted, so this doesn't filter on is_active."""
    with engine.begin() as conn:
        rows = conn.execute(sa.text(
            f"SELECT id, model_version, metrics, valid_from_date, valid_to_date, created_at FROM {TABLE} "
            "WHERE model_kind = :model_kind AND target = :target ORDER BY created_at DESC, id DESC LIMIT :limit"
        ), {"model_kind": model_kind, "target": target, "limit": limit}).mappings().all()
    out = []
    for row in rows:
        row = dict(row)
        row["metrics"] = json.loads(row["metrics"]) if isinstance(row["metrics"], str) else row["metrics"]
        out.append(row)
    return out


def prune_old_artifacts(engine: sa.Engine, model_kind: str, target: str, keep: int = 12) -> int:
    """Delete inactive snapshots beyond the `keep` most recent, so the table
    doesn't grow without bound over years of weekly tune cycles - at these
    row sizes (model/lear_model.py: ~200KB; model/lstm_model.py: ~1.1MB) this
    is about hygiene, not disk space. The active row is never a candidate,
    whatever its age."""
    with engine.begin() as conn:
        keep_ids = conn.execute(sa.text(
            f"SELECT id FROM {TABLE} WHERE model_kind = :model_kind AND target = :target "
            "ORDER BY created_at DESC, id DESC LIMIT :keep"
        ), {"model_kind": model_kind, "target": target, "keep": keep}).scalars().all()
        stmt = sa.text(
            f"DELETE FROM {TABLE} WHERE model_kind = :model_kind AND target = :target "
            "AND NOT is_active AND id NOT IN :keep_ids"
        ).bindparams(sa.bindparam("keep_ids", expanding=True))
        result = conn.execute(stmt, {"model_kind": model_kind, "target": target,
                                     "keep_ids": list(keep_ids) or [-1]})
    return result.rowcount
