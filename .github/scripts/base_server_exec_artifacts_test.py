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

    def test_the_jar_a_leg_ran_is_picked_with_its_exec_data(self):
        pairs, _ = mod.pick(
            {"artifacts": [art("server-exec-a"), art("server-layer-a"), art("layer-a")]}
        )
        self.assertEqual([name for name, _ in pairs], ["server-exec-a", "server-layer-a"])

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

    def test_each_loaders_newest_artifact_wins_and_a_run_supplies_only_what_is_new(self):
        runs = [{"id": 4}, {"id": 3}, {"id": 2}, {"id": 1}]
        listings = {
            4: {"artifacts": [art("coverage-exec-core")]},
            3: {"artifacts": [art("server-exec-a")]},
            2: {"artifacts": [art("server-exec-a"), art("server-exec-b")]},
            1: {"artifacts": [art("server-exec-b"), art("server-exec-c")]},
        }
        chosen = mod.choose_runs(runs, lambda run_id: listings[run_id])
        self.assertEqual(
            [(r["id"], [n for n, _ in pairs]) for r, pairs in chosen],
            [(3, ["server-exec-a"]), (2, ["server-exec-b"]), (1, ["server-exec-c"])],
        )

    def test_the_walk_stops_once_every_wanted_artifact_is_found(self):
        runs = [{"id": 2}, {"id": 1}]
        listings = {2: {"artifacts": [art("server-exec-a")]}}
        chosen = mod.choose_runs(runs, listings.__getitem__, {"server-exec-a"})
        self.assertEqual([r["id"] for r, _ in chosen], [2])

    def test_a_loader_whose_leg_did_not_succeed_is_skipped_for_an_older_good_run(self):
        runs = [{"id": 2}, {"id": 1}]
        listings = {
            2: {"artifacts": [art("server-exec-a"), art("server-exec-b")]},
            1: {"artifacts": [art("server-exec-a"), art("server-exec-b")]},
        }

        def job(name, conclusion):
            return {"name": f"{name} on a dedicated server", "conclusion": conclusion}

        jobs = {
            2: {"jobs": [job("a", "failure"), job("b", "success"), {"name": "other"}]},
            1: {"jobs": [job("a", "success"), job("b", "success")]},
        }
        chosen = mod.choose_runs(runs, listings.__getitem__, None, jobs.__getitem__)
        self.assertEqual(
            [(r["id"], [n for n, _ in pairs]) for r, pairs in chosen],
            [(2, ["server-exec-b"]), (1, ["server-exec-a"])],
        )

    def test_a_run_with_no_artifact_is_not_asked_for_its_jobs(self):
        asked = []
        mod.choose_runs([{"id": 1}], lambda i: {}, None, lambda i: asked.append(i) or {"jobs": []})
        self.assertEqual(asked, [])

    def test_each_loaders_head_commit_comes_from_the_run_supplying_its_artifact(self):
        # The discover job diffs these commits against the run's head (#1739), so each loader must
        # name its own run's commit: a full run supplies several, and the older run supplies the rest.
        runs = [{"id": 2, "head_sha": "b" * 40}, {"id": 1, "head_sha": "a" * 40}]
        listings = {
            2: {"artifacts": [art("server-exec-a"), art("server-layer-a")]},
            1: {"artifacts": [art("server-exec-b")]},
        }
        chosen = mod.choose_runs(runs, listings.__getitem__)
        self.assertEqual(mod.head_shas(chosen), {"a": "b" * 40, "b": "a" * 40})

    def test_a_run_that_named_no_commit_answers_an_empty_one(self):
        chosen = [({"id": 1}, [("server-exec-a", "https://x/a.zip")])]
        self.assertEqual(mod.head_shas(chosen), {"a": ""})

    def test_head_shas_read_no_layer_artifact(self):
        chosen = [({"id": 1, "head_sha": "c" * 40}, [("server-layer-a", "https://x/a.jar")])]
        self.assertEqual(mod.head_shas(chosen), {})

    def test_succeeded_loaders_reads_only_dedicated_leg_successes(self):
        jobs = {
            "jobs": [
                {"name": "1.20.6-forge on a dedicated server", "conclusion": "success"},
                {"name": "1.20.6-forge", "conclusion": "success"},
                {"name": "a1.1.2_01-ornithe on a dedicated server", "conclusion": "cancelled"},
            ]
        }
        self.assertEqual(mod.succeeded_loaders(jobs), {"1.20.6-forge"})
        self.assertEqual(mod.succeeded_loaders({}), set())

    def test_no_run_with_a_live_server_exec_returns_nothing(self):
        listings = {1: {"artifacts": [art("server-exec-a", True)]}, 2: {}}
        self.assertEqual(mod.choose_runs([{"id": 2}, {"id": 1}], listings.get), [])
        self.assertEqual(mod.choose_runs([], listings.get), [])

    def test_candidates_are_recent_completed_dispatch_schedule_or_push_runs_newest_first(self):
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
        self.assertEqual([r["id"] for r in mod.candidate_runs(listing, now)], [1, 3, 2])
        many = {
            "workflow_runs": [
                run(n, "schedule", f"2026-11-23T{n % 24:02d}:00:00Z") for n in range(80)
            ]
        }
        self.assertEqual(len(mod.candidate_runs(many, now)), 50)

    def test_main_never_fails_when_the_lookup_does(self):
        err = io.StringIO()
        with unittest.mock.patch.dict("os.environ", {"GITHUB_API_URL": "http://127.0.0.1:1"}):
            with contextlib.redirect_stderr(err):
                self.assertEqual(mod.main(), 0)
        self.assertIn("lookup failed", err.getvalue())


if __name__ == "__main__":
    unittest.main()
