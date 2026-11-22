#!/usr/bin/env python3
"""Tests dedicated-needed.py: only a diff confined to mod ports skips the dedicated legs (#1342)."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "dedicated-needed.py"

REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

BASE = {
    "AGENTS.md": "agents\n",
    "mod_api.json": "{}",
    "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "class H {}\n",
    "loaders/1.20.6-forge/src/main/java/net/grug/minecraft/forge/ForgeAdapter.java": "class B {}\n",
    "test-saves/a.zip": "zip",
    ".github/scripts/run-loader.sh": "# run\n",
    ".github/workflows/build.yml": "name: Build\n",
    "mods/coverage/tests/x-Test.grug": "tick() {}\n",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "mods/buildcraft/tests/wrench-Test.grug": "tick() {}\n",
    "mods/buildcraft/screenshots/a/forge.png": "png",
    "mods/mekanism/about.json": "{}",
    "shared/Shared.java": "class Shared {}\n",
}
DISPATCH = ("--event", "workflow_dispatch", "--ref", "refs/heads/g1342")


class DedicatedNeededTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes: dict, base=None, *extra: str) -> str:
        head = self.repo.commit(changes)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                self.base if base is None else base,
                head,
                "--repo",
                str(self.repo.path),
                *extra,
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return result.stdout.strip()

    def test_a_dispatch_on_main_keeps_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        out = self.answer(changes, None, "--event", "workflow_dispatch", "--ref", "refs/heads/main")
        self.assertEqual(out, "true")
        self.assertIn("on main", self.reason)

    def test_a_dispatch_naming_no_base_keeps_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        self.assertEqual(self.answer(changes, "", *DISPATCH), "true")
        self.assertIn("names no base", self.reason)

    def test_a_mod_port_with_goldens_and_prose_skips(self):
        changes = {
            "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
            "mods/buildcraft/tests/wrench-Test.grug": "tick() { }\n",
            "mods/buildcraft/screenshots/a/forge.png": "png2",
            "mods/mekanism/about.json": '{"a":1}',
            "AGENTS.md": "agents2\n",
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "false")
        self.assertIn("all 5 changed paths", self.reason)

    def test_every_path_that_is_not_client_only_runs_the_legs(self):
        cases = {
            "core": "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java",
            "api": "mod_api.json",
            "loader": "loaders/1.20.6-forge/src/main/java/net/grug/minecraft/forge/ForgeAdapter.java",
            "save": "test-saves/a.zip",
            "script": ".github/scripts/run-loader.sh",
            "workflow": ".github/workflows/build.yml",
            "coverage mod": "mods/coverage/tests/x-Test.grug",
            "unknown directory": "shared/Shared.java",
            "new file": "tools/new.py",
        }
        for name, path in cases.items():
            with self.subTest(name):
                self.setUp()
                changes = {
                    "mods/buildcraft/code/wrench-Item.grug": f"on_use() {{ }} // {name}\n",
                    path: f"changed {name}\n",
                }
                self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
                self.assertIn(path, self.reason)

    def test_a_nested_markdown_file_is_not_prose_alone(self):
        changes = {"core/NOTES.md": "notes\n"}
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")

    def test_an_empty_diff_keeps_the_legs(self):
        head = self.repo.commit({})
        result = subprocess.run(
            [sys.executable, str(SCRIPT), head, head, "--repo", str(self.repo.path), *DISPATCH],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.stdout.strip(), "true")
        self.assertIn("empty", result.stderr)

    def test_an_unreachable_base_keeps_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        self.assertEqual(self.answer(changes, "0" * 40, *DISPATCH), "true")

    def test_a_schedule_or_unnamed_event_keeps_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        self.assertEqual(self.answer(changes, None, "--event", "schedule"), "true")
        self.assertEqual(self.answer(changes, None), "true")

    def test_a_pull_request_or_push_of_mod_ports_skips_the_legs(self):
        changes = {
            "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
            "AGENTS.md": "agents2\n",
        }
        for event in ("pull_request", "push"):
            with self.subTest(event):
                self.setUp()
                self.assertEqual(self.answer(changes, None, "--event", event), "false")
                self.assertIn("all 2 changed paths", self.reason)

    def test_a_pull_request_or_push_that_reaches_server_code_runs_the_legs(self):
        for event in ("pull_request", "push"):
            for path in (
                "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java",
                "mods/coverage/tests/x-Test.grug",
            ):
                with self.subTest(event=event, path=path):
                    self.setUp()
                    changes = {
                        "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
                        path: "changed\n",
                    }
                    self.assertEqual(self.answer(changes, None, "--event", event), "true")
                    self.assertIn(path, self.reason)

    def test_a_pull_request_or_push_with_no_base_runs_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        for event in ("pull_request", "push"):
            with self.subTest(event):
                self.assertEqual(self.answer(changes, "", "--event", event), "true")
                self.assertIn("names no base", self.reason)

    def test_an_unreachable_pull_request_base_runs_the_legs(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        self.assertEqual(self.answer(changes, "0" * 40, "--event", "pull_request"), "true")

    def loaders(self, *extra: str) -> list:
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "b", "h", "--loaders", *extra],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        return json.loads(result.stdout)

    def test_a_dispatch_runs_every_loader_with_a_server(self):
        self.assertEqual(
            self.loaders("--event", "workflow_dispatch"),
            [
                "1.2.5-forge",
                "1.20.6-forge",
                "a1.1.2_01-ornithe",
                "b1.7.3-ornithe",
                "b1.7.3-stationapi",
            ],
        )
        self.assertEqual(self.loaders(), self.loaders("--event", "workflow_dispatch"))

    def test_a_pull_request_or_push_runs_only_the_loaders_whose_classes_count(self):
        for event in ("pull_request", "push"):
            self.assertEqual(self.loaders("--event", event), ["1.20.6-forge", "b1.7.3-stationapi"])

    def test_every_loader_is_a_loader_directory_with_a_server(self):
        root = HERE.parent.parent / "loaders"
        for name in self.loaders():
            self.assertTrue((root / name).is_dir(), name)


if __name__ == "__main__":
    unittest.main()
