#!/usr/bin/env python3
"""Tests select-coverage-tests.py: a coverage test is carried over only when the diff
provably cannot change what it does or what it records, and every doubt runs more, never
less."""

import contextlib
import importlib.util
import io
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location(
    "select_coverage_tests", HERE / "select-coverage-tests.py"
)
sel = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(sel)

# affected_mods_test.py has the throwaway repository the integration cases commit into.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

# Three coverage tests; a buildcraft mod whose non-block files no coverage test reads; a
# block whose change moves the ids; and core, with a main file and a unit test.
BASE = {
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/coverage/tests/frame-Test.grug": 'tick() { block("grug:coverage_frame") }\n',
    "mods/coverage/tests/alpha-Test.grug": "tick() { HostFunctions.get_block() }\n",
    "mods/coverage/tests/bravo-Test.grug": "tick() {}\n",
    "mods/buildcraft/about.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "mods/buildcraft/data/grug/recipes/crafting/wrench.json": "{}",
    "mods/buildcraft/tests/wrench-Test.grug": 'tick() { item("grug:wrench") }\n',
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "core/src/main/java/net/grug/Foo.java": "class Foo {}\n",
    "core/src/test/java/net/grug/FooTest.java": "class FooTest {}\n",
    "mod_api.json": "{}",
    "README.md": "readme\n",
}

ALL = frozenset(
    {
        "coverage/tests/frame-Test.grug",
        "coverage/tests/alpha-Test.grug",
        "coverage/tests/bravo-Test.grug",
    }
)


def select(changed, *, base=ALL):
    return sel.select_coverage_tests(
        changed=frozenset(changed),
        coverage_tests=ALL,
        base_coverage_tests=base,
    )


class SelectCoverageTestsPureTest(unittest.TestCase):
    # The fallbacks: an empty diff and any error run every test.

    def test_an_empty_diff_runs_every_test(self):
        run = select([])
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("empty" in reason for reason in run.values()))

    def test_no_coverage_tests_is_no_selection(self):
        self.assertEqual(
            sel.select_coverage_tests(
                changed=frozenset({"README.md"}),
                coverage_tests=frozenset(),
                base_coverage_tests=frozenset(),
            ),
            {},
        )

    # A change confined to files no coverage test reads lets every one be carried over.

    def test_a_change_confined_to_another_mod_is_carried_over(self):
        self.assertEqual(
            select({"mods/buildcraft/data/grug/recipes/crafting/wrench.json"}),
            {},
        )
        self.assertEqual(select({"mods/buildcraft/tests/wrench-Test.grug"}), {})
        # A prose path is an unknown path, which runs every test: a prose-only diff never
        # reaches this selector anyway, since the no-test skip of #785 fires first and skips
        # the test jobs whole.
        self.assertEqual(set(select({"README.md"})), set(ALL))

    # The coarse triggers: everything the whole gate observes that cannot be scoped to a test.

    def test_a_file_outside_mods_runs_every_test(self):
        for path in (
            "mod_api.json",
            "shared/Shared.java",
            "loaders/1.2.5-forge/build.gradle",
            ".github/workflows/build.yml",
            "core/generate.py",
            "recreation.py",
        ):
            with self.subTest(path=path):
                run = select({path})
                self.assertEqual(set(run), set(ALL), path)
                self.assertTrue(all("outside mods/" in reason for reason in run.values()), path)

    def test_a_core_main_file_runs_every_test(self):
        # A host entry's implementation lives in core, and a core file that serves none is a
        # runner, an adapter or glue: either way the gate cannot attribute the change to a
        # test, so every coverage test runs.
        run = select({"core/src/main/java/net/grug/Foo.java"})
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("outside mods/" in reason for reason in run.values()))

    def test_a_file_of_the_coverage_mod_runs_every_test(self):
        for path in (
            "mods/coverage/code/coverage_frame-Block.grug",
            "mods/coverage/assets/grug/lang/en_us.json",
            "mods/coverage/about.json",
        ):
            with self.subTest(path=path):
                run = select({path})
                self.assertEqual(set(run), set(ALL), path)
                self.assertTrue(all("coverage mod" in reason for reason in run.values()), path)

    def test_a_block_or_item_file_anywhere_runs_every_test(self):
        for path in (
            "mods/examplemod/code/foo_block-Block.grug",
            "mods/buildcraft/code/wrench-Item.grug",
        ):
            with self.subTest(path=path):
                run = select({path})
                self.assertEqual(set(run), set(ALL), path)
                self.assertTrue(all("ids" in reason for reason in run.values()), path)

    def test_a_core_test_file_is_no_run_trigger(self):
        # A core unit test runs in the core job, not in a game, so it cannot change what a
        # coverage test does or records.
        self.assertEqual(select({"core/src/test/java/net/grug/FooTest.java"}), {})

    # The fine rules: a test's own file, and a new test.

    def test_a_changed_coverage_test_runs_only_that_test(self):
        self.assertEqual(
            select({"mods/coverage/tests/alpha-Test.grug"}),
            {"coverage/tests/alpha-Test.grug": "its own test file changed"},
        )

    def test_a_new_coverage_test_runs_that_test(self):
        run = sel.select_coverage_tests(
            changed=frozenset({"mods/coverage/tests/delta-Test.grug"}),
            coverage_tests=frozenset(ALL) | frozenset({"coverage/tests/delta-Test.grug"}),
            base_coverage_tests=ALL,
        )
        self.assertEqual(set(run), {"coverage/tests/delta-Test.grug"})
        self.assertIn("new", run["coverage/tests/delta-Test.grug"])

    # The rules compose: the first trigger that fires is a valid reason to run.

    def test_two_triggers_both_run_every_test(self):
        run = select({"mods/buildcraft/data/grug/recipes/crafting/wrench.json", "mod_api.json"})
        self.assertEqual(set(run), set(ALL))

    def test_a_changed_coverage_test_and_an_outside_path(self):
        run = select({"mods/coverage/tests/alpha-Test.grug", "README.md"})
        # README.md is outside mods/, so every test runs; the reason is the coarse one.
        self.assertEqual(set(run), set(ALL))


class SelectCoverageTestsScriptTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def select(self, changes, base=None):
        head = self.repo.commit(changes)
        args = [
            sys.executable,
            str(HERE / "select-coverage-tests.py"),
            base or self.base,
            head,
            "--repo",
            str(self.repo.path),
        ]
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        lines = result.stdout.splitlines()
        self.assertLessEqual(len(lines), 1)
        return lines[0] if lines else ""

    def test_a_change_confined_to_another_mod_carries_every_test_over(self):
        self.assertEqual(
            self.select({"mods/buildcraft/data/grug/recipes/crafting/wrench.json": "{}\n"}),
            ",".join(sorted(ALL)),
        )
        self.assertEqual(self.reason.count("carrying over"), 3)
        self.assertNotIn("running ", self.reason)

    def test_a_file_of_the_coverage_mod_carries_no_test_over(self):
        self.assertEqual(
            self.select({"mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n# x\n"}),
            "",
        )
        self.assertEqual(self.reason.count("running coverage/tests/"), 3)

    def test_a_changed_coverage_test_carries_only_the_other_tests_over(self):
        self.assertEqual(
            self.select({"mods/coverage/tests/alpha-Test.grug": "tick() {}\n"}),
            "coverage/tests/bravo-Test.grug,coverage/tests/frame-Test.grug",
        )

    def test_an_unreadable_base_carries_no_test_over(self):
        # An unreadable base is an error, and the fallback is to run every coverage test,
        # which is the empty carry-over line, never to carry them all over.
        self.assertEqual(self.select({"README.md": "changed\n"}, base="0" * 40), "")
        self.assertIn("carrying over no coverage test", self.reason)

    def test_the_entry_point_carries_no_test_over_when_the_script_itself_fails(self):
        original = sel.aff.changed_paths
        sel.aff.changed_paths = lambda *a, **k: (_ for _ in ()).throw(ValueError("boom"))
        self.addCleanup(setattr, sel.aff, "changed_paths", original)
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(sel.main([self.base, self.base, "--repo", str(self.repo.path)]), 0)
        self.assertEqual(out.getvalue().strip(), "")
        self.assertIn("carrying over no coverage test", err.getvalue())


if __name__ == "__main__":
    unittest.main()
