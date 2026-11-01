#!/usr/bin/env python3
"""Tests sweep-caches.py: dead refs lose their entries, and main and open pull requests never do."""

import contextlib
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

SPEC = importlib.util.spec_from_file_location("sweep_caches", HERE / "sweep-caches.py")
sweep_caches = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sweep_caches)

SLUG = "test/repo"


def entry(cache_id, ref, size=1_000_000):
    return {"id": cache_id, "ref": ref, "size_in_bytes": size, "key": f"k{cache_id}"}


def ids(entries):
    return [e["id"] for e in entries]


class DecisionTest(unittest.TestCase):
    def decide(self, entries, open_pulls=(), branches=("main",)):
        return ids(sweep_caches.entries_to_delete(entries, set(open_pulls), set(branches)))

    def test_empty_store_deletes_nothing(self):
        self.assertEqual(self.decide([]), [])

    def test_main_is_never_deleted_even_when_the_branch_list_omits_it(self):
        entries = [entry(1, "refs/heads/main"), entry(2, "refs/heads/master")]
        self.assertEqual(self.decide(entries, branches=()), [])

    def test_open_pull_request_is_kept(self):
        self.assertEqual(self.decide([entry(1, "refs/pull/7/merge")], open_pulls={7}), [])

    def test_closed_pull_request_is_deleted(self):
        entries = [entry(1, "refs/pull/7/merge"), entry(2, "refs/pull/8/merge")]
        self.assertEqual(self.decide(entries, open_pulls={8}), [1])

    def test_pull_number_is_matched_whole(self):
        self.assertEqual(self.decide([entry(1, "refs/pull/17/merge")], open_pulls={7}), [1])
        self.assertEqual(self.decide([entry(1, "refs/pull/7/merge")], open_pulls={17}), [1])

    def test_deleted_branch_is_deleted_and_live_branch_kept(self):
        entries = [entry(1, "refs/heads/gone"), entry(2, "refs/heads/live")]
        self.assertEqual(self.decide(entries, branches={"main", "live"}), [1])

    def test_slash_branch_names_are_matched_whole(self):
        entries = [entry(1, "refs/heads/feature/x"), entry(2, "refs/heads/feature")]
        self.assertEqual(self.decide(entries, branches={"feature/x"}), [2])

    def test_other_refs_are_left_alone(self):
        entries = [entry(1, "refs/tags/v1"), entry(2, "refs/pull/7/head"), entry(3, "")]
        self.assertEqual(self.decide(entries), [])

    def test_entry_without_a_ref_key_is_left_alone(self):
        self.assertEqual(self.decide([{"id": 1, "size_in_bytes": 1, "key": "k"}]), [])


class ApiHandler(http.server.BaseHTTPRequestHandler):
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
        page = int(query["page"][0])
        per = int(query["per_page"][0])
        routes = self.server.routes
        self.server.requests.append(("GET", parsed.path))
        if routes["list_error"]:
            self._reply(500)
            return
        if parsed.path == f"/repos/{SLUG}/actions/caches":
            items = routes["caches"]
            self._reply(
                200,
                {"total_count": len(items), "actions_caches": items[(page - 1) * per : page * per]},
            )
        elif parsed.path == f"/repos/{SLUG}/pulls":
            assert query["state"] == ["open"]
            items = [{"number": n} for n in routes["pulls"]]
            self._reply(200, items[(page - 1) * per : page * per])
        elif parsed.path == f"/repos/{SLUG}/branches":
            items = [{"name": b} for b in routes["branches"]]
            self._reply(200, items[(page - 1) * per : page * per])
        else:
            self._reply(404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        self.server.requests.append(("DELETE", parsed.path))
        cache_id = int(parsed.path.rsplit("/", 1)[1])
        if cache_id in self.server.routes["refuse"]:
            self._reply(500)
        elif cache_id in self.server.routes["vanished"]:
            self._reply(404)
        else:
            self._reply(204)

    def log_message(self, *args):
        pass


class SweepTest(unittest.TestCase):
    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {
            "caches": [
                entry(1, "refs/heads/main"),
                entry(2, "refs/pull/7/merge"),
                entry(3, "refs/pull/8/merge", size=2_000_000),
                entry(4, "refs/heads/gone", size=3_000_000),
            ],
            "pulls": [7],
            "branches": ["main"],
            "refuse": set(),
            "vanished": set(),
            "list_error": False,
        }
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
            code = sweep_caches.main(list(args))
        return code, out.getvalue(), err.getvalue()

    def deleted(self):
        return [p.rsplit("/", 1)[1] for m, p in self.server.requests if m == "DELETE"]

    def test_deletes_the_dead_entries_and_reports_them(self):
        code, out, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), ["3", "4"])
        self.assertIn("refs/pull/8/merge: 1 entries, 2.0 MB deleted", out)
        self.assertIn("refs/heads/gone: 1 entries, 3.0 MB deleted", out)
        self.assertIn("2 entries, 5.0 MB deleted, 0 already gone", out)

    def test_dry_run_deletes_nothing(self):
        code, out, _ = self.run_script("--dry-run")
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), [])
        self.assertIn("2 entries, 5.0 MB would delete", out)

    def test_pagination_boundaries(self):
        per = sweep_caches.PER_PAGE
        for count in (per - 1, per, per + 1, 2 * per, 2 * per + 1):
            with self.subTest(count=count):
                self.server.routes["caches"] = [
                    entry(i, f"refs/pull/{1000 + i}/merge") for i in range(1, count + 1)
                ]
                self.server.routes["pulls"] = [1000 + i for i in range(1, count + 1, 2)]
                self.server.requests.clear()
                code, out, _ = self.run_script("--dry-run")
                self.assertEqual(code, 0)
                dead = count // 2
                self.assertIn(f"{count} entries, {len(self.server.routes['pulls'])} open", out)
                self.assertIn(f"{dead} dead", out)

    def test_pull_requests_and_branches_are_paged_too(self):
        per = sweep_caches.PER_PAGE
        self.server.routes["pulls"] = list(range(1, per + 2))
        self.server.routes["branches"] = ["main"] + [f"b{i}" for i in range(per + 1)]
        self.server.routes["caches"] = [
            entry(1, f"refs/pull/{per + 1}/merge"),
            entry(2, f"refs/heads/b{per}"),
            entry(3, "refs/pull/999/merge"),
        ]
        code, _, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), ["3"])

    def test_an_entry_the_cap_evicted_is_already_gone(self):
        self.server.routes["vanished"] = {3}
        code, out, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertIn("1 entries, 3.0 MB deleted, 1 already gone", out)

    def test_a_refused_deletion_fails_but_sweeps_the_rest(self):
        self.server.routes["refuse"] = {3}
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.deleted(), ["3", "4"])
        self.assertIn("could not be deleted", err)

    def test_a_listing_failure_deletes_nothing(self):
        self.server.routes["list_error"] = True
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.deleted(), [])
        self.assertIn("stopped", err)

    def test_missing_repository_deletes_nothing(self):
        os.environ["GITHUB_REPOSITORY"] = ""
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.deleted(), [])
        self.assertIn("GITHUB_REPOSITORY", err)


if __name__ == "__main__":
    unittest.main()
