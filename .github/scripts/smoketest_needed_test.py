#!/usr/bin/env python3
"""Tests smoketest-needed.py: only a diff of the smoketest itself runs the job on a pull request."""

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

    def test_a_change_to_the_smoketest_runs_it(self):
        for path in (".github/smoketest/smoketest.c", ".github/smoketest/new.py"):
            with self.subTest(path=path):
                self.assertEqual(self.answer({path: "changed\n"}), "true")
                self.assertIn("running", self.reason)

    def test_anything_else_skips_it(self):
        for path in (
            "README.md",
            "core/src/Main.java",
            "mods/examplemod/code/foo-Block.grug",
            "mods/coverage/tests/frame-Test.grug",
            "mod_api.json",
            ".github/smoketest.md",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.answer({path: "changed\n"}), "false")
                self.assertIn("skipping", self.reason)

    def test_one_path_among_others_runs_it(self):
        self.assertEqual(
            self.answer({"README.md": "x\n", ".github/smoketest/run.sh": "#\n"}), "true"
        )

    def test_a_doubt_skips_it(self):
        self.assertEqual(self.answer({"README.md": "x\n"}, base="0" * 40), "false")
        self.assertEqual(self.answer({"README.md": "x\n"}, base=self.base), "false")


if __name__ == "__main__":
    unittest.main()
