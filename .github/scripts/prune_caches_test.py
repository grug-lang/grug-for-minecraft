#!/usr/bin/env python3
"""Tests prune-caches.py: main's older generations go, its newest stay, and branches never do."""

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

SPEC = importlib.util.spec_from_file_location("prune_caches", HERE / "prune-caches.py")
prune_caches = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(prune_caches)

SLUG = "test/repo"
HASH = "a" * 64


def entry(cache_id, key, created_at, ref="refs/heads/main", size=1_000_000):
    return {
        "id": cache_id,
        "ref": ref,
        "key": key,
        "created_at": created_at,
        "size_in_bytes": size,
    }


def ids(entries):
    return [e["id"] for e in entries]


class DecisionTest(unittest.TestCase):
    def prune(self, entries, keep=2):
        return ids(prune_caches.entries_to_prune(entries, keep))

    def test_empty_store_prunes_nothing(self):
        self.assertEqual(self.prune([]), [])

    def test_a_lone_entry_is_kept(self):
        self.assertEqual(self.prune([entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20")]), [])

    def test_a_family_at_the_keep_count_is_kept(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21"),
        ]
        self.assertEqual(self.prune(entries), [])

    def test_the_oldest_generation_goes(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20T00:00:00Z"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21T00:00:00Z"),
            entry(3, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z"),
        ]
        self.assertEqual(self.prune(entries), [1])

    def test_the_newest_are_chosen_by_date_not_by_id(self):
        entries = [
            entry(9, f"grug-minecraft-Linux-{HASH}", "2026-11-20T00:00:00Z"),
            entry(8, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z"),
            entry(7, f"grug-minecraft-Linux-{HASH}", "2026-11-21T00:00:00Z"),
        ]
        self.assertEqual(self.prune(entries), [9])

    def test_a_tie_on_the_creation_second_is_broken_by_id(self):
        entries = [
            entry(3, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z"),
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z"),
        ]
        self.assertEqual(self.prune(entries), [1])

    def test_a_branch_keeps_its_generations(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20", ref="refs/heads/some-branch"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21", ref="refs/heads/some-branch"),
            entry(3, f"grug-minecraft-Linux-{HASH}", "2026-11-22", ref="refs/heads/some-branch"),
        ]
        self.assertEqual(self.prune(entries), [])

    def test_a_pull_request_merge_ref_keeps_its_generations(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20", ref="refs/pull/7/merge"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21", ref="refs/pull/7/merge"),
            entry(3, f"grug-minecraft-Linux-{HASH}", "2026-11-22", ref="refs/pull/7/merge"),
        ]
        self.assertEqual(self.prune(entries), [])

    def test_a_key_without_a_hash_is_left_alone(self):
        entries = [
            entry(1, "grug-reference", "2026-11-20"),
            entry(2, "grug-reference", "2026-11-21"),
            entry(3, "grug-reference", "2026-11-22"),
        ]
        self.assertEqual(self.prune(entries), [])

    def test_a_short_hex_tail_is_not_a_generation(self):
        entries = [entry(i, f"grug-key-{i:03d}", f"2026-11-2{i}") for i in range(1, 4)]
        self.assertEqual(self.prune(entries), [])

    def test_two_loaders_of_one_prefix_are_two_families(self):
        forge = [entry(1, f"grug-gradle-Linux-1.20.6-forge-{HASH}", "2026-11-20")]
        fabric = [entry(2, f"grug-gradle-Linux-1.2.5-forge-{HASH}", "2026-11-20")]
        self.assertEqual(self.prune(forge + fabric), [])
        forge.append(entry(3, f"grug-gradle-Linux-1.20.6-forge-{HASH}", "2026-11-21"))
        self.assertEqual(self.prune(forge + fabric), [])

    def test_a_version_suffix_is_not_the_family(self):
        key = f"grug-rs-library-Linux-rustc 1.100.0 (0ef1bc386 2026-11-09)-{HASH}-v1"
        self.assertEqual(
            prune_caches.family(key), "grug-rs-library-Linux-rustc 1.100.0 (0ef1bc386 2026-11-09)"
        )

    def test_the_family_strips_the_hash(self):
        self.assertEqual(
            prune_caches.family(f"grug-minecraft-Linux-1.20.6-forge-{HASH}"),
            "grug-minecraft-Linux-1.20.6-forge",
        )

    def test_keep_one_leaves_only_the_newest(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20T00:00:00Z"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21T00:00:00Z"),
        ]
        self.assertEqual(self.prune(entries, keep=1), [1])

    def test_a_keep_below_one_still_keeps_the_newest(self):
        entries = [
            entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20T00:00:00Z"),
            entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21T00:00:00Z"),
        ]
        self.assertEqual(self.prune(entries, keep=0), [1])


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


class PruneTest(unittest.TestCase):
    ENV = ("GITHUB_API_URL", "GITHUB_REPOSITORY", "GITHUB_TOKEN")

    def setUp(self):
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), ApiHandler)
        self.server.routes = {
            "caches": [
                entry(1, f"grug-minecraft-Linux-{HASH}", "2026-11-20T00:00:00Z", size=1_000_000),
                entry(2, f"grug-minecraft-Linux-{HASH}", "2026-11-21T00:00:00Z", size=2_000_000),
                entry(3, f"grug-minecraft-Linux-{HASH}", "2026-11-22T00:00:00Z", size=3_000_000),
                entry(
                    4,
                    f"grug-gradle-Linux-{HASH}",
                    "2026-11-20T00:00:00Z",
                    ref="refs/heads/live",
                    size=4_000_000,
                ),
            ],
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
            code = prune_caches.main(list(args))
        return code, out.getvalue(), err.getvalue()

    def deleted(self):
        return [p.rsplit("/", 1)[1] for m, p in self.server.requests if m == "DELETE"]

    def test_prunes_the_older_generations_and_reports_them(self):
        code, out, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), ["1"])
        self.assertIn("4 entries, keeping the newest 2 per main family, pruning 1", out)
        self.assertIn("grug-minecraft-Linux: 1 entries, 1.0 MB deleted", out)
        self.assertIn("1 entries, 1.0 MB deleted, 0 already gone", out)

    def test_a_branch_entry_is_never_pruned(self):
        code, _, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertNotIn("4", self.deleted())

    def test_dry_run_deletes_nothing(self):
        code, out, _ = self.run_script("--dry-run")
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), [])
        self.assertIn("1 entries, 1.0 MB would delete", out)

    def test_keep_selects_how_many_generations_stay(self):
        code, out, _ = self.run_script("--keep", "1")
        self.assertEqual(code, 0)
        self.assertEqual(self.deleted(), ["1", "2"])
        self.assertIn("2 entries, 3.0 MB deleted", out)

    def test_pagination_does_not_hide_the_newest_generation(self):
        per = prune_caches.sweep_caches.PER_PAGE
        oldest = [
            entry(i, f"grug-gradle-Linux-{HASH}", f"2026-11-{10 + i // 24:02d}T00:00:00Z")
            for i in range(1, per + 1)
        ]
        newest = entry(per + 1, f"grug-gradle-Linux-{HASH}", "2026-11-24T00:00:00Z")
        self.server.routes["caches"] = oldest + [newest]
        code, out, _ = self.run_script("--dry-run")
        self.assertEqual(code, 0)
        self.assertIn(f"{per + 1} entries, keeping the newest 2 per main family", out)
        self.assertIn(f"{per - 1} entries,", out)

    def test_an_entry_the_cap_evicted_is_already_gone(self):
        self.server.routes["vanished"] = {1}
        code, out, _ = self.run_script()
        self.assertEqual(code, 0)
        self.assertIn("0 entries, 0.0 MB deleted, 1 already gone", out)

    def test_a_refused_deletion_fails_but_prunes_the_rest(self):
        self.server.routes["refuse"] = {1}
        code, _, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertEqual(self.deleted(), ["1"])
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
