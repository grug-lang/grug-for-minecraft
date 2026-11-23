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
    "shared/ornithe/src/main/java/Common.java": "class Common {}\n",
    "loaders/a1.1.2_01-ornithe/build.gradle": "sourceSets { main { java { srcDir '../../shared/ornithe/src/main/java' } } }\n",
    "loaders/b1.7.3-ornithe/build.gradle": "sourceSets { main { java { srcDir '../../shared/ornithe/src/main/java' } } }\n",
    "loaders/1.2.5-forge/src/Server.java": "class S {}\n",
    "loaders/a1.1.2_01-ornithe/src/ServerGrugModLoader.java": "class S {}\n",
    "loaders/b1.7.3-ornithe/src/ServerGrugModLoader.java": "class S {}\n",
    "loaders/b1.7.3-stationapi/src/ServerStationApiAdapter.java": "class S {}\n",
    "loaders/1.20.6-forge/src/main/java/GrugServerRun.java": "class S {}\n",
}
ALL = ["1.2.5-forge", "1.20.6-forge", "a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi"]
ORNITHE = ["a1.1.2_01-ornithe", "b1.7.3-ornithe"]
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

    def loaders_of(self, changes: dict, event="pull_request", base=None) -> list:
        out = self.answer(changes, base, "--event", event, "--loaders")
        return json.loads(out)

    def test_a_pull_request_or_push_that_cannot_reach_server_code_runs_no_leg(self):
        for name, changes in {
            "mod port and prose": {
                "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
                "AGENTS.md": "agents2\n",
            },
            "core": {"core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "c2\n"},
            "api": {"mod_api.json": '{"a":1}'},
            "coverage mod": {"mods/coverage/tests/x-Test.grug": "tick() { }\n"},
            "workflow and scripts": {
                ".github/workflows/build.yml": "name: B2\n",
                ".github/scripts/run-loader.sh": "# run2\n",
            },
            "test save": {"test-saves/a.zip": "zip2"},
        }.items():
            for event in ("pull_request", "push"):
                with self.subTest(name=name, event=event):
                    self.setUp()
                    self.assertEqual(self.loaders_of(changes, event), [])
                    self.assertIn("skipping", self.reason)
                    self.setUp()
                    self.assertEqual(self.answer(changes, None, "--event", event), "false")

    def test_each_loaders_server_code_runs_exactly_its_own_leg(self):
        cases = {
            "1.2.5-forge": "loaders/1.2.5-forge/src/Server.java",
            "1.20.6-forge": "loaders/1.20.6-forge/src/main/java/GrugServerRun.java",
            "a1.1.2_01-ornithe": "loaders/a1.1.2_01-ornithe/src/ServerGrugModLoader.java",
            "b1.7.3-ornithe": "loaders/b1.7.3-ornithe/src/ServerGrugModLoader.java",
            "b1.7.3-stationapi": "loaders/b1.7.3-stationapi/src/ServerStationApiAdapter.java",
        }
        for loader, path in cases.items():
            for event in ("pull_request", "push"):
                with self.subTest(loader=loader, event=event):
                    self.setUp()
                    changes = {path: "changed\n", "mods/buildcraft/code/wrench-Item.grug": "x\n"}
                    self.assertEqual(self.loaders_of(changes, event), [loader])
                    self.assertIn(path, self.reason)
                    self.setUp()
                    self.assertEqual(self.answer(changes, None, "--event", event), "true")

    def test_the_shared_ornithe_directory_runs_both_ornithe_legs_and_no_other(self):
        changes = {"shared/ornithe/src/main/java/Common.java": "class Common { int x; }\n"}
        self.assertEqual(self.loaders_of(changes), ORNITHE)

    def test_two_loaders_in_one_diff_run_both_legs_in_name_order(self):
        changes = {
            "loaders/b1.7.3-stationapi/src/ServerStationApiAdapter.java": "n\n",
            "loaders/1.2.5-forge/src/Server.java": "n\n",
        }
        self.assertEqual(self.loaders_of(changes), ["1.2.5-forge", "b1.7.3-stationapi"])

    def test_a_loader_build_file_runs_that_loaders_leg(self):
        changes = {"loaders/1.20.6-forge/build.gradle": "plugins {}\n"}
        self.assertEqual(self.loaders_of(changes), ["1.20.6-forge"])

    def test_a_shared_path_no_build_declares_runs_every_leg(self):
        self.assertEqual(self.loaders_of({"shared/Shared.java": "class Shared { int x; }\n"}), ALL)
        self.assertIn("no loader's build declares", self.reason)

    def test_a_path_under_loaders_naming_no_loader_runs_every_leg(self):
        self.assertEqual(self.loaders_of({"loaders/notes.txt": "n\n"}), ALL)
        self.assertEqual(self.loaders_of({"loaders/other/src/X.java": "n\n"}), ALL)

    def test_a_pull_request_or_push_with_no_base_runs_every_leg(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        for event in ("pull_request", "push"):
            with self.subTest(event):
                self.assertEqual(self.loaders_of(changes, event, ""), ALL)
                self.assertIn("names no base", self.reason)
                self.assertEqual(self.answer(changes, "", "--event", event), "true")

    def test_an_unreachable_pull_request_base_runs_every_leg(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        self.assertEqual(self.loaders_of(changes, "pull_request", "0" * 40), ALL)
        self.assertEqual(self.answer(changes, "0" * 40, "--event", "pull_request"), "true")

    def test_an_empty_pull_request_diff_runs_every_leg(self):
        head = self.repo.commit({})
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                head,
                head,
                "--repo",
                str(self.repo.path),
                "--event",
                "pull_request",
                "--loaders",
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(json.loads(result.stdout), ALL)

    def test_a_dispatch_runs_every_loader_with_a_server(self):
        changes = {"core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "c2\n"}
        self.assertEqual(self.loaders_of(changes, "workflow_dispatch", ""), ALL)

    def test_a_dispatch_of_mod_ports_runs_no_leg(self):
        changes = {"mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n"}
        out = self.answer(changes, None, *DISPATCH, "--loaders")
        self.assertEqual(json.loads(out), [])

    def test_every_loader_is_a_loader_directory_with_a_server(self):
        root = HERE.parent.parent / "loaders"
        spec = importlib.util.spec_from_file_location("dedicated_needed", SCRIPT)
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        self.assertEqual(
            sorted(module.DISPATCH_LOADERS), sorted(p.name for p in root.iterdir() if p.is_dir())
        )


if __name__ == "__main__":
    unittest.main()
