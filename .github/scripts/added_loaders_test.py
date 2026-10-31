#!/usr/bin/env python3
"""Tests added-loaders.py: only a push that adds a loader directory runs the Windows jobs."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "added-loaders.py"
SPEC = importlib.util.spec_from_file_location("added_loaders", SCRIPT)
added_loaders = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(added_loaders)

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

BASE = {
    "loaders/1.2.5-forge/build.gradle": "// 1.2.5\n",
    "loaders/1.2.5-forge/src/Main.java": "class Main {}\n",
    "loaders/1.20.6-forge/build.gradle": "// 1.20.6\n",
    "loaders/1.20.6-forge/src/Main.java": "class Main {}\n",
    "core/src/Main.java": "class Main {}\n",
    "mods/coverage/about.json": "{}",
}


class AddedLoadersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes: dict, before=None) -> list[str]:
        """The script's answer for a commit with these changes."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), before or self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return json.loads(result.stdout)

    def test_a_commit_that_adds_no_loader_runs_no_windows_job(self):
        self.assertEqual(self.answer({"core/src/Main.java": "class Main { }\n"}), [])
        self.assertIn("adds no loader", self.reason)

    def test_a_file_added_to_an_existing_loader_adds_no_loader(self):
        patch = {"loaders/1.2.5-forge/src/Patch.java": "class Patch {}\n"}
        self.assertEqual(self.answer(patch), [])

    def test_a_new_loader_directory_is_added(self):
        changes = {
            "loaders/b1.7.3-fabric/build.gradle": "// b1.7.3\n",
            "loaders/b1.7.3-fabric/src/Main.java": "class Main {}\n",
        }
        self.assertEqual(self.answer(changes), ["b1.7.3-fabric"])
        self.assertIn("b1.7.3-fabric", self.reason)

    def test_two_new_loaders_come_out_sorted(self):
        changes = {
            "loaders/1.21-fabric/build.gradle": "// 1.21\n",
            "loaders/1.21-fabric/src/Main.java": "class Main {}\n",
            "loaders/alpha-fabric/build.gradle": "// alpha\n",
            "loaders/alpha-fabric/src/Main.java": "class Main {}\n",
        }
        self.assertEqual(self.answer(changes), ["1.21-fabric", "alpha-fabric"])

    def test_a_file_straight_in_loaders_is_no_loader(self):
        self.assertEqual(self.answer({"loaders/README.md": "readme\n"}), [])

    def test_a_deleted_loader_adds_none(self):
        self.assertEqual(self.answer({"loaders/1.20.6-forge/build.gradle": None}), [])

    def test_a_moved_loader_counts_at_its_new_name(self):
        moved = {
            "loaders/1.20.6-forge/build.gradle": None,
            "loaders/1.20.6-forge/src/Main.java": None,
            "loaders/1.20.6-forge2/build.gradle": "// 1.20.6\n",
            "loaders/1.20.6-forge2/src/Main.java": "class Main {}\n",
        }
        self.assertEqual(self.answer(moved), ["1.20.6-forge2"])

    def test_a_loader_added_and_removed_in_one_push_adds_none(self):
        loader = {
            "loaders/tmp-fabric/build.gradle": "// tmp\n",
            "loaders/tmp-fabric/src/Main.java": "class Main {}\n",
        }
        self.repo.commit(loader, "add a loader")
        head = self.repo.commit(
            {"loaders/tmp-fabric/build.gradle": None, "loaders/tmp-fabric/src/Main.java": None},
            "drop it again",
        )
        result = subprocess.run(
            [sys.executable, str(SCRIPT), self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(json.loads(result.stdout), [])

    def test_an_unreachable_before_runs_every_loader(self):
        answer = self.answer({"core/src/Main.java": "class Main { }\n"}, before="0000000")
        self.assertEqual(answer, ["1.2.5-forge", "1.20.6-forge"])
        self.assertIn("every loader runs", self.reason)


if __name__ == "__main__":
    unittest.main()
