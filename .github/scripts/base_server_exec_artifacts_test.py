#!/usr/bin/env python3
"""Tests base_server_exec_artifacts.py: only a live server-exec artifact of the base run is
downloaded, and a base run too old or without a dedicated leg answers nothing (#1602)."""

import contextlib
import importlib.util
import io
import json
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

    def test_main_prints_name_and_url_and_never_fails(self):
        out, err = io.StringIO(), io.StringIO()
        with unittest.mock.patch(
            "sys.stdin", io.StringIO(json.dumps({"artifacts": [art("server-exec-a")]}))
        ):
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(mod.main(), 0)
        self.assertEqual(out.getvalue(), "server-exec-a https://x/server-exec-a.zip\n")
        with unittest.mock.patch("sys.stdin", io.StringIO("garbage")):
            with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
                self.assertEqual(mod.main(), 0)
        self.assertIn("not JSON", err.getvalue())


if __name__ == "__main__":
    unittest.main()
