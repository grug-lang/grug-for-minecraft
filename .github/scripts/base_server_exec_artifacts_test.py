#!/usr/bin/env python3
"""Tests base_server_exec_artifacts.py: only a live server-exec artifact of the base run is
downloaded, and a base run too old or without a dedicated leg answers nothing (#1602)."""

import contextlib
import importlib.util
import io
import unittest
import unittest.mock
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "base_server_exec_artifacts", Path(__file__).resolve().parent / "base_server_exec_artifacts.py"
)
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)


def art(name, expired=False):
    return {"name": name, "expired": expired, "archive_download_url": f"https://x/{name}.zip"}


class PickTest(unittest.TestCase):
    def test_live_server_exec_artifacts_are_picked_and_others_ignored(self):
        pairs, why = mod.pick(
            {"artifacts": [art("coverage-exec-core"), art("server-exec-b"), art("server-exec-a")]}
        )
        self.assertEqual([name for name, _ in pairs], ["server-exec-a", "server-exec-b"])
        self.assertIn("2 live", why)

    def test_a_base_run_without_a_dedicated_leg_carries_nothing(self):
        pairs, why = mod.pick({"artifacts": [art("coverage-exec-core")]})
        self.assertEqual(pairs, [])
        self.assertIn("no dedicated leg", why)

    def test_a_base_run_older_than_retention_carries_nothing_and_says_so(self):
        pairs, why = mod.pick(
            {"artifacts": [art("server-exec-a", True), art("server-exec-b", True)]}
        )
        self.assertEqual(pairs, [])
        self.assertIn("expired", why)

    def test_an_expired_one_beside_a_live_one_is_skipped(self):
        pairs, _ = mod.pick({"artifacts": [art("server-exec-a", True), art("server-exec-b")]})
        self.assertEqual([name for name, _ in pairs], ["server-exec-b"])

    def test_an_empty_listing_carries_nothing(self):
        self.assertEqual(mod.pick({})[0], [])

    def test_the_newest_run_with_a_live_server_exec_wins(self):
        runs = [{"id": 3}, {"id": 2}, {"id": 1}]
        listings = {
            3: {"artifacts": [art("coverage-exec-core")]},
            2: {"artifacts": [art("server-exec-a")]},
            1: {"artifacts": [art("server-exec-b")]},
        }
        run, pairs = mod.choose_run(runs, lambda run_id: listings[run_id])
        self.assertEqual(run["id"], 2)
        self.assertEqual([name for name, _ in pairs], ["server-exec-a"])

    def test_no_run_with_a_live_server_exec_returns_nothing(self):
        listings = {1: {"artifacts": [art("server-exec-a", True)]}, 2: {}}
        self.assertEqual(mod.choose_run([{"id": 2}, {"id": 1}], listings.get), (None, []))
        self.assertEqual(mod.choose_run([], listings.get), (None, []))

    def test_candidates_are_recent_completed_dispatch_or_schedule_runs_newest_first(self):
        now = mod.parse_time("2026-11-23T12:00:00Z")

        def run(id, event, created, status="completed"):
            return {"id": id, "event": event, "created_at": created, "status": status}

        listing = {
            "workflow_runs": [
                run(1, "push", "2026-11-23T10:00:00Z"),
                run(2, "workflow_dispatch", "2026-11-20T10:00:00Z"),
                run(3, "schedule", "2026-11-22T10:00:00Z"),
                run(4, "workflow_dispatch", "2026-11-10T10:00:00Z"),
                run(5, "workflow_dispatch", "2026-11-23T11:00:00Z", "in_progress"),
            ]
        }
        self.assertEqual([r["id"] for r in mod.candidate_runs(listing, now)], [3, 2])
        many = {
            "workflow_runs": [
                run(n, "schedule", f"2026-11-23T{n % 24:02d}:00:00Z") for n in range(30)
            ]
        }
        self.assertEqual(len(mod.candidate_runs(many, now)), 10)

    def test_main_never_fails_when_the_lookup_does(self):
        err = io.StringIO()
        with unittest.mock.patch.dict("os.environ", {"GITHUB_API_URL": "http://127.0.0.1:1"}):
            with contextlib.redirect_stderr(err):
                self.assertEqual(mod.main(), 0)
        self.assertIn("lookup failed", err.getvalue())


if __name__ == "__main__":
    unittest.main()
