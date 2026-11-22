#!/usr/bin/env python3
"""Tests smoketest-needed.py: only a diff that reaches what the smoketest reads runs the job."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "smoketest-needed.py"
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

BASE = {
    "README.md": "readme\n",
    "mod_api.json": "{}",
    "core/src/Main.java": "class Main {}\n",
    ".github/smoketest/smoketest.c": "int x;\n",
    "mods/coverage/tests/frame-Test.grug": "tick() {}\n",
    "mods/examplemod/code/foo-Block.grug": "on_tick() {}\n",
}


class SmoketestNeededTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes, base=None):
        self.repo.git("reset", "-q", "--hard", self.base)
        head = self.repo.commit(changes)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), base or self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
            check=True,
        )
        self.reason = result.stderr
        return result.stdout.strip()

    def test_what_the_smoketest_reads_runs_it(self):
        for path in (
            ".github/smoketest/smoketest.c",
            ".github/smoketest/new.py",
            "mods/coverage/tests/frame-Test.grug",
            "mods/coverage/code/new-Block.grug",
            "mod_api.json",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.answer({path: "changed\n"}), "true")
                self.assertIn("running", self.reason)

    def test_anything_else_skips_it(self):
        for path in (
            "README.md",
            "core/src/Main.java",
            "mods/examplemod/code/foo-Block.grug",
            "mod_api.json.bak",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.answer({path: "changed\n"}), "false")
                self.assertIn("skipping", self.reason)

    def test_one_path_among_others_runs_it(self):
        self.assertEqual(self.answer({"README.md": "x\n", "mod_api.json": "[]"}), "true")

    def test_a_doubt_skips_it(self):
        self.assertEqual(self.answer({"README.md": "x\n"}, base="0" * 40), "false")
        self.assertEqual(self.answer({"README.md": "x\n"}, base=self.base), "false")


if __name__ == "__main__":
    unittest.main()
