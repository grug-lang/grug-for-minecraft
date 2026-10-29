#!/usr/bin/env python3
"""Tests affected-mods.py: only a change confined to other mods lets a mod's tests skip."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "affected-mods.py"
SPEC = importlib.util.spec_from_file_location("affected_mods", SCRIPT)
affected = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(affected)

# Three mods the way the repository has them: one that names another's entity in a test (like
# buildcraft naming coverage's fixtures), one that names nothing, and the always-run one.
BASE = {
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": "on_tick() {}\n",
    "mods/coverage/tests/frame-Test.grug": 'tick() { block("grug:coverage_frame") }\n',
    "mods/coverage/assets/grug/lang/en_us.json": "{}",
    "mods/buildcraft/about.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "mods/buildcraft/tests/wrench-Test.grug": 'tick() { item("grug:wrench") }\n',
    "mods/buildcraft/tests/pipe-Test.grug": 'tick() { block("grug:coverage_frame") }\n',
    "mods/buildcraft/data/grug/recipes/crafting/wrench.json": "{}",
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "on_tick() {}\n",
    "mods/examplemod/assets/grug/textures/block/foo_block.png": "png",
    "mods/solo/about.json": "{}",
    "mods/solo/code/solo-Block.grug": "on_tick() {}\n",
    "core/src/Main.java": "class Main {}\n",
    "mod_api.json": "{}",
    "README.md": "readme\n",
}


class Repo:
    """A throwaway git repository whose commits the tests diff."""

    def __init__(self, tmp: Path):
        self.path = tmp
        self.git("init", "-q", "-b", "main")
        self.git("config", "user.email", "t@example.com")
        self.git("config", "user.name", "t")
        self.git("config", "commit.gpgsign", "false")

    def git(self, *args: str) -> str:
        out = subprocess.run(
            ["git", "-C", str(self.path), *args], capture_output=True, check=True, text=True
        )
        return out.stdout.strip()

    def commit(self, changes: dict, message: str = "c") -> str:
        """Writes the files (a None value deletes one) and commits; returns the commit."""
        for name, content in changes.items():
            path = self.path / name
            if content is None:
                path.unlink()
                continue
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(content)
        self.git("add", "-A")
        self.git("commit", "-q", "--allow-empty", "-m", message)
        return self.git("rev-parse", "HEAD")


class AffectedModsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def select(self, changes: dict, base=None):
        """The script's stdout for a commit with these changes: the list, or "" for every mod."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), base or self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return result.stdout.strip()

    # What a skip is allowed to rest on.

    def test_a_change_in_one_mod_runs_that_mod_and_coverage(self):
        # examplemod and solo are named by nothing and name nothing.
        self.assertEqual(
            self.select({"mods/examplemod/code/foo_block-Block.grug": "# new\n"}),
            "coverage,examplemod",
        )

    def test_a_mod_that_names_no_other_skips_a_change_to_a_third(self):
        self.assertEqual(
            self.select({"mods/solo/code/solo-Block.grug": "on_tick() { x() }\n"}),
            "coverage,solo",
        )

    def test_a_change_to_a_mod_runs_the_mods_that_name_it(self):
        # buildcraft's tests name coverage_frame, so a change to coverage runs buildcraft too.
        self.assertEqual(
            self.select({"mods/coverage/code/coverage_frame-Block.grug": "on_tick() { x() }\n"}),
            "buildcraft,coverage",
        )

    def test_a_change_to_coverage_only_runs_coverage_when_nobody_names_it(self):
        self.assertEqual(
            self.select(
                {
                    "mods/buildcraft/tests/pipe-Test.grug": "tick() {}\n",
                    "mods/coverage/about.json": '{"name": "c"}',
                }
            ),
            "buildcraft,coverage",
        )
        # Buildcraft no longer names coverage after that commit, so a coverage change skips it.
        self.base = self.repo.git("rev-parse", "HEAD")
        self.assertEqual(
            self.select({"mods/coverage/about.json": '{"name": "d"}'}, self.base),
            "coverage",
        )

    def test_a_change_to_buildcraft_does_not_run_examplemod(self):
        self.assertEqual(
            self.select({"mods/buildcraft/tests/wrench-Test.grug": "tick() {}\n"}),
            "buildcraft,coverage",
        )

    def test_the_dependency_is_transitive(self):
        # solo names buildcraft's wrench, and buildcraft names coverage, so coverage reaches solo.
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:wrench")\n'})
        self.assertEqual(
            self.select({"mods/coverage/code/coverage_frame-Block.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_name_in_a_grug_comment_is_no_reference(self):
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": "# see grug:wrench\nx()\n"})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage",
        )

    def test_a_name_in_a_string_after_a_hash_is_a_reference(self):
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("#grug:wrench")\n'})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_name_in_a_string_with_an_escaped_quote_is_a_reference(self):
        text = 'x("a\\" # b", "grug:wrench") # trailing\n'
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": text})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_name_in_a_json_file_is_a_reference(self):
        base = self.repo.commit({"mods/solo/data/grug/x.json": '{"item": "grug:wrench"}'})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_reference_without_the_prefix_still_counts(self):
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("wrench")\n'})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_about_json_and_the_license_are_no_reference(self):
        base = self.repo.commit(
            {"mods/solo/about.json": '{"name": "wrench"}', "mods/solo/LICENSE": "wrench\n"}
        )
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage",
        )

    # Everything that selects every mod, printed as nothing.

    def test_a_file_outside_mods_selects_every_mod(self):
        for path in ("core/src/Main.java", "mod_api.json", ".github/workflows/build.yml", "x.md"):
            with self.subTest(path=path):
                self.assertEqual(self.select({path: "changed\n"}), "")
                self.assertIn("is not inside a mod directory", self.reason)

    def test_a_file_outside_mods_next_to_a_mod_change_selects_every_mod(self):
        self.assertEqual(
            self.select(
                {"mods/solo/code/solo-Block.grug": "# a\n", "core/src/Main.java": "// a\n"}
            ),
            "",
        )

    def test_a_file_directly_in_mods_selects_every_mod(self):
        self.assertEqual(self.select({"mods/README.md": "x\n"}), "")

    def test_a_change_in_a_shared_namespace_selects_every_mod(self):
        for path in (
            "mods/solo/data/minecraft/tags/blocks/mineable/pickaxe.json",
            "mods/solo/data/c/tags/items/gears.json",
            "mods/solo/assets/minecraft/lang/en_us.json",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.select({path: "{}"}), "")
                self.assertIn("shares with vanilla", self.reason.replace("shared", "shares"))

    def test_a_change_in_the_grug_namespace_does_not(self):
        self.assertEqual(
            self.select({"mods/solo/data/grug/tags/items/x.json": "{}"}), "coverage,solo"
        )

    def test_an_empty_diff_selects_every_mod(self):
        self.assertEqual(self.select({}), "")
        self.assertIn("diff is empty", self.reason)

    def test_the_same_commit_twice_selects_every_mod(self):
        head = self.repo.git("rev-parse", "HEAD")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), head, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout, "")

    def test_an_unreachable_base_selects_every_mod(self):
        for base in ("0" * 40, "no-such-ref", "deadbeef"):
            with self.subTest(base=base):
                self.assertEqual(
                    self.select({"mods/solo/code/solo-Block.grug": f"# {base}\n"}, base), ""
                )
                self.assertIn("failed", self.reason)

    def test_a_base_that_is_a_tree_selects_every_mod(self):
        tree = self.repo.git("rev-parse", "HEAD^{tree}")
        self.assertEqual(self.select({"mods/solo/code/solo-Block.grug": "# t\n"}, tree), "")

    def test_a_directory_that_is_no_repository_selects_every_mod(self):
        with tempfile.TemporaryDirectory() as empty:
            result = subprocess.run(
                [sys.executable, str(SCRIPT), "a", "b", "--repo", empty],
                capture_output=True,
                text=True,
            )
        self.assertEqual((result.returncode, result.stdout), (0, ""))

    def test_a_mod_name_the_runner_could_not_take_selects_every_mod(self):
        for name in ("a,b", "has space", ".hidden", "-x", "new\nline", 'quo"te'):
            with self.subTest(name=name):
                path = f"mods/{name}/code/x-Block.grug"
                self.assertEqual(self.select({path: "x\n"}), "")

    def test_a_missing_coverage_mod_selects_every_mod(self):
        base = self.repo.commit({})
        # coverage is gone at the head, so there is nothing to always run.
        files = {k: None for k in BASE if k.startswith("mods/coverage/")}
        files["mods/solo/code/solo-Block.grug"] = "# x\n"
        self.assertEqual(self.select(files, base), "")

    # The real cases.

    def test_a_new_mod_runs_itself_and_coverage(self):
        self.assertEqual(self.select({"mods/newmod/code/n-Block.grug": "x\n"}), "coverage,newmod")

    def test_a_new_mod_that_names_another_does_not_run_that_other(self):
        self.assertEqual(
            self.select({"mods/newmod/code/n-Block.grug": 'x("grug:wrench")\n'}),
            "coverage,newmod",
        )

    def test_a_new_mod_that_takes_a_name_runs_the_mods_that_name_it(self):
        # solo names "newthing" before newmod exists; once it ships newthing, solo observes it.
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:newthing")\n'})
        self.assertEqual(
            self.select({"mods/newmod/code/newthing-Block.grug": "x\n"}, base),
            "coverage,newmod,solo",
        )

    def test_a_deleted_mod_is_not_selected_but_the_mods_that_named_it_are(self):
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:foo_block")\n'})
        self.assertEqual(
            self.select(
                {
                    "mods/examplemod/code/foo_block-Block.grug": None,
                    "mods/examplemod/about.json": None,
                    "mods/examplemod/assets/grug/textures/block/foo_block.png": None,
                },
                base,
            ),
            "coverage,solo",
        )

    def test_a_deleted_file_another_mod_named_keeps_its_edge(self):
        # Only the base ships wrench, so only the base tree can say solo named it.
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:wrench")\n'})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": None}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_removed_reference_keeps_the_edge_from_the_base(self):
        # solo named wrench at the base and no longer does; its own change selects it anyway.
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:wrench")\n'})
        self.assertEqual(
            self.select({"mods/solo/code/solo-Block.grug": "x()\n"}, base), "coverage,solo"
        )

    def test_a_rename_selects_both_the_old_and_the_new_mod(self):
        old = (self.repo.path / "mods/solo/code/solo-Block.grug").read_text()
        self.assertEqual(
            self.select(
                {
                    "mods/solo/code/solo-Block.grug": None,
                    "mods/examplemod/code/solo-Block.grug": old,
                }
            ),
            "coverage,examplemod,solo",
        )

    def test_a_mod_whose_every_file_moved_away_is_no_longer_selected(self):
        old = (self.repo.path / "mods/solo/code/solo-Block.grug").read_text()
        self.assertEqual(
            self.select(
                {
                    "mods/solo/code/solo-Block.grug": None,
                    "mods/solo/about.json": None,
                    "mods/examplemod/code/solo-Block.grug": old,
                }
            ),
            "coverage,examplemod",
        )

    def test_a_rename_is_a_delete_and_an_add_even_when_git_would_call_it_a_rename(self):
        self.repo.git("mv", "mods/solo/code/solo-Block.grug", "mods/examplemod/code/moved.grug")
        self.repo.git("commit", "-q", "-m", "mv")
        self.assertEqual(
            subprocess.run(
                [sys.executable, str(SCRIPT), self.base, "HEAD", "--repo", str(self.repo.path)],
                capture_output=True,
                text=True,
            ).stdout.strip(),
            "coverage,examplemod,solo",
        )

    def test_a_path_with_spaces_and_odd_characters_stays_in_its_mod(self):
        for name in ("a b.grug", 'quo"te.grug', "unié.grug", "tab\there.grug", "-x.grug"):
            with self.subTest(name=name):
                self.assertEqual(self.select({f"mods/solo/code/{name}": "x\n"}), "coverage,solo")

    def test_crlf_files_are_scanned_like_the_rest(self):
        base = self.repo.commit({"mods/solo/code/solo-Block.grug": 'x("grug:wrench")\r\n# c\r\n'})
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "# b\n"}, base),
            "buildcraft,coverage,solo",
        )

    def test_a_mod_directory_with_no_tests_can_be_selected(self):
        self.assertEqual(
            self.select({"mods/examplemod/assets/grug/lang/en_us.json": "{}"}),
            "coverage,examplemod",
        )

    def test_a_change_to_every_mod_prints_nothing_rather_than_the_full_list(self):
        self.assertEqual(
            self.select(
                {
                    "mods/solo/code/solo-Block.grug": "# a\n",
                    "mods/examplemod/code/foo_block-Block.grug": "# a\n",
                    "mods/buildcraft/code/wrench-Item.grug": "# a\n",
                }
            ),
            "",
        )

    def test_a_binary_file_is_not_scanned_but_still_counts_as_a_change(self):
        self.assertEqual(
            self.select({"mods/examplemod/assets/grug/textures/block/foo_block.png": "png2"}),
            "coverage,examplemod",
        )

    def test_a_shallow_clone_with_both_commits_works(self):
        head = self.repo.commit({"mods/solo/code/solo-Block.grug": "# s\n"})
        with tempfile.TemporaryDirectory() as clone_dir:
            clone = Path(clone_dir) / "c"
            url = f"file://{self.repo.path}"
            subprocess.run(["git", "init", "-q", str(clone)], check=True)
            for sha in (self.base, head):
                subprocess.run(
                    ["git", "-C", str(clone), "fetch", "-q", "--depth=1", url, sha], check=True
                )
            result = subprocess.run(
                [sys.executable, str(SCRIPT), self.base, head, "--repo", str(clone)],
                capture_output=True,
                text=True,
            )
        self.assertEqual(result.stdout.strip(), "coverage,solo")

    def test_a_shallow_clone_missing_the_base_selects_every_mod(self):
        head = self.repo.commit({"mods/solo/code/solo-Block.grug": "# s\n"})
        with tempfile.TemporaryDirectory() as clone_dir:
            clone = Path(clone_dir) / "c"
            subprocess.run(["git", "init", "-q", str(clone)], check=True)
            subprocess.run(
                [
                    "git",
                    "-C",
                    str(clone),
                    "fetch",
                    "-q",
                    "--depth=1",
                    f"file://{self.repo.path}",
                    head,
                ],
                check=True,
            )
            result = subprocess.run(
                [sys.executable, str(SCRIPT), self.base, head, "--repo", str(clone)],
                capture_output=True,
                text=True,
            )
        self.assertEqual((result.returncode, result.stdout), (0, ""))

    def test_the_diff_is_between_the_two_commits_so_a_moved_base_only_runs_more(self):
        # A pull request whose base moved is diffed against the old base: main's own changes show
        # up in the diff too, which can only select more mods.
        moved = self.repo.commit({"mods/examplemod/code/foo_block-Block.grug": "# main\n"})
        head = self.repo.commit({"mods/solo/code/solo-Block.grug": "# pr\n"})
        result = subprocess.run(
            [sys.executable, str(SCRIPT), self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "coverage,examplemod,solo")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), moved, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "coverage,solo")

    def test_an_error_in_the_script_selects_every_mod(self):
        # A symlink in mods/ whose blob is not what cat-file expects is not the point; any failure
        # inside the scan must end in "every mod". Break the scan itself.
        original = affected.tree_facts
        affected.tree_facts = lambda *args: 1 / 0
        try:
            self.repo.commit({"mods/solo/code/solo-Block.grug": "# z\n"})
            status = affected.main([self.base, "HEAD", "--repo", str(self.repo.path)])
        finally:
            affected.tree_facts = original
        self.assertEqual(status, 0)


class StripGrugCommentsTest(unittest.TestCase):
    def test_strips_to_the_end_of_the_line(self):
        self.assertEqual(affected.strip_grug_comments("a # b\nc"), "a \nc")

    def test_keeps_a_hash_inside_a_string(self):
        self.assertEqual(affected.strip_grug_comments('x("#c:gears") # y'), 'x("#c:gears") ')

    def test_an_unbalanced_quote_does_not_hide_the_next_line(self):
        self.assertEqual(affected.strip_grug_comments('x("a # b\ny'), 'x("a # b\ny')

    def test_an_escaped_backslash_before_the_closing_quote(self):
        self.assertEqual(affected.strip_grug_comments('"a\\\\" # b'), '"a\\\\" ')


if __name__ == "__main__":
    unittest.main()
