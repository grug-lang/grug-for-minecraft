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

# Four coverage tests; a buildcraft mod whose non-block files no coverage test reads; a
# block whose change moves the ids; and core, with a main file and a unit test.
BASE = {
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/coverage/tests/frame-Test.grug": 'tick() { block("grug:coverage_frame") }\n',
    "mods/coverage/tests/alpha-Test.grug": "tick() { HostFunctions.get_block() }\n",
    "mods/coverage/tests/bravo-Test.grug": "tick() {}\n",
    # A coverage test that reads another mod's item, which is the one the fine rule scopes to.
    "mods/coverage/tests/gear-Test.grug": 'tick() { item("grug:stone_gear") }\n',
    "mods/buildcraft/about.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "mods/buildcraft/code/stone_gear-Item.grug": "on_use() {}\n",
    "mods/buildcraft/code/wrench_entity-BlockEntity.grug": "export tick() {}\n",
    "mods/buildcraft/data/grug/recipes/crafting/wrench.json": "{}",
    "mods/buildcraft/tests/wrench-Test.grug": 'tick() { item("grug:wrench") }\n',
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "mods/examplemod/data/minecraft/tags/blocks/mineable/pickaxe.json": "{}",
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
        "coverage/tests/gear-Test.grug",
    }
)

# What each coverage test reads, as the selector reads it from the test's own source.
SYMBOLS = {
    "coverage/tests/frame-Test.grug": sel.referenced_symbols(
        BASE["mods/coverage/tests/frame-Test.grug"]
    ),
    "coverage/tests/alpha-Test.grug": sel.referenced_symbols(
        BASE["mods/coverage/tests/alpha-Test.grug"]
    ),
    "coverage/tests/bravo-Test.grug": sel.referenced_symbols(
        BASE["mods/coverage/tests/bravo-Test.grug"]
    ),
    "coverage/tests/gear-Test.grug": sel.referenced_symbols(
        BASE["mods/coverage/tests/gear-Test.grug"]
    ),
}


