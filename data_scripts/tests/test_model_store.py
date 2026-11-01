"""Offline tests for model_store.py against an in-memory SQLite DB with the
same table shape as schema/model_artifacts.sql (BYTEA -> BLOB, JSONB -> TEXT,
TIMESTAMPTZ -> TEXT - sqlite is untyped, so these are just documentation; the
partial unique index is the one piece of real behaviour this mirrors exactly).

Run from the data_scripts/ folder:
    pytest -v tests/test_model_store.py
"""

import unittest

import sqlalchemy as sa

import model_store as store


def make_db() -> sa.Engine:
    engine = sa.create_engine("sqlite:///:memory:")
    with engine.begin() as conn:
        conn.execute(sa.text(
            "CREATE TABLE model_artifacts ("
            " id INTEGER PRIMARY KEY AUTOINCREMENT,"
            " model_kind TEXT NOT NULL, target TEXT NOT NULL, model_version TEXT NOT NULL,"
            " format TEXT NOT NULL, blob BLOB NOT NULL, trained_through TEXT NOT NULL,"
            " valid_from_date TEXT NOT NULL, valid_to_date TEXT NOT NULL,"
            " metrics TEXT NOT NULL DEFAULT '{}', is_active BOOLEAN NOT NULL DEFAULT 0,"
            " created_at TEXT NOT NULL DEFAULT (datetime('now')))"
        ))
        conn.execute(sa.text(
            "CREATE UNIQUE INDEX model_artifacts_one_active ON model_artifacts (model_kind, target) "
            "WHERE is_active"
        ))
    return engine


def save(engine, *, kind="lstm", target="y0", version="lstm_y0_1", promote=False, blob=b"abc"):
    return store.save_model_artifact(
        engine, model_kind=kind, target=target, blob=blob, format="torch", model_version=version,
        trained_through="2026-11-01 23:00:00", valid_from_date="2026-02-01", valid_to_date="2026-10-31",
        metrics={"mae": 7.0}, promote=promote,
    )


class SaveAndLoadTests(unittest.TestCase):
    def test_round_trips_blob_and_metrics(self):
        engine = make_db()
        save(engine, blob=b"\x00\x01\xff", promote=True)
        row = store.load_active_artifact(engine, "lstm", "y0")
        self.assertEqual(row["blob"], b"\x00\x01\xff")
        self.assertEqual(row["metrics"], {"mae": 7.0})
        self.assertEqual(row["model_version"], "lstm_y0_1")

    def test_no_active_row_returns_none(self):
        engine = make_db()
        save(engine, promote=False)  # saved, never promoted
        self.assertIsNone(store.load_active_artifact(engine, "lstm", "y0"))

    def test_kinds_and_targets_are_independent(self):
        engine = make_db()
        save(engine, kind="lstm", target="y0", version="a", promote=True)
        save(engine, kind="lear", target="y0", version="b", promote=True)
        save(engine, kind="lstm", target="y1", version="c", promote=True)
        self.assertEqual(store.load_active_artifact(engine, "lstm", "y0")["model_version"], "a")
        self.assertEqual(store.load_active_artifact(engine, "lear", "y0")["model_version"], "b")
        self.assertEqual(store.load_active_artifact(engine, "lstm", "y1")["model_version"], "c")
        self.assertIsNone(store.load_active_artifact(engine, "lear", "y1"))


class PromotionTests(unittest.TestCase):
    def test_promoting_a_new_row_deactivates_the_old_one(self):
        engine = make_db()
        first_id = save(engine, version="v1", promote=True)
        second_id = save(engine, version="v2", promote=False)
        self.assertEqual(store.load_active_artifact(engine, "lstm", "y0")["model_version"], "v1")

        store.promote_artifact(engine, second_id, "lstm", "y0")
        self.assertEqual(store.load_active_artifact(engine, "lstm", "y0")["model_version"], "v2")
        with engine.begin() as conn:
            still_there = conn.execute(sa.text("SELECT is_active FROM model_artifacts WHERE id = :id"),
                                       {"id": first_id}).scalar_one()
        self.assertFalse(bool(still_there))

    def test_never_two_active_rows_at_once(self):
        """The unique index this mirrors is the actual safety net in Postgres -
        this just confirms _promote's deactivate-then-activate ordering means
        the application layer never needs to rely on catching a violation."""
        engine = make_db()
        save(engine, version="v1", promote=True)
        save(engine, version="v2", promote=True)  # promote=True on insert too
        with engine.begin() as conn:
            n_active = conn.execute(sa.text(
                "SELECT COUNT(*) FROM model_artifacts WHERE model_kind='lstm' AND target='y0' AND is_active"
            )).scalar_one()
        self.assertEqual(n_active, 1)


class LatestMetricsTests(unittest.TestCase):
    def test_returns_newest_first_regardless_of_active_flag(self):
        engine = make_db()
        save(engine, version="v1", promote=True)
        save(engine, version="v2", promote=False)
        rows = store.latest_metrics(engine, "lstm", "y0", limit=2)
        self.assertEqual([r["model_version"] for r in rows], ["v2", "v1"])
        self.assertEqual(rows[0]["metrics"], {"mae": 7.0})


class PruneTests(unittest.TestCase):
    def test_keeps_the_newest_n_and_the_active_row(self):
        engine = make_db()
        ids = [save(engine, version=f"v{i}", promote=(i == 0)) for i in range(5)]
        deleted = store.prune_old_artifacts(engine, "lstm", "y0", keep=2)
        with engine.begin() as conn:
            remaining = conn.execute(sa.text(
                "SELECT model_version FROM model_artifacts ORDER BY id"
            )).scalars().all()
        # v0 is active (never pruned even though it's the oldest); v3, v4 are
        # the 2 newest kept by `keep`; v1, v2 are deleted.
        self.assertEqual(deleted, 2)
        self.assertEqual(set(remaining), {"v0", "v3", "v4"})


if __name__ == "__main__":
    unittest.main()
