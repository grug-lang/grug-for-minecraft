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
    "mods/buildcraft/tests/capture-Test.grug": "# Test.screenshot()\n",
    "mods/buildcraft/screenshots/pipe/1.2.5-forge.png": "png",
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/LICENSE": "license\n",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "mods/examplemod/tests/capture-Test.grug": "# Test.screenshot()\n",
    "mods/examplemod/screenshots/x/1.20.6-forge.png": "png",
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

    def answer(self, changes: dict, base=None, repo=None, mod=None, loader=None) -> str:
        """The script's stdout for a commit with these changes: ``true`` or ``false``."""
        head = self.repo.commit(changes) if changes is not None else "HEAD"
        argv = [
            sys.executable,
            str(SCRIPT),
            base or self.base,
            head,
            "--repo",
            str(repo or self.repo.path),
        ]
        if mod is not None:
            argv += ["--mod", mod]
        if loader is not None:
            argv += ["--loader", loader]
        result = subprocess.run(argv, capture_output=True, text=True)
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
        for path, content in (
            ("mods/buildcraft/about.json", "{}\n"),
            ("mods/buildcraft/LICENSE", "license\n"),
            ("mods/buildcraft/tests/capture-Test.grug", "# Test.screenshot()\n"),
            ("mods/buildcraft/tests/newcapture-Test.grug", "# Test.is_reference()\n"),
            ("mods/buildcraft/screenshots/pipe/1.2.5-forge.png", "png"),
            ("loaders/1.2.5-forge/build.gradle", "// new\n"),
            ("loaders/1.2.5-forge/src/Main.java", "// new\n"),
            ("loaders/1.2.5-forge/src/new/File.java", "// new\n"),
            ("core/src/Main.java", "// new\n"),
            ("shared/Shared.java", "// new\n"),
            ("mod_api.json", "// new\n"),
            ("recreation.py", "// new\n"),
            (".github/workflows/build.yml", "// new\n"),
            (".github/scripts/run-loader.sh", "// new\n"),
            (".github/scripts/new-script.py", "// new\n"),
            ("README.md", "readme\n"),
            ("settings.gradle.kts", "// new\n"),
            ("gradle/grug-core.gradle", "// new\n"),
            ("test-saves/world.zip", "zip"),
            ("mods/stray_file.txt", "new\n"),
            ("loaders/stray_file.txt", "new\n"),
        ):
            with self.subTest(path=path):
                # Each path is the whole diff: without the reset the history keeps growing and every
                # later diff still holds the first path, which always answers true.
                self.repo.git("reset", "-q", "--hard", self.base)
                self.assertEqual(self.answer({path: content}), "true")

    def test_the_port_s_code_data_assets_and_other_tests_skip(self):
        # The harness holds no code, data or assets: the real BuildCraft provides every block and
        # draws every texture, so changing them cannot change what the reference run draws.
        changes = {
            "mods/buildcraft/code/new-Block.grug": "# new\n",
            "mods/buildcraft/data/grug/recipes/crafting/new.json": "{}\n",
            "mods/buildcraft/assets/grug/textures/block/new.png": "png",
            "mods/buildcraft/tests/pipe-Test.grug": "# new\n",
        }
        self.assertEqual(self.answer(changes), "false")
        self.assertIn("skipping", self.reason)

    def test_a_test_that_stops_capturing_still_runs_it(self):
        # It was in the harness at the base commit, so its removal from it is a change to the run.
        self.assertEqual(
            self.answer({"mods/buildcraft/tests/capture-Test.grug": "tick() {}\n"}), "true"
        )

    def test_one_path_in_the_reference_among_others_runs_it(self):
        changes = {
            "mods/examplemod/code/foo_block-Block.grug": "# new\n",
            "loaders/1.20.6-forge/src/Main.java": "// new\n",
            "mods/buildcraft/tests/capture-Test.grug": "# Test.screenshot()\n// new\n",
        }
        self.assertEqual(self.answer(changes), "true")

    def test_a_file_moved_out_of_buildcraft_counts_at_its_old_path(self):
        self.repo.git(
            "mv", "mods/buildcraft/tests/capture-Test.grug", "mods/examplemod/capture-Test.grug"
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
        self.assertEqual(self.answer({"mods/buildcraft/screenshots/odd name\"'.png": "x"}), "true")

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
        reference.reason_to_run = lambda *args: 1 / 0
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

    # The same rules hold for a mod that is not buildcraft, on a loader that is not 1.2.5-forge.

    def test_a_change_confined_to_other_mods_and_loaders_skips_when_the_mod_is_not_buildcraft(self):
        # buildcraft is another mod and 1.2.5-forge is another loader, once the reference job runs
        # examplemod on 1.20.6-forge.
        changes = {
            "mods/buildcraft/about.json": "{}\n",
            "mods/buildcraft/tests/capture-Test.grug": "# Test.screenshot()\n// new\n",
            "loaders/1.2.5-forge/src/Main.java": "// new\n",
            "mods/coverage/code/coverage_frame-Block.grug": "# new\n",
        }
        self.assertEqual(self.answer(changes, mod="examplemod", loader="1.20.6-forge"), "false")
        self.assertIn("skipping", self.reason)

    def test_a_change_to_the_selected_mods_harness_runs_it(self):
        self.assertEqual(
            self.answer(
                {"mods/examplemod/about.json": "{}\n"}, mod="examplemod", loader="1.20.6-forge"
            ),
            "true",
        )

    def test_a_change_to_the_selected_mods_screenshot_test_runs_it(self):
        self.assertEqual(
            self.answer(
                {"mods/examplemod/tests/capture-Test.grug": "# Test.is_reference()\n"},
                mod="examplemod",
                loader="1.20.6-forge",
            ),
            "true",
        )

    def test_a_change_to_the_selected_mods_code_still_skips(self):
        # The harness holds no code, so a change confined to the mod's code is outside it.
        self.assertEqual(
            self.answer(
                {"mods/examplemod/code/foo_block-Block.grug": "# new\n"},
                mod="examplemod",
                loader="1.20.6-forge",
            ),
            "false",
        )

    def test_a_change_to_the_selected_loader_runs_it(self):
        self.assertEqual(
            self.answer(
                {"loaders/1.20.6-forge/src/Main.java": "// new\n"},
                mod="examplemod",
                loader="1.20.6-forge",
            ),
            "true",
        )

    def test_a_change_to_the_other_loader_skips_when_the_mod_is_not_buildcraft(self):
        self.assertEqual(
            self.answer(
                {"loaders/1.2.5-forge/src/Main.java": "// new\n"},
                mod="examplemod",
                loader="1.20.6-forge",
            ),
            "false",
        )

    def test_a_file_moved_out_of_the_selected_mod_counts_at_its_old_path(self):
        self.repo.git(
            "mv", "mods/examplemod/tests/capture-Test.grug", "mods/coverage/capture-Test.grug"
        )
        self.repo.git("commit", "-q", "-m", "move")
        self.assertEqual(self.answer(None, mod="examplemod", loader="1.20.6-forge"), "true")

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
