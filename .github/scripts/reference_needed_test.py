#!/usr/bin/env python3
"""Tests reference-needed.py: only a change confined to other mods and other loaders skips."""

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "reference-needed.py"
SPEC = importlib.util.spec_from_file_location("reference_needed", SCRIPT)
reference = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reference)

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

BASE = {
    "mods/buildcraft/about.json": "{}",
    "mods/buildcraft/LICENSE": "license\n",
    "mods/buildcraft/tests/pipe-Test.grug": "tick() {}\n",
    "mods/buildcraft/screenshots/pipe/1.2.5-forge.png": "png",
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "loaders/1.2.5-forge/build.gradle": "// 1.2.5\n",
    "loaders/1.2.5-forge/src/Main.java": "class Main {}\n",
    "loaders/1.20.6-forge/build.gradle": "// 1.20.6\n",
    "loaders/1.20.6-forge/src/Main.java": "class Main {}\n",
    "loaders/b1.7.3-ornithe/build.gradle": "// b1.7.3\n",
    "core/src/Main.java": "class Main {}\n",
    "shared/Shared.java": "class Shared {}\n",
    "mod_api.json": "{}",
    "recreation.py": "# recreation\n",
    ".github/workflows/build.yml": "name: Build\n",
    ".github/scripts/run-loader.sh": "# run\n",
    "README.md": "readme\n",
}


class ReferenceNeededTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes: dict, base=None, repo=None) -> str:
        """The script's stdout for a commit with these changes: ``true`` or ``false``."""
        head = self.repo.commit(changes) if changes is not None else "HEAD"
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                base or self.base,
                head,
                "--repo",
                str(repo or self.repo.path),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return result.stdout.strip()

    # What a skip is allowed to rest on.

    def test_a_change_to_another_mod_skips(self):
        self.assertEqual(
            self.answer({"mods/examplemod/code/foo_block-Block.grug": "# new\n"}), "false"
        )
        self.assertIn("skipping", self.reason)

    def test_a_change_to_every_other_mod_and_loader_at_once_skips(self):
        changes = {
            "mods/examplemod/code/foo_block-Block.grug": "# new\n",
            "mods/coverage/code/coverage_frame-Block.grug": "# new\n",
            "mods/newmod/code/bar-Block.grug": "# new\n",
            "loaders/1.20.6-forge/src/Main.java": "// new\n",
            "loaders/b1.7.3-ornithe/build.gradle": "// new\n",
        }
        self.assertEqual(self.answer(changes), "false")

    def test_a_change_to_another_loader_skips(self):
        self.assertEqual(self.answer({"loaders/1.20.6-forge/src/Main.java": "// new\n"}), "false")

    def test_a_new_loader_skips(self):
        self.assertEqual(self.answer({"loaders/9.9-newloader/build.gradle": "// new\n"}), "false")

    # Everything the job observes runs it.

    def test_each_part_of_the_reference_job_runs_it(self):
        for path in (
            "mods/buildcraft/about.json",
            "mods/buildcraft/LICENSE",
            "mods/buildcraft/tests/pipe-Test.grug",
            "mods/buildcraft/code/new-Block.grug",
            "mods/buildcraft/screenshots/pipe/1.2.5-forge.png",
            "loaders/1.2.5-forge/build.gradle",
            "loaders/1.2.5-forge/src/Main.java",
            "loaders/1.2.5-forge/src/new/File.java",
            "core/src/Main.java",
            "shared/Shared.java",
            "mod_api.json",
            "recreation.py",
            ".github/workflows/build.yml",
            ".github/scripts/run-loader.sh",
            ".github/scripts/new-script.py",
            "README.md",
            "settings.gradle.kts",
            "gradle/grug-core.gradle",
            "test-saves/world.zip",
            "mods/stray_file.txt",
            "loaders/stray_file.txt",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.answer({path: "new\n"}), "true")

    def test_one_path_in_the_reference_among_others_runs_it(self):
        changes = {
            "mods/examplemod/code/foo_block-Block.grug": "# new\n",
            "loaders/1.20.6-forge/src/Main.java": "// new\n",
            "mods/buildcraft/tests/pipe-Test.grug": "# new\n",
        }
        self.assertEqual(self.answer(changes), "true")

    def test_a_file_moved_out_of_buildcraft_counts_at_its_old_path(self):
        self.repo.git(
            "mv", "mods/buildcraft/tests/pipe-Test.grug", "mods/examplemod/pipe-Test.grug"
        )
        self.repo.git("commit", "-q", "-m", "move")
        self.assertEqual(self.answer(None), "true")

    def test_a_file_moved_out_of_the_1_2_5_loader_counts_at_its_old_path(self):
        self.repo.git(
            "mv", "loaders/1.2.5-forge/src/Main.java", "loaders/1.20.6-forge/src/Moved.java"
        )
        self.repo.git("commit", "-q", "-m", "move")
        self.assertEqual(self.answer(None), "true")

    def test_a_deleted_buildcraft_runs_it(self):
        self.assertEqual(self.answer({"mods/buildcraft/about.json": None}), "true")

    def test_a_path_with_spaces_and_odd_characters_stays_in_its_directory(self):
        self.assertEqual(self.answer({"mods/examplemod/code/odd name\"'.grug": "x\n"}), "false")
        self.assertEqual(self.answer({"mods/buildcraft/code/odd name\"'.grug": "x\n"}), "true")

    # Every doubt runs the job.

    def test_an_empty_diff_runs_it(self):
        self.assertEqual(self.answer({}), "true")

    def test_the_same_commit_twice_runs_it(self):
        self.assertEqual(self.answer(None, base="HEAD"), "true")

    def test_an_unreachable_base_runs_it(self):
        self.assertEqual(self.answer({"mods/examplemod/a.grug": "x\n"}, base="0" * 40), "true")
        self.assertIn("running", self.reason)

    def test_a_directory_that_is_no_repository_runs_it(self):
        with tempfile.TemporaryDirectory() as other:
            self.assertEqual(self.answer({"mods/examplemod/a.grug": "x\n"}, repo=other), "true")

    def test_an_error_in_the_script_runs_it(self):
        original = reference.reason_to_run
        reference.reason_to_run = lambda path: 1 / 0
        try:
            self.repo.commit({"mods/examplemod/a.grug": "x\n"})
            with (
                contextlib.redirect_stdout(io.StringIO()) as out,
                contextlib.redirect_stderr(io.StringIO()),
            ):
                status = reference.main([self.base, "HEAD", "--repo", str(self.repo.path)])
        finally:
            reference.reason_to_run = original
        self.assertEqual(status, 0)
        self.assertEqual(out.getvalue().strip(), "true")

    def test_the_pull_request_is_diffed_against_its_base_not_the_branch_history(self):
        # A base that moved on since the branch was cut only adds paths to the diff, which runs more.
        self.repo.commit({"mods/examplemod/a.grug": "x\n"})
        self.repo.commit({"core/src/Main.java": "class Main { int x; }\n"})
        head = self.repo.commit({"mods/examplemod/b.grug": "x\n"})
        result = subprocess.run(
            [sys.executable, str(SCRIPT), self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "true")


if __name__ == "__main__":
    unittest.main()
