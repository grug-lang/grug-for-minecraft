#!/usr/bin/env python3
"""Tests main-dispatch.py: the check dispatches when due, whatever else is running (#1089, #1463)."""

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
    """Serves the dispatch listing and the dispatch it may send.

    A status listing is answered too, with whatever live runs the test set, so a check that
    reinstated the busy read sees them and a test that expects a dispatch fails.
    """

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
        if query.get("event") == ["workflow_dispatch"]:
            kind = "dispatches"
        elif query.get("status"):
            kind = "live"
        else:
            self._reply(400)
            return
        if kind in self.server.fail:
            self._reply(500)
            return
        runs = self.server.routes[kind]
        if kind == "dispatches" and self.server.sequence:
            runs = self.server.sequence.pop(0)
        self._reply(200, {"total_count": len(runs), "workflow_runs": runs})

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
    """Pins the check's decisions.

    Two cases are the causal pins for the removed busy read, failing if a run in flight holds a due
    check off or the script asks GitHub for a live status listing again:
    test_due_with_a_run_live_on_another_branch_dispatches and test_the_check_reads_no_live_run_list.
    The other cases pin the age rule and the reason it prints, which a reinstated busy rule would
    change, but they dispatch or hold off the same way under either rule.
    """

    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {"dispatches": [], "live": []}
        self.server.fail = set()
        self.server.refuse_dispatch = False
        self.server.requests = []
        # The dispatch listing the check reads second, so a test can show a run another path
        # created between the two reads.
        self.server.sequence = []
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

    # The cases the check exists for.

    def test_due_dispatches(self):
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("the four-hourly check is due", out)
        self.assertIn("dispatched build.yml on main", out)

    def test_due_with_a_run_live_on_another_branch_dispatches(self):
        # A proof or a pull request in flight is no reason to wait: a due tick dispatches and its
        # jobs queue behind that run. Waiting for an idle pool starved the check for 106 hours
        # (#1463), so a check that reads the live run and still dispatches is the rule under test.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.routes["live"] = [run(11, datetime.timedelta(minutes=20))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("queue behind any run in flight", out)
        self.assertIn("dispatched build.yml on main", out)

    def test_a_full_build_past_twice_the_interval_dispatches(self):
        # This pins the reason and the top of the age range, not the busy rule: a build this old
        # dispatches under either rule, so only the "at least the interval" text below tells them
        # apart, and the causal pins are the two cases the class docstring names.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=9))]
        self.server.routes["live"] = [run(11, datetime.timedelta(minutes=20))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [{"ref": "main"}])
        self.assertIn("at least the 4 h 0 min interval", out)
        self.assertIn("dispatched build.yml on main", out)

    def test_a_dispatch_created_during_the_check_is_not_doubled(self):
        # Another tick, or the orchestrator's own dispatch, can create the dispatch this check
        # measured between its read and its POST; the newest is read once more, and a changed one
        # cancels this check's dispatch (#1463).
        old = run(10, datetime.timedelta(hours=9))
        self.server.routes["dispatches"] = [old]
        self.server.sequence = [[old], [run(12, datetime.timedelta(minutes=1))]]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("appeared while this check read the run lists", out)

    def test_a_not_due_check_with_a_run_live_dispatches_nothing(self):
        # This pins the not-due reason, not the busy rule: neither rule dispatches here, so only the
        # "younger than the interval" text tells them apart, and the causal pins are the two cases
        # the class docstring names.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=1))]
        self.server.routes["live"] = [run(11, datetime.timedelta(minutes=1))]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("younger than the 4 h 0 min interval", out)

    # The edges around those cases.

    def test_the_check_reads_no_live_run_list(self):
        # The busy read is gone on purpose: the check must not let another run hold it off, so it
        # never asks GitHub for a live status listing.
        self.server.routes["dispatches"] = [run(10, datetime.timedelta(hours=5))]
        self.server.routes["live"] = [run(11, datetime.timedelta(minutes=5))]
        code, _, err = self.run_script()
        self.assertEqual(code, 0, err)
        queries = [q for method, _, q, _ in self.server.requests if method == "GET"]
        self.assertFalse(any(q.get("status") for q in queries))

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
        due, reason = main_dispatch.should_dispatch(now, [latest])
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

    def test_an_unreadable_run_list_fails_without_dispatching(self):
        self.server.fail = {"dispatches"}
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.dispatched(), [])
        self.assertIn("could not read the build.yml run list", err)
        self.assertIn("dispatched nothing", err)

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


if __name__ == "__main__":
    unittest.main()
