#!/usr/bin/env python3
"""Tests drain_caches.py: a merged pull request's two refs lose every entry, and nothing else does."""

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
from urllib.parse import parse_qs, urlparse

HERE = Path(__file__).resolve().parent

SPEC = importlib.util.spec_from_file_location("drain_caches", HERE / "drain-caches.py")
drain_caches = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(drain_caches)

SLUG = "test/repo"
NUMBER = 825
MERGE_REF = f"refs/pull/{NUMBER}/merge"
BRANCH_REF = "refs/heads/g825"


def entry(cache_id, ref, size=19_500_000, key="pre-commit-Linux-abc"):
    return {"id": cache_id, "ref": ref, "size_in_bytes": size, "key": key}


def merged_pull(head_repo=SLUG, head_ref="g825", base_ref="main", merged_at="2026-11-01T10:00:00Z"):
    return {
        "number": NUMBER,
        "merged_at": merged_at,
        "head": {"ref": head_ref, "repo": {"full_name": head_repo} if head_repo else None},
        "base": {"ref": base_ref},
    }


class ApiHandler(http.server.BaseHTTPRequestHandler):
    """Serves the two endpoints the script reads and the one it deletes."""

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
        self.server.requests.append(("GET", parsed.path, query))
        if parsed.path == f"/repos/{SLUG}/pulls/{NUMBER}":
            if self.server.routes["pull_error"]:
                self._reply(500)
            else:
                self._reply(200, self.server.routes["pull"])
            return
        if parsed.path == f"/repos/{SLUG}/actions/caches":
            ref = query.get("ref", [""])[0]
            page = int(query.get("page", ["1"])[0])
            entries = self.server.routes["caches"].get(ref, [])
            start = (page - 1) * drain_caches.PER_PAGE
            self._reply(
                200,
                {
                    "total_count": len(entries),
                    "actions_caches": entries[start : start + drain_caches.PER_PAGE],
                },
            )
            return
        self._reply(404)

    def do_DELETE(self):
        parsed = urlparse(self.path)
        self.server.requests.append(("DELETE", parsed.path, {}))
        cache_id = int(parsed.path.rsplit("/", 1)[1])
        if cache_id in self.server.routes["refuse"]:
            self._reply(500)
            return
        if cache_id in self.server.routes["vanished"]:
            self._reply(404)
            return
        for ref, entries in self.server.routes["caches"].items():
            for candidate in list(entries):
                if candidate["id"] == cache_id:
                    entries.remove(candidate)
                    self._reply(204)
                    return
        self._reply(404)

    def log_message(self, *args):
        pass


