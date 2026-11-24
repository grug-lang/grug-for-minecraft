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

SCRIPT_SPEC = importlib.util.spec_from_file_location("dedicated_needed", SCRIPT)
script = importlib.util.module_from_spec(SCRIPT_SPEC)
SCRIPT_SPEC.loader.exec_module(script)

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

    # A test that captures, or reads what the capture is drawn from, reaches the joined client of a
    # dedicated leg and can fail there while every client leg passes (#1696). The two shapes below
    # are #1696's: before the fix it stepped from ctx.camera(), and after it from ctx.get_origin().
    OVERLAY_FOLLOWS_PLAYER = """\
export tick(ctx: TestTickContext) {
    origin: Vec3 = ctx.get_origin()
    ctx.set_player_position(origin.x() + 3.0, origin.y() - 4.62, origin.z())
    ctx.screenshot().equals(r"screenshots/overlay_follows_player", 100, 430, 840, 640)
}
"""
    OVERLAY_BEFORE_1696 = """\
export tick(ctx: TestTickContext) {
    before: Vec3 = ctx.camera()
    ctx.set_player_position(before.x() + 3.0, before.y() - 1.62, before.z())
    ctx.screenshot().equals(r"screenshots/overlay_follows_player", 100, 430, 840, 640)
}
"""

    def test_a_mod_test_that_draws_a_capture_runs_the_legs(self):
        changes = {
            "mods/safesquares/tests/overlay_follows_player-Test.grug": self.OVERLAY_FOLLOWS_PLAYER
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("screenshot", self.reason)

    def test_the_1696_change_runs_the_legs(self):
        changes = {
            "mods/safesquares/tests/overlay_follows_player-Test.grug": self.OVERLAY_FOLLOWS_PLAYER,
            "mods/safesquares/about.json": '{"b":2}',
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("overlay_follows_player-Test.grug", self.reason)

    def test_a_mod_test_that_reads_the_joined_cameras_camera_runs_the_legs(self):
        changes = {
            "mods/safesquares/tests/overlay_follows_player-Test.grug": self.OVERLAY_BEFORE_1696
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("camera", self.reason)

    def test_a_mod_test_that_only_reads_the_world_and_the_items_keeps_the_saving(self):
        # get_origin and get_player answer from this side's own test player, which on a dedicated
        # leg is the server's copy, so a test that only builds from them cannot fail there.
        test = """\
export tick(ctx: TestTickContext) {
    level: Level = ctx.get_client_level()
    origin: Vec3 = ctx.get_origin()
    player: Player = ctx.get_player()
    level.place_block(origin.x(), origin.y() - 4.0, origin.z(), "minecraft:stone")
    held: Option = player.get_held_item()
    ctx.assert(held.has(), "the player should hold an item")
    ctx.not_done()
}
"""
        changes = {
            "mods/buildcraft/tests/pipe-Test.grug": test,
            "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "false")
        self.assertIn("all 2 changed paths", self.reason)

    def test_a_changed_grug_outside_tests_keeps_the_saving(self):
        # Only a test file's own calls are read, so a code file's text cannot run a leg.
        changes = {
            "mods/buildcraft/code/wrench-Item.grug": "# ctx.screenshot() is a test's call\non_use() { }\n",
            "mods/buildcraft/tests/wrench-Test.grug": "tick() { }\n",
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "false")

    def test_a_deleted_test_file_runs_the_legs(self):
        changes = {"mods/buildcraft/tests/wrench-Test.grug": None}
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("cannot be read", self.reason)

    def test_a_renamed_test_file_runs_the_legs(self):
        # A rename is a delete and an add, and the deleted half cannot be read at the head.
        changes = {
            "mods/buildcraft/tests/wrench-Test.grug": None,
            "mods/buildcraft/tests/wrench_renamed-Test.grug": "tick() { }\n",
        }
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("wrench-Test.grug", self.reason)

    def test_a_test_path_that_is_not_a_grug_runs_the_legs(self):
        changes = {"mods/buildcraft/tests/notes.md": "notes\n"}
        self.assertEqual(self.answer(changes, None, *DISPATCH), "true")
        self.assertIn("not a .grug test file", self.reason)

    def test_a_test_file_the_script_cannot_read_runs_the_legs(self):
        reason = script.mod_test_reason(
            Path(self.repo.path), "0" * 40, "mods/buildcraft/tests/wrench-Test.grug"
        )
        self.assertIsNotNone(reason)
        self.assertIn("cannot be read", reason)

    def test_a_pull_request_that_changes_a_joined_client_test_runs_every_leg(self):
        changes = {
            "mods/safesquares/tests/overlay_follows_player-Test.grug": self.OVERLAY_FOLLOWS_PLAYER
        }
        for event in ("pull_request", "push"):
            with self.subTest(event):
                self.setUp()
                self.assertEqual(self.loaders_of(changes, event), ALL)
                self.assertIn("screenshot", self.reason)
                self.setUp()
                self.assertEqual(self.answer(changes, None, "--event", event), "true")

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
            "core unit test": {"core/src/test/java/XTest.java": "class XTest {}\n"},
            "workflow and scripts": {
                ".github/workflows/build.yml": "name: B2\n",
                ".github/scripts/run-loader.sh": "# run2\n",
            },
        }.items():
            for event in ("pull_request", "push"):
                with self.subTest(name=name, event=event):
                    self.setUp()
                    self.assertEqual(self.loaders_of(changes, event), [])
                    self.assertIn("skipping", self.reason)
                    self.setUp()
                    self.assertEqual(self.answer(changes, None, "--event", event), "false")

    def test_core_the_api_the_coverage_mod_and_the_root_build_run_every_leg(self):
        cases = {
            "core main": "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java",
            "core build": "core/build.gradle.kts",
            "api": "mod_api.json",
            "coverage test": "mods/coverage/tests/x-Test.grug",
            "coverage code": "mods/coverage/code/x-Block.grug",
            "gradle dir": "gradle/grug-core.gradle",
            "gradle properties": "gradle.properties",
            "settings": "settings.gradle.kts",
            "root build": "build.gradle.kts",
            "setup action": ".github/actions/setup-loader-build/action.yml",
            "test save": "test-saves/a.zip",
            "multiplayer harness": ".github/scripts/run-multiplayer.sh",
            "multiplayer test driver": ".github/scripts/run_multiplayer_test.py",
        }
        for name, path in cases.items():
            for event in ("pull_request", "push"):
                with self.subTest(name=name, event=event):
                    self.setUp()
                    self.assertEqual(self.loaders_of({path: "changed\n"}, event), ALL)
                    self.assertIn(path, self.reason)

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
