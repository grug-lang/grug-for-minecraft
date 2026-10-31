#!/usr/bin/env python3
"""Tests no-tests.py: only a diff of prose alone lets the test jobs skip."""

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "no-tests.py"
SPEC = importlib.util.spec_from_file_location("no_tests", SCRIPT)
no_tests = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(no_tests)

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

BASE = {
    "AGENTS.md": "agents\n",
    "README.md": "readme\n",
    "mod_api.md": "api\n",
    "LICENSE": "license\n",
    ".github/ISSUE_TEMPLATE/bug.yml": "name: Bug\n",
    ".github/workflows/build.yml": "name: Build\n",
    ".github/scripts/run-loader.sh": "# run\n",
    ".github/scripts/reference-needed.py": "# reference\n",
    ".github/scripts/watch-run.sh": "# watch\n",
    ".github/scripts/no-tests.py": "# no tests\n",
    ".github/scripts/no_tests_test.py": "# test\n",
    "core/src/Main.java": "class Main {}\n",
    "shared/Shared.java": "class Shared {}\n",
    "loaders/1.2.5-forge/build.gradle": "// 1.2.5\n",
    "mod_api.json": "{}",
    "settings.gradle.kts": "// settings\n",
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/coverage/tests/frame-Test.grug": "tick() {}\n",
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "mods/examplemod/screenshots/table/1.2.5-forge.png": "png",
    "mods/examplemod/README.md": "mod readme\n",
    "mods/README.md": "mods readme\n",
    "mods/stray_file.txt": "stray\n",
}


class NoTestsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes: dict, base=None) -> str:
        """The script's stdout for a commit with these changes: ``true`` or ``false``."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), base or self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
            check=True,
        )
        self.reason = result.stderr
        return result.stdout.strip()

    def reset(self):
        """The next answer() has to be taken from the base again, since the history keeps growing."""
        self.repo.git("reset", "-q", "--hard", self.base)

    # The paths that skip.

    def test_prose_alone_skips(self):
        for path, content in (
            ("AGENTS.md", "agents changed\n"),
            ("README.md", "readme changed\n"),
            ("mod_api.md", "api changed\n"),
            ("docs/new.md", "new doc\n"),
            (".github/PULL_REQUEST_TEMPLATE.md", "template\n"),
            (".github/ISSUE_TEMPLATE/bug.yml", "name: Bug report\n"),
            (".github/ISSUE_TEMPLATE/new.md", "new template\n"),
            ("README", "readme without a suffix\n"),
        ):
            with self.subTest(path=path):
                self.reset()
                self.assertEqual(self.answer({path: content}), "true")
                self.assertIn("skipping", self.reason)

    def test_every_prose_path_at_once_skips(self):
        self.assertEqual(
            self.answer(
                {
                    "AGENTS.md": "agents changed\n",
                    "README.md": "readme changed\n",
                    ".github/ISSUE_TEMPLATE/bug.yml": "name: Bug report\n",
                }
            ),
            "true",
        )

    def test_deleting_prose_skips(self):
        self.reset()
        self.assertEqual(self.answer({"AGENTS.md": None}), "true")

    # Everything a test can read runs the tests.

    def test_a_prose_file_inside_a_mod_runs_the_tests(self):
        for path in ("mods/examplemod/README.md", "mods/README.md"):
            with self.subTest(path=path):
                self.reset()
                self.assertEqual(self.answer({path: "changed\n"}), "false")
                self.assertIn("inside a mod", self.reason)

    def test_anything_but_prose_runs_the_tests(self):
        for path, content in (
            ("mods/examplemod/about.json", "{}\n"),
            ("mods/examplemod/code/foo_block-Block.grug", "# changed\n"),
            ("mods/examplemod/screenshots/table/1.2.5-forge.png", "png"),
            ("mods/stray_file.txt", "stray\n"),
            ("mod_api.json", "// changed\n"),
            ("recreation.py", "# changed\n"),
            ("dead_links.py", "# changed\n"),
            ("core/src/Main.java", "// changed\n"),
            ("shared/Shared.java", "// changed\n"),
            ("loaders/1.2.5-forge/build.gradle", "// changed\n"),
            ("settings.gradle.kts", "// changed\n"),
            (".github/workflows/build.yml", "// changed\n"),
            (".github/scripts/run-loader.sh", "// changed\n"),
            # The scripts #755's research called development-only, which a core test reads.
            (".github/scripts/reference-needed.py", "// changed\n"),
            (".github/scripts/watch-run.sh", "// changed\n"),
            (".github/scripts/no-tests.py", "// changed\n"),
            (".github/scripts/no_tests_test.py", "// changed\n"),
            (".gitignore", "/changed\n"),
            ("LICENSE", "changed\n"),
        ):
            with self.subTest(path=path):
                self.reset()
                self.assertEqual(self.answer({path: content}), "false")
                self.assertIn("running", self.reason)

    def test_one_other_path_among_prose_runs_the_tests(self):
        self.reset()
        self.assertEqual(
            self.answer({"AGENTS.md": "agents changed\n", "mod_api.json": "{}\n"}),
            "false",
        )
        self.assertIn("mod_api.json", self.reason)

    # Every doubt runs the tests.

    def test_an_empty_diff_runs_the_tests(self):
        self.assertEqual(self.answer({}), "false")
        self.assertIn("empty", self.reason)

    def test_an_unreadable_base_runs_the_tests(self):
        self.assertEqual(self.answer({"AGENTS.md": "changed\n"}, base="0" * 40), "false")
        self.assertIn("running", self.reason)

    def test_every_doubt_in_the_script_runs_the_tests(self):
        """The entry point answers false when the script itself throws."""
        self.reset()
        original = no_tests.load_changed_paths
        no_tests.load_changed_paths = lambda: (_ for _ in ()).throw(ValueError("boom"))
        self.addCleanup(setattr, no_tests, "load_changed_paths", original)
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            self.assertEqual(no_tests.main([self.base, self.base]), 0)
        self.assertEqual(out.getvalue().strip(), "false")


if __name__ == "__main__":
    unittest.main()