class DrainCachesTest(unittest.TestCase):
    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {
            "pull": merged_pull(),
            "caches": {
                MERGE_REF: [entry(1, MERGE_REF), entry(2, MERGE_REF)],
                BRANCH_REF: [entry(3, BRANCH_REF, size=1_000_000)],
            },
            "refuse": set(),
            "vanished": set(),
            "pull_error": False,
        }
        self.server.requests = []
        self.thread = threading.Thread(target=self.server.serve_forever)
        self.thread.start()
        self.tmp = tempfile.TemporaryDirectory()
        self.saved_env = {name: os.environ.get(name) for name in self.ENV}
        os.environ["GITHUB_API_URL"] = f"http://127.0.0.1:{self.server.server_address[1]}"
        os.environ["GITHUB_REPOSITORY"] = SLUG
        os.environ["GITHUB_TOKEN"] = "test-token"

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.tmp.cleanup()
        for name, value in self.saved_env.items():
            if value is None:
                os.environ.pop(name, None)
            else:
                os.environ[name] = value

    def run_script(self, *args):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = drain_caches.main([str(NUMBER), *args])
        return code, out.getvalue(), err.getvalue()

    def run_event(self, *args, pull_requests=None):
        """Run the second pass, with the payload a completed run would hand the workflow."""
        event = {
            "event": "pull_request",
            "head_branch": "g825",
            "pull_requests": [{"number": NUMBER}] if pull_requests is None else pull_requests,
        }
        payload = Path(self.tmp.name) / "event.json"
        payload.write_text(json.dumps({"workflow_run": event}))
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = drain_caches.main(["--run-event", str(payload), *args])
        return code, out.getvalue(), err.getvalue()

    def deleted(self):
        return [path for method, path, _ in self.server.requests if method == "DELETE"]

    def test_deletes_both_refs_of_a_merged_pull_request(self):
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(
            self.deleted(),
            [
                f"/repos/{SLUG}/actions/caches/1",
                f"/repos/{SLUG}/actions/caches/2",
                f"/repos/{SLUG}/actions/caches/3",
            ],
        )
        self.assertIn(f"{MERGE_REF}: 2 entries, 39.0 MB deleted", out)
        self.assertIn(f"{BRANCH_REF}: 1 entries, 1.0 MB deleted", out)
        self.assertIn("3 entries, 40.0 MB deleted, 0 already gone", out)

    def test_a_fork_pull_request_drains_only_the_merge_ref(self):
        self.server.routes["pull"] = merged_pull(head_repo="someone/else")
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(
            self.deleted(),
            [f"/repos/{SLUG}/actions/caches/1", f"/repos/{SLUG}/actions/caches/2"],
        )
        self.assertNotIn(BRANCH_REF, out)

    def test_a_pull_request_without_its_head_repository_drains_only_the_merge_ref(self):
        self.server.routes["pull"] = merged_pull(head_repo=None)
        code, _, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertNotIn(f"/repos/{SLUG}/actions/caches/3", self.deleted())

    def test_a_head_branch_named_like_the_default_branch_is_never_drained(self):
        # The base is deliberately not main. With main as both, refs_to_drain returns at branch ==
        # base before PROTECTED_REFS is reached, which is how this test passed while the guard could
        # be deleted and every test stayed green.
        self.server.routes["pull"] = merged_pull(head_ref="main", base_ref="release-x")
        self.server.routes["caches"]["refs/heads/main"] = [entry(9, "refs/heads/main")]
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertNotIn(f"/repos/{SLUG}/actions/caches/9", self.deleted())
        self.assertIn(f"/repos/{SLUG}/actions/caches/1", self.deleted())
        self.assertNotIn("refs/heads/main", out)

    def test_a_head_branch_that_is_the_base_is_not_named(self):
        # A pull request cannot have its base as its head. The early return is what stops a malformed
        # event from naming it, and this keeps that line reachable now that the test above does not.
        self.server.routes["pull"] = merged_pull(head_ref="release-x", base_ref="release-x")
        self.server.routes["caches"]["refs/heads/release-x"] = [entry(9, "refs/heads/release-x")]
        code, _, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(
            self.deleted(),
            [f"/repos/{SLUG}/actions/caches/1", f"/repos/{SLUG}/actions/caches/2"],
        )

    def test_an_unmerged_pull_request_deletes_nothing(self):
        self.server.routes["pull"] = merged_pull(merged_at=None)
        code, _, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.deleted(), [])
        self.assertIn("has not merged", err)

    def test_a_dry_run_deletes_nothing(self):
        code, out, err = self.run_script("--dry-run")
        self.assertEqual(code, 0, err)
        self.assertEqual(self.deleted(), [])
        self.assertIn("3 entries, 40.0 MB would delete, 0 already gone", out)

    def test_pages_through_more_entries_than_one_page(self):
        many = [entry(1000 + i, MERGE_REF, size=1) for i in range(drain_caches.PER_PAGE + 1)]
        self.server.routes["caches"][MERGE_REF] = many
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertEqual(len(self.deleted()), drain_caches.PER_PAGE + 1 + 1)
        self.assertIn(f"{MERGE_REF}: {drain_caches.PER_PAGE + 1} entries", out)
        pages = [q.get("page", [""])[0] for m, p, q in self.server.requests if m == "GET"]
        self.assertIn("2", pages)

    def test_an_entry_the_cap_evicted_is_already_gone(self):
        # The listing holds an entry that the deletion no longer finds, as the cap evicting it in
        # between does, which is not a failed drain.
        self.server.routes["vanished"] = {1}
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn(f"{MERGE_REF}: 1 entries, 19.5 MB deleted", out)
        self.assertIn("2 entries, 20.5 MB deleted, 1 already gone", out)

    def test_a_refused_deletion_fails_the_run(self):
        self.server.routes["refuse"] = {2}
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("could not be deleted", err)
        self.assertIn("1 entries could not be deleted", err)
        # The other entries are still drained.
        self.assertIn(f"/repos/{SLUG}/actions/caches/1", self.deleted())
        self.assertIn(f"/repos/{SLUG}/actions/caches/3", self.deleted())

    def test_an_unreadable_api_fails_the_run(self):
        self.server.routes["pull_error"] = True
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("stopped", err)
        self.assertEqual(self.deleted(), [])

    def test_a_missing_repository_name_fails_the_run(self):
        del os.environ["GITHUB_REPOSITORY"]
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("GITHUB_REPOSITORY is not set", err)
        self.assertEqual(self.deleted(), [])

    def test_pull_requests_in_run_reads_the_run_payload(self):
        event = {
            "workflow_run": {
                "pull_requests": [
                    {"number": 933},
                    {"number": 934},
                    {"number": 933},
                    {},
                    {"number": "935"},
                ]
            }
        }
        self.assertEqual(drain_caches.pull_requests_in_run(event), [933, 934])
        self.assertEqual(
            drain_caches.pull_requests_in_run({"workflow_run": {"pull_requests": []}}), []
        )
        self.assertEqual(drain_caches.pull_requests_in_run({"workflow_run": {}}), [])
        self.assertEqual(drain_caches.pull_requests_in_run({}), [])

    def test_a_run_of_a_merged_pull_request_drains_its_refs_again(self):
        # The run that saved a cache after the merge finished, which is the second pass's whole job.
        code, out, err = self.run_event()
        self.assertEqual(code, 0, err)
        self.assertEqual(
            self.deleted(),
            [
                f"/repos/{SLUG}/actions/caches/1",
                f"/repos/{SLUG}/actions/caches/2",
                f"/repos/{SLUG}/actions/caches/3",
            ],
        )
        self.assertIn(f"{MERGE_REF}: 2 entries, 39.0 MB deleted", out)

    def test_a_run_of_an_unmerged_pull_request_drains_nothing(self):
        self.server.routes["pull"] = merged_pull(merged_at=None)
        code, _, err = self.run_event()
        self.assertEqual(code, 0, err)
        self.assertEqual(self.deleted(), [])
        self.assertIn("has not merged", err)

    def test_a_run_that_names_no_pull_request_drains_nothing(self):
        code, _, err = self.run_event(pull_requests=[])
        self.assertEqual(code, 0, err)
        self.assertEqual(self.deleted(), [])
        self.assertIn("names no pull request", err)

    def test_a_run_event_fails_when_a_deletion_is_refused(self):
        self.server.routes["refuse"] = {2}
        code, out, err = self.run_event()
        self.assertEqual(code, 1)
        self.assertIn("could not be deleted", err)
        self.assertIn(f"/repos/{SLUG}/actions/caches/1", self.deleted())


class ThrowawayGateCheck(unittest.TestCase):
    def test_fails_on_purpose(self):
        self.fail("throwaway PR that proves pr gate blocks a failure (#638)")


if __name__ == "__main__":
    unittest.main()
