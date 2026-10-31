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
import tempfile
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

# affected_mods_test.py has the throwaway repository the test commits the base into.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

SLUG = "test/repo"


class ApiHandler(http.server.BaseHTTPRequestHandler):
    """Serves the two endpoints the lookup reads, from the routes on the test case."""

    def do_GET(self):
        path = urlparse(self.path).path
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
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.base_url = f"http://127.0.0.1:{self.server.server_address[1]}"
        self.saved_env = {name: os.environ.get(name) for name in self.ENV}
        os.environ["GITHUB_API_URL"] = self.base_url
        os.environ["GITHUB_REPOSITORY"] = SLUG
        os.environ["GITHUB_TOKEN"] = "test-token"
        self.repo_tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.repo_tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.repo_tmp.name))
        self.base = self.repo.commit({"README.md": "readme\n"})

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
            code = lookup.main([base, "--repo", str(self.repo.path)])
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
        self.assertIn("uploaded no coverage-map artifact", err)

    def test_no_run_of_the_base_answers_nothing(self):
        self.server.routes["runs"] = {
            "workflow_runs": [{"id": 1, "head_sha": "0" * 40, "conclusion": "success"}]
        }
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("no successful run", err)

    def test_a_read_error_answers_nothing(self):
        self.server.routes["runs"] = None  # the handler answers 500 for the runs endpoint
        out, err = self.run_lookup(self.base)
        self.assertEqual(out, "")
        self.assertIn("the GitHub API could not be read", err)

    def test_a_base_commit_not_in_the_repository_answers_nothing(self):
        # A base that is not in the repository cannot be read for its committer date, and the
        # fallback is the same as every other way to not find a map.
        out, err = self.run_lookup("f" * 40)
        self.assertEqual(out, "")
        self.assertIn("could not be read", err)


if __name__ == "__main__":
    unittest.main()
