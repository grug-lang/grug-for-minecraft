#!/usr/bin/env python3
"""Tests main-dispatch.py: the four-hourly check dispatches only when due and idle (#1089)."""

import contextlib
import datetime
import http.server
import importlib.util
import io
import json
import os
import threading
import unittest
from pathlib import Path
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent

SPEC = importlib.util.spec_from_file_location("main_dispatch", HERE / "main-dispatch.py")
main_dispatch = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(main_dispatch)

SLUG = "test/repo"
RUNS = f"/repos/{SLUG}/actions/workflows/build.yml/runs"
DISPATCH = f"/repos/{SLUG}/actions/workflows/build.yml/dispatches"


def stamp(delta):
    """A GitHub timestamp the given span from now, in the Z form the API returns."""
    moment = datetime.datetime.now(datetime.timezone.utc) - delta
    return moment.strftime("%Y-%m-%dT%H:%M:%SZ")


def run(run_id, age):
    return {"id": run_id, "created_at": stamp(age), "html_url": f"https://example/{run_id}"}


class ApiHandler(http.server.BaseHTTPRequestHandler):
    """Serves the two listings the decision reads and the dispatch it may send."""

    def _reply(self, status, body=None):
        self.send_response(status)
        if body is not None:
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(json.dumps(body).encode())
        else:
            self.end_headers()

    def do_GET(self):
        parsed = urlparse(self.path)
        query = parse_qs(parsed.query)
        self.server.requests.append(("GET", parsed.path, query, None))
        if parsed.path != RUNS:
            self._reply(404)
            return
        status = query.get("status", [""])[0]
        if query.get("event") == ["workflow_dispatch"]:
            kind = "dispatches"
        elif status in main_dispatch.LIVE_STATUSES:
            kind = status
        else:
            self._reply(400)
            return
        if kind in self.server.fail:
            self._reply(500)
            return
        self._reply(
            200,
            {
                "total_count": len(self.server.routes[kind]),
                "workflow_runs": self.server.routes[kind],
            },
        )

    def do_POST(self):
        parsed = urlparse(self.path)
        length = int(self.headers.get("Content-Length") or 0)
        body = json.loads(self.rfile.read(length) or b"{}")
        self.server.requests.append(("POST", parsed.path, {}, body))
        if parsed.path != DISPATCH:
            self._reply(404)
            return
        if self.server.refuse_dispatch:
            self._reply(500)
            return
        self._reply(204)

    def log_message(self, *args):
        pass


class MainDispatchTest(unittest.TestCase):
    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {"dispatches": []}
        for status in main_dispatch.LIVE_STATUSES:
            self.server.routes[status] = []
        self.server.fail = set()
        self.server.refuse_dispatch = False
        self.server.requests = []
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.saved_env = {name: os.environ.get(name) for name in self.ENV}
        os.environ["GITHUB_API_URL"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        os.environ["GITHUB_REPOSITORY"] = SLUG
        os.environ["GITHUB_TOKEN"] = "test-token"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        for name, value in self.saved_env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def run_script(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = main_dispatch.main(list(args))
        return code, out.getvalue(), err.getvalue()

    def dispatched(self):
        return [body for method, path, _, body in self.server.requests if method == "POST"]

    # The four cases the check exists for.

    def test_due_and_idle_dispatches(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("the four-hourly check is due", out)
        self.assertIn("dispatched build.yml on main", out)

    def test_due_and_busy_dispatches_nothing(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.routes["in_progress"] = [run(11, datetime.timedelta(minutes=20))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("20 job slots", out)
        self.assertNotIn("dispatched build.yml", out)

    def test_recent_and_idle_dispatches_nothing(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=1))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("younger than the 4 h 0 min interval", out)

    def test_recent_and_busy_dispatches_nothing(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=1))]
        self.server.routes["queued"] = [run(11, datetime.timedelta(minutes=1))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("are live", out)

    def test_every_live_status_blocks_the_dispatch(self):
        # A run created between the dispatch read and the busy read is often still requested, and
        # GitHub also reports waiting and pending, so every live status counts as in flight.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        for status in main_dispatch.LIVE_STATUSES:
            with self.subTest(status=status):
                for other in main_dispatch.LIVE_STATUSES:
                    self.server.routes[other] = []
                self.server.routes[status] = [run(11, datetime.timedelta(minutes=5))]
                self.server.requests = []
                code, out, err = self.run_script()
                self.assertEqual(code, 0, err)
                self.assertEqual(self.dispatched(), [])
                self.assertIn("are live", out)

    def test_a_run_returned_under_two_statuses_is_counted_once(self):
        # A run moves from requested to queued between the listings, so the same id can come back
        # twice; the reason must still name one run.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.routes["requested"] = [run(11, datetime.timedelta(minutes=1))]
        self.server.routes["queued"] = [run(11, datetime.timedelta(minutes=1))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("1 build.yml run(s) are live", out)

    # The edges around those four.

    def test_no_dispatch_ever_is_due(self):
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("has no build.yml dispatch yet", out)

    def test_the_newest_dispatch_wins_whatever_the_listing_order(self):
        self.server.routes["dispatches"] = [
            run(9, datetime.timedelta(hours=6)),
            run(10, datetime.timedelta(hours=1)),
        ]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("younger than the 4 h 0 min interval", out)

    def test_a_recent_sunday_release_dispatch_holds_the_check_off(self):
        # The release dispatches build.yml on main, so its run is a workflow_dispatch like any other
        # and a recent one is exactly what the check must not duplicate.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=2))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("younger than the 4 h 0 min interval", out)

    def test_a_dispatch_exactly_one_interval_old_is_due(self):
        now = datetime.datetime.now(datetime.timezone.utc)
        latest = {"id": 10, "created_at": (now - datetime.timedelta(hours=4)).isoformat()}
        due, reason = main_dispatch.should_dispatch(now, [latest], [])
        self.assertTrue(due, reason)

    def test_the_interval_can_be_shortened_for_a_verification_run(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=1))]
        code, out, err = self.run_script("--interval-minutes", "30")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("at least the 30 min interval", out)

    def test_a_dry_run_dispatches_nothing(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        code, out, err = self.run_script("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("dry run", out)

    def test_an_unreadable_dispatch_list_fails_without_dispatching(self):
        self.server.fail = {"dispatches"}
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("could not read the build.yml run list", err)
        self.assertIn("dispatched nothing", err)

    def test_an_unreadable_busy_list_fails_without_dispatching(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.fail = {"in_progress"}
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("could not read the build.yml run list", err)

    def test_a_refused_dispatch_fails_the_run(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.refuse_dispatch = True
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("the dispatch was refused", err)

    def test_a_missing_repository_name_fails_the_run(self):
        del os.environ["GITHUB_REPOSITORY"]
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("GITHUB_REPOSITORY is not set", err)

    def test_the_busy_listing_asks_for_every_live_status_and_branch(self):
        # The 20 job slots are shared by every run, so a pull request's run in flight holds the check
        # off too; every live status is read and the status queries name no branch.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.routes["in_progress"] = [run(11, datetime.timedelta(minutes=5))]
        code, _, err = self.run_script()
        self.assertEqual(code, 0, err)
        queries = [q for method, _, q, _ in self.server.requests if method == "GET"]
        statuses = {q["status"][0] for q in queries if q.get("status")}
        self.assertEqual(statuses, set(main_dispatch.LIVE_STATUSES))
        self.assertFalse(any("branch" in q for q in queries if q.get("status")))


if __name__ == "__main__":
    unittest.main()
