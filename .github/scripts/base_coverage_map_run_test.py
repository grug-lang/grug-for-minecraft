#!/usr/bin/env python3
"""Tests base_coverage_map_run.py: the lookup finds the successful push run of the base commit
that uploaded the coverage map, and every way to not find one answers nothing, which runs every
coverage test, never carries one over (#786)."""

import contextlib
import http.server
import importlib.util
import io
import json
import os
import threading
import unittest
from pathlib import Path
from urllib.parse import urlparse

HERE = Path(__file__).resolve().parent

SPEC = importlib.util.spec_from_file_location(
    "base_coverage_map_run", HERE / "base_coverage_map_run.py"
)
lookup = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lookup)

SLUG = "test/repo"


class ApiHandler(http.server.BaseHTTPRequestHandler):
    """Serves the two endpoints the lookup reads, from the routes on the test case."""

    def do_GET(self):
        parsed = urlparse(self.path)
        path = parsed.path
        self.server.requests.append(parsed.query)
        routes = self.server.routes
        if path == f"/repos/{SLUG}/actions/runs":
            if routes["runs"] is None:
                self.send_response(500)
                self.end_headers()
                self.wfile.write(b"error")
                return
            body = routes["runs"]
        elif path.startswith(f"/repos/{SLUG}/actions/runs/") and path.endswith("/artifacts"):
            run_id = path.rsplit("/", 2)[1]
            body = routes["artifacts"].get(run_id, {"artifacts": []})
        else:
            self.send_response(404)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.end_headers()
        self.wfile.write(json.dumps(body).encode())

    def log_message(self, *args):
        pass


class BaseCoverageMapRunTest(unittest.TestCase):
    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {"runs": [], "artifacts": {}}
        self.server.requests = []
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.saved_env = {name: os.environ.get(name) for name in self.ENV}
        os.environ["GITHUB_API_URL"] = self.base_url
        os.environ["GITHUB_REPOSITORY"] = SLUG
        os.environ["GITHUB_TOKEN"] = "test-token"
        self.base = "a" * 40

    def tearDown(self):
        self.server.shutdown()
        self.thread.join()
        for name, value in self.saved_env.items():
            if value is None:
                del os.environ[name]
            else:
                os.environ[name] = value

    def run_lookup(self, base):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = lookup.main([base])
        self.assertEqual(code, 0, err.getvalue())
        return out.getvalue(), err.getvalue()

    def test_prints_the_successful_run_that_uploaded_the_map(self):
        self.server.routes["runs"] = {
            "workflow_runs": [
                {"id": 2, "head_sha": self.base, "conclusion": "failure"},
                {"id": 1, "head_sha": self.base, "conclusion": "success"},
            ]
        }
        self.server.routes["artifacts"] = {
            "1": {"artifacts": [{"name": "coverage-exec-core"}, {"name": "coverage-map"}]}
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out.strip(), "1", err)

    def test_the_map_rule_reads_the_expired_flag(self):
        # A listed artifact always carries `expired`, so a live map is `expired: false`. The
        # absent case is read as live only defensively, as the lookup did before it read the
        # flag; `expired: true` is listed only in the minutes before GitHub prunes the artifact,
        # which is the race this rule guards.
        self.assertTrue(lookup.carries_a_map({"name": "coverage-map", "expired": False}))
        self.assertTrue(lookup.carries_a_map({"name": "coverage-map"}))
        self.assertFalse(lookup.carries_a_map({"name": "coverage-map", "expired": True}))
        self.assertFalse(lookup.carries_a_map({"name": "coverage-exec-core", "expired": False}))

    def test_a_map_expiring_mid_prune_is_skipped_and_the_next_run_is_used(self):
        # `expired: true` on a listed entry is real only in the minutes between the artifact's
        # expiry and GitHub pruning it from the listing. When a newer run is caught there, the
        # older successful run with a live map is the base the pull request carries over from.
        self.server.routes["runs"] = {
            "workflow_runs": [
                {"id": 2, "head_sha": self.base, "conclusion": "success"},
                {"id": 1, "head_sha": self.base, "conclusion": "success"},
            ]
        }
        self.server.routes["artifacts"] = {
            "2": {"artifacts": [{"name": "coverage-map", "expired": True}]},
            "1": {"artifacts": [{"name": "coverage-map", "expired": False}]},
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out.strip(), "1", err)

    def test_a_map_expiring_mid_prune_only_answers_nothing(self):
        # The same race with no older map: the answer is the no-map fallback, which runs every
        # coverage test. A routine expiry is pruned from the listing instead, which
        # test_a_run_without_the_map_artifact_answers_nothing covers.
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 1, "head_sha": self.base, "conclusion": "success"}]
        }
        self.server.routes["artifacts"] = {
            "1": {"artifacts": [{"name": "coverage-map", "expired": True}]}
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("uploaded a coverage-map artifact", err)

    def test_a_run_that_failed_answers_nothing(self):
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 2, "head_sha": self.base, "conclusion": "failure"}]
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("no successful run", err)

    def test_a_run_without_the_map_artifact_answers_nothing(self):
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 1, "head_sha": self.base, "conclusion": "success"}]
        }
        self.server.routes["artifacts"] = {"1": {"artifacts": [{"name": "coverage-exec-core"}]}}
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("uploaded a coverage-map artifact", err)

    def test_a_successful_run_without_the_map_does_not_hide_a_later_one(self):
        # A Release dispatch on main is successful without building the coverage data, so the
        # run to read is the first successful one that has the artifact, not the first success.
        self.server.routes["runs"] = {
            "workflow_runs": [
                {"id": 2, "head_sha": self.base, "conclusion": "success"},
                {"id": 1, "head_sha": self.base, "conclusion": "success"},
            ]
        }
        self.server.routes["artifacts"] = {"1": {"artifacts": [{"name": "coverage-map"}]}}
        out, err = self.run_lookup(self.base)
        self.assertEqual(out.strip(), "1", err)

    def test_no_run_of_the_base_answers_nothing(self):
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 1, "head_sha": "0" * 40, "conclusion": "success"}]
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("no successful run", err)

    def test_the_lookup_names_the_base_commit_and_the_branch(self):
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 1, "head_sha": self.base, "conclusion": "success"}]
        }
        self.server.routes["artifacts"] = {"1": {"artifacts": [{"name": "coverage-map"}]}}
        out, err = self.run_lookup(self.base)
        self.assertEqual(out.strip(), "1", err)
        runs_query = self.server.requests[0]
        self.assertIn(f"head_sha={self.base}", runs_query)
        self.assertIn("branch=main", runs_query)
        # An instance may match a created date exactly rather than as "created at or after",
        # so the lookup names the commit instead of relying on a date filter.
        self.assertNotIn("created=", runs_query)

    def test_a_read_error_answers_nothing(self):
        self.server.routes["runs"] = None  # the handler answers 500 for the runs endpoint
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("the GitHub API could not be read", err)


if __name__ == "__main__":
    unittest.main()
