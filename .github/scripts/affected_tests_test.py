#!/usr/bin/env python3
"""Tests affected-tests.py: only a change a test can observe lets that test skip."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent.parent
SPEC = importlib.util.spec_from_file_location("affected_tests", SCRIPTS / "affected-tests.py")
affected = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(affected)

API = {
    "entities": {
        "Test": {"description": "A test.", "export_functions": [], "static_methods": {"stall": {}}}
    },
    "classes": {"Level": {"description": "A level.", "methods": {"place_block": {}}}},
    "host_functions": {"item": {}},
}


def api_text(**changes):
    api = json.loads(json.dumps(API))
    for name, value in changes.items():
        api[name] = value
    return json.dumps(api)


# Three mods the way the repository has them: one that names another's entity, one with two tests
# that name the same block and one that names nothing, and the always-run one.
BASE = {
    "mods/coverage/about.json": "{}",
    "mods/coverage/code/coverage_frame-Block.grug": 'export init(ctx: BlockInitContext) { ctx.e("coverage_frame_entity") }\n',
    "mods/coverage/code/coverage_frame_entity-BlockEntity.grug": "export tick() {}\n",
    "mods/coverage/tests/frame-Test.grug": 'tick() { block("grug:coverage_frame") }\n',
    "mods/coverage/tests/other-Test.grug": "tick() {}\n",
    "mods/buildcraft/about.json": "{}",
    "mods/buildcraft/code/wooden_pipe-Block.grug": 'x("grug:wooden_pipe_entity")\n',
    "mods/buildcraft/code/wooden_pipe_entity-BlockEntity.grug": "export tick() {}\n",
    "mods/buildcraft/code/wrench-Item.grug": "export init() {}\n",
    "mods/buildcraft/tests/wooden_pipe-Test.grug": 'tick() { block("grug:wooden_pipe") }\n',
    "mods/buildcraft/tests/wooden_pipe_shapes-Test.grug": 'tick() { block("grug:wooden_pipe") }\n',
    "mods/buildcraft/tests/wrench-Test.grug": 'tick() { item("grug:wrench") }\n',
    "mods/buildcraft/tests/placing-Test.grug": 'tick() { Test.get_client_level().place_block(0, 0, 0, "x") }\n',
    "mods/buildcraft/data/grug/recipes/wrench.json": '{"result": "grug:wrench"}',
    "mods/buildcraft/assets/grug/textures/block/wooden_pipe_plain.png": "png",
    "mods/solo/tests/solo-Test.grug": "tick() {}\n",
    "mods/examplemod/about.json": "{}",
    "mods/examplemod/code/foo_block-Block.grug": "export init() {}\n",
    "mod_api.json": api_text(),
    "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": (
        "public class HostFunctions {\n"
        "    public static void Test_stall(double ms) { sleep(ms); }\n"
        "    public static void Level_place_block() { GrugCore.getAdapter().placeBlock(); }\n"
        "    static void sleep(double ms) {}\n"
        "}\n"
    ),
    "loaders/x/src/main/java/net/grug/minecraft/x/XAdapter.java": (
        "public class XAdapter implements ModLoaderAdapter {\n    public void placeBlock() {}\n}\n"
    ),
    "core/src/main/java/net/grug/minecraft/grug/GrugModsWatcher.java": "public class GrugModsWatcher {}\n",
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


class AffectedTestsTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def select(self, changes: dict, base=None):
        """The script's stdout for a commit with these changes: the list, or "" for every test."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPTS / "affected-tests.py"),
                base or self.base,
                head,
                "--repo",
                str(self.repo.path),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return result.stdout.strip()

    def test_a_test_change_runs_that_test_and_coverage(self):
        self.assertEqual(
            self.select(
                {"mods/buildcraft/tests/wrench-Test.grug": 'tick() { item("grug:wrench") }'}
            ),
            "mods/buildcraft/tests/wrench-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_code_change_runs_the_tests_that_name_it(self):
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "export init() { x() }\n"}),
            "mods/buildcraft/tests/wrench-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_block_entity_change_reaches_the_tests_that_name_its_block(self):
        # The pipe's block file names its entity, so a test placing the pipe reaches the entity.
        self.assertEqual(
            self.select(
                {
                    "mods/buildcraft/code/wooden_pipe_entity-BlockEntity.grug": "export tick() { x() }\n"
                }
            ),
            "mods/buildcraft/tests/wooden_pipe-Test.grug,"
            "mods/buildcraft/tests/wooden_pipe_shapes-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_code_name_no_test_knows_runs_the_mods_tests(self):
        self.assertEqual(
            self.select({"mods/buildcraft/code/new_thing-Block.grug": "export init() {}\n"}),
            "mods/buildcraft/tests/placing-Test.grug,"
            "mods/buildcraft/tests/wooden_pipe-Test.grug,"
            "mods/buildcraft/tests/wooden_pipe_shapes-Test.grug,"
            "mods/buildcraft/tests/wrench-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_data_file_runs_the_tests_that_name_what_it_reaches(self):
        self.assertEqual(
            self.select(
                {
                    "mods/buildcraft/data/grug/recipes/wrench.json": '{"result": "grug:wrench", "x": 1}'
                }
            ),
            "mods/buildcraft/tests/wrench-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_texture_no_test_names_runs_its_mods_tests(self):
        self.assertEqual(
            self.select(
                {"mods/buildcraft/assets/grug/textures/block/wooden_pipe_plain.png": "png2"}
            ),
            "mods/buildcraft/tests/placing-Test.grug,"
            "mods/buildcraft/tests/wooden_pipe-Test.grug,"
            "mods/buildcraft/tests/wooden_pipe_shapes-Test.grug,"
            "mods/buildcraft/tests/wrench-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_an_added_host_entry_runs_only_coverage(self):
        api = json.loads(api_text())
        api["entities"]["Test"]["static_methods"]["added"] = {}
        self.assertEqual(
            self.select({"mod_api.json": json.dumps(api)}),
            "mods/coverage/tests/frame-Test.grug,mods/coverage/tests/other-Test.grug",
        )
        self.assertIn("only adds entries", self.reason)

    def test_a_changed_host_entry_runs_its_callers(self):
        api = json.loads(api_text())
        api["classes"]["Level"]["methods"]["place_block"] = {"description": "Changed."}
        self.assertEqual(
            self.select({"mod_api.json": json.dumps(api)}),
            "mods/buildcraft/tests/placing-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_java_file_with_no_entry_runs_every_test(self):
        self.assertEqual(
            self.select({"core/src/main/java/net/grug/minecraft/grug/GrugModsWatcher.java": "x\n"}),
            "",
        )
        self.assertIn("implements no host API entry", self.reason)

    def test_an_adapter_change_runs_the_tests_calling_its_entry(self):
        self.assertEqual(
            self.select(
                {
                    "loaders/x/src/main/java/net/grug/minecraft/x/XAdapter.java": (
                        "public class XAdapter implements ModLoaderAdapter {\n"
                        "    public void placeBlock() { x(); }\n"
                        "}\n"
                    )
                }
            ),
            "mods/buildcraft/tests/placing-Test.grug,"
            "mods/coverage/tests/frame-Test.grug,"
            "mods/coverage/tests/other-Test.grug",
        )

    def test_a_change_outside_mods_runs_every_test(self):
        for path, content in (
            ("README.md", "changed\n"),
            (".github/workflows/build.yml", "x\n"),
            ("core/src/main/java/net/grug/minecraft/grug/GrugTestRunner.java", "x\n"),
        ):
            with self.subTest(path=path):
                self.assertEqual(self.select({path: content}), "")

    def test_a_shared_namespace_runs_every_test(self):
        self.assertEqual(
            self.select({"mods/buildcraft/data/minecraft/tags/blocks/x.json": "{}"}), ""
        )

    def test_an_empty_diff_runs_every_test(self):
        self.assertEqual(self.select({}), "")
        self.assertIn("diff is empty", self.reason)

    def test_an_error_in_the_script_runs_every_test(self):
        original = affected.build_table
        affected.build_table = lambda *args: 1 / 0
        try:
            self.repo.commit({"loaders/x/src/main/java/net/grug/minecraft/x/XAdapter.java": "x\n"})
            status = affected.main([self.base, "HEAD", "--repo", str(self.repo.path)])
        finally:
            affected.build_table = original
        self.assertEqual(status, 0)


class TableTest(unittest.TestCase):
    def test_the_checked_in_table_is_what_the_sources_give(self):
        expected = json.loads((SCRIPTS / affected.TABLE_NAME).read_text())
        self.assertEqual(affected.build_table(REPO_ROOT, "HEAD"), expected)


if __name__ == "__main__":
    unittest.main()