def select(changed, *, base=ALL, statuses=None, symbols=None):
    """The selection for a diff that modified these paths, unless a status says otherwise."""
    changed = frozenset(changed)
    code_status = {path: sel.MODIFIED for path in changed} | (statuses or {})
    return sel.select_coverage_tests(
        changed=changed,
        coverage_tests=ALL,
        base_coverage_tests=base,
        code_status=code_status,
        test_symbols=SYMBOLS if symbols is None else symbols,
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

    def test_a_nested_coverage_test_runs_every_test(self):
        # The map flattens a test path into dots and names it back only as <mod>/tests/<name>,
        # so a test under a subdirectory of tests/ has no key the tool can match back, and the
        # selection cannot offer it for carry-over: every coverage test runs.
        nested = frozenset({"coverage/tests/sub/delta-Test.grug"})
        tests = frozenset(ALL) | nested
        run = sel.select_coverage_tests(
            changed=frozenset({"mods/buildcraft/tests/wrench-Test.grug"}),
            coverage_tests=tests,
            base_coverage_tests=tests,
        )
        self.assertEqual(set(run), tests)
        self.assertTrue(all("nested" in reason for reason in run.values()))

    def test_a_test_path_the_carry_over_value_cannot_hold_runs_every_test(self):
        # The names travel as one comma-separated value and as one line each in a file, so a path
        # holding a separator or a quote cannot be represented: every coverage test runs.
        for name in (
            "coverage/tests/a,b-Test.grug",
            'coverage/tests/a"b-Test.grug',
            "coverage/tests/a'b-Test.grug",
            "coverage/tests/a\nb-Test.grug",
        ):
            with self.subTest(name=name):
                tests = frozenset(ALL) | frozenset({name})
                run = sel.select_coverage_tests(
                    changed=frozenset({"mods/buildcraft/tests/wrench-Test.grug"}),
                    coverage_tests=tests,
                    base_coverage_tests=tests,
                )
                self.assertEqual(set(run), tests)
                self.assertTrue(all("cannot represent" in reason for reason in run.values()), run)

    # A change confined to files no coverage test reads lets every one be carried over.

    def test_a_change_confined_to_another_mod_is_carried_over(self):
        self.assertEqual(
            select({"mods/buildcraft/data/grug/recipes/crafting/wrench.json"}),
            {},
        )
        self.assertEqual(select({"mods/buildcraft/tests/wrench-Test.grug"}), {})
        # A modified block or item of another mod whose id no coverage test reads.
        self.assertEqual(select({"mods/buildcraft/code/wrench-Item.grug"}), {})
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

    def test_an_added_or_removed_block_or_item_file_runs_every_test(self):
        # Ids are handed out in name order, so a block or item that appears or goes moves the ids
        # of the ones after it, which no test can be checked against.
        for path, status in (
            ("mods/examplemod/code/bar-Block.grug", sel.ADDED),
            ("mods/buildcraft/code/wrench-Item.grug", sel.GONE),
        ):
            with self.subTest(path=path, status=status):
                run = select({path}, statuses={path: status})
                self.assertEqual(set(run), set(ALL), path)
                self.assertTrue(all("moves the ids" in reason for reason in run.values()), path)

    def test_a_block_or_item_file_of_unknown_status_runs_every_test(self):
        # A block or item file the status says nothing about is a doubt, and a doubt runs more.
        run = sel.select_coverage_tests(
            changed=frozenset({"mods/buildcraft/code/stone_gear-Item.grug"}),
            coverage_tests=ALL,
            base_coverage_tests=ALL,
            test_symbols=SYMBOLS,
        )
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("cannot attribute" in reason for reason in run.values()), run)

    def test_another_mods_block_entity_file_runs_every_test(self):
        # A block entity is created by the block that names it, not by the test that names the
        # block, so the selector cannot see which tests read the entity's code.
        run = select({"mods/buildcraft/code/wrench_entity-BlockEntity.grug"})
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("cannot attribute" in reason for reason in run.values()), run)

    def test_another_mods_helper_code_file_runs_every_test(self):
        # A helper is called by another file, which the selector reads names from rather than
        # code, so the tests that reach it cannot be listed.
        run = select({"mods/buildcraft/code/pipe_helpers.grug"})
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("cannot attribute" in reason for reason in run.values()), run)

    def test_a_file_of_another_mod_in_the_shared_namespace_runs_every_test(self):
        # data/ and assets/ outside the grug namespace are shared with vanilla and every mod, so
        # any mod's change there is what every coverage test observes.
        path = "mods/examplemod/data/minecraft/tags/blocks/mineable/pickaxe.json"
        run = select({path})
        self.assertEqual(set(run), set(ALL))
        self.assertTrue(all("shares with vanilla" in reason for reason in run.values()), run)
        # A file in the grug namespace only names what ships it, so it is carried over.
        self.assertEqual(select({"mods/buildcraft/data/grug/recipes/crafting/wrench.json"}), {})

    def test_a_core_test_file_is_no_run_trigger(self):
        # A core unit test runs in the core job, not in a game, so it cannot change what a
        # coverage test does or records.
        self.assertEqual(select({"core/src/test/java/net/grug/FooTest.java"}), {})

    # The fine rules: a test's own file, a new test, and another mod's block or item it reads.

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

    def test_a_modified_block_or_item_another_mod_declares_runs_the_tests_reading_it(self):
        # Only the test whose source reads the id can observe a body change to it, and the id
        # itself does not move, so the other coverage tests are carried over.
        run = select({"mods/buildcraft/code/stone_gear-Item.grug"})
        self.assertEqual(set(run), {"coverage/tests/gear-Test.grug"})
        self.assertIn("stone_gear", run["coverage/tests/gear-Test.grug"])

    def test_a_modified_block_of_another_mod_no_test_reads_is_carried_over(self):
        self.assertEqual(select({"mods/buildcraft/code/wrench-Item.grug"}), {})
        self.assertEqual(select({"mods/examplemod/code/foo_block-Block.grug"}), {})

    def test_a_test_the_selector_cannot_read_runs_when_a_block_changes(self):
        # The symbol facts come from the test's source, so a test whose source could not be read
        # has no answer, and a doubt about whether it reads the changed id runs it.
        run = select(
            {"mods/buildcraft/code/stone_gear-Item.grug"},
            symbols={t: s for t, s in SYMBOLS.items() if t != "coverage/tests/gear-Test.grug"},
        )
        self.assertIn("coverage/tests/gear-Test.grug", run)
        self.assertIn("could not be read", run["coverage/tests/gear-Test.grug"])
        # The others were read and do not read the item, so this one doubt runs only itself.
        self.assertEqual(set(run), {"coverage/tests/gear-Test.grug"})

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
        self.assertEqual(self.reason.count("carrying over"), 4)
        self.assertNotIn("running ", self.reason)

    def test_a_modified_block_file_carries_over_the_tests_that_do_not_read_it(self):
        # The diff's status is what tells a modification, which moves no id, from an addition or a
        # removal, which does: only the test reading the item runs.
        self.assertEqual(
            self.select({"mods/buildcraft/code/stone_gear-Item.grug": "on_use() {}\n# changed\n"}),
            "coverage/tests/alpha-Test.grug,coverage/tests/bravo-Test.grug,"
            "coverage/tests/frame-Test.grug",
        )
        self.assertIn("it reads stone_gear", self.reason)

    def test_a_new_block_file_carries_no_test_over(self):
        # A block file that appears renumbers the ids after it, which the selector reports as
        # every test running rather than as the tests that read that one block.
        self.assertEqual(
            self.select({"mods/examplemod/code/bar_block-Block.grug": "on_tick() {}\n"}),
            "",
        )
        self.assertIn("moves the ids", self.reason)

    def test_another_mods_code_that_cannot_be_scoped_carries_no_test_over(self):
        self.assertEqual(
            self.select(
                {"mods/buildcraft/code/wrench_entity-BlockEntity.grug": "export tick() {}\n# x\n"}
            ),
            "",
        )
        self.assertIn("cannot attribute", self.reason)

    def test_a_shared_namespace_file_carries_no_test_over(self):
        self.assertEqual(
            self.select(
                {"mods/examplemod/data/minecraft/tags/blocks/mineable/pickaxe.json": "{}\n"}
            ),
            "",
        )
        self.assertIn("shares with vanilla", self.reason)

    def test_a_file_of_the_coverage_mod_carries_no_test_over(self):
        self.assertEqual(
            self.select({"mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n# x\n"}),
            "",
        )
        self.assertEqual(self.reason.count("running coverage/tests/"), 4)

    def test_a_changed_coverage_test_carries_only_the_other_tests_over(self):
        self.assertEqual(
            self.select({"mods/coverage/tests/alpha-Test.grug": "tick() {}\n"}),
            "coverage/tests/bravo-Test.grug,coverage/tests/frame-Test.grug,"
            "coverage/tests/gear-Test.grug",
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
