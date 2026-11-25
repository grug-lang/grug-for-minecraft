#!/usr/bin/env python3
"""Tests server-exec-needed.py: a loader's dedicated leg runs when its carried exec data cannot
match the head's class bytes (#1739)."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "server-exec-needed.py"

SPEC = importlib.util.spec_from_file_location("server_exec_needed", HERE / "server-exec-needed.py")
mod = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod)

REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

ALL = ["1.2.5-forge", "1.20.6-forge", "a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi"]
ORNITHE = ["a1.1.2_01-ornithe", "b1.7.3-ornithe"]

# One source file per loader, and the two Ornithe builds that declare the shared directory, so the
# fixture has the shape dedicated-needed.py's own tests use. A change to any of these is the record
# of a loader's class bytes moving.
BASE = {
    "mod_api.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "class H {}\n",
    "loaders/1.2.5-forge/src/main/java/net/grug/minecraft/forge125/Grug125ServerAdapter.java": "class A {}\n",
    "loaders/1.20.6-forge/src/main/java/net/grug/minecraft/forge/GrugServerRun.java": "class A {}\n",
    "loaders/a1.1.2_01-ornithe/src/main/java/net/grug/minecraft/ornithe/ServerGrugModLoader.java": "class A {}\n",
    "loaders/b1.7.3-ornithe/src/main/java/net/grug/minecraft/ornithe/ServerGrugModLoader.java": "class A {}\n",
    "loaders/b1.7.3-stationapi/src/main/java/net/grug/minecraft/stationapi/ServerStationApiAdapter.java": "class A {}\n",
    "loaders/a1.1.2_01-ornithe/build.gradle": (
        "sourceSets { main { java { srcDir '../../shared/ornithe/src/main/java' } } }\n"
    ),
    "loaders/b1.7.3-ornithe/build.gradle": (
        "sourceSets { main { java { srcDir '../../shared/ornithe/src/main/java' } } }\n"
    ),
    "shared/ornithe/src/main/java/net/grug/minecraft/ornithe/Common.java": "class Common {}\n",
}


class ServerExecNeededTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.artifact = self.repo.commit(BASE, "the commit the artifact was recorded at")

    def select(self, changes: dict, bases=None, already=None):
        head = self.repo.commit(changes)
        chosen, lines = mod.select(
            self.repo.path,
            head,
            {loader: self.artifact for loader in ALL} if bases is None else bases,
            already,
        )
        self.lines = "\n".join(lines)
        return chosen

    # What forces a leg: a loader's class bytes moving after its artifact was recorded.

    def test_a_changed_class_under_one_loader_selects_that_one_leg(self):
        chosen = self.select(
            {
                "loaders/b1.7.3-stationapi/src/main/java/net/grug/minecraft/stationapi/ServerStationApiAdapter.java": "class A { int x; }\n",
            }
        )
        self.assertEqual(chosen, ["b1.7.3-stationapi"])
        self.assertIn("ServerStationApiAdapter", self.lines)

    def test_the_1735_merge_selects_only_the_two_loaders_it_changed(self):
        # The shape of #1711's merge against #1735's run: only the two loaders it changed run.
        chosen = self.select(
            {
                "loaders/1.2.5-forge/src/main/java/net/grug/minecraft/forge125/Grug125ServerAdapter.java": "class A { int x; }\n",
                "loaders/b1.7.3-stationapi/src/main/java/net/grug/minecraft/stationapi/ServerStationApiAdapter.java": "class A { int x; }\n",
            }
        )
        self.assertEqual(chosen, ["1.2.5-forge", "b1.7.3-stationapi"])
        self.assertIn("Grug125ServerAdapter", self.lines)

    def test_an_unchanged_loader_selects_nothing(self):
        # A mod-only diff moves no loader's class bytes, so every recording still matches.
        self.assertEqual(
            self.select(
                {"mods/buildcraft/code/wrench-Item.grug": "on_use() { ctx.screenshot() }\n"}
            ),
            [],
        )
        self.assertIn("no server code changed", self.lines)

    def test_a_recording_at_the_head_selects_nothing(self):
        # A branch with nothing since the artifact's commit has an empty diff, which for this check
        # means the recording still matches rather than the empty diff that runs every leg elsewhere.
        head = self.repo.commit({"mods/buildcraft/code/wrench-Item.grug": "x\n"})
        chosen, lines = mod.select(self.repo.path, head, {loader: head for loader in ALL})
        self.assertEqual(chosen, [])
        self.assertIn("no server code changed", "\n".join(lines))

    def test_a_changed_shared_file_under_core_selects_every_leg(self):
        chosen = self.select(
            {
                "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "class H { int x; }\n"
            }
        )
        self.assertEqual(chosen, ALL)

    def test_a_changed_shared_ornithe_file_selects_both_ornithe_legs(self):
        chosen = self.select(
            {
                "shared/ornithe/src/main/java/net/grug/minecraft/ornithe/Common.java": "class C { int x; }\n"
            }
        )
        self.assertEqual(chosen, ORNITHE)

    def test_a_loader_build_file_change_selects_that_loader(self):
        self.assertEqual(
            self.select({"loaders/1.20.6-forge/build.gradle": "plugins {}\n"}), ["1.20.6-forge"]
        )

    # The missing and unreadable cases run the leg, never fewer.

    def test_a_missing_artifact_selects_that_loader(self):
        bases = {loader: self.artifact for loader in ALL if loader != "b1.7.3-ornithe"}
        chosen = self.select({"mods/buildcraft/code/wrench-Item.grug": "x\n"}, bases)
        self.assertEqual(chosen, ["b1.7.3-ornithe"])
        self.assertIn("no live server-exec artifact", self.lines)

    def test_an_artifact_whose_run_named_no_commit_selects_that_loader(self):
        bases = {loader: self.artifact for loader in ALL}
        bases["a1.1.2_01-ornithe"] = ""
        chosen = self.select({"mods/buildcraft/code/wrench-Item.grug": "x\n"}, bases)
        self.assertEqual(chosen, ["a1.1.2_01-ornithe"])

    def test_a_commit_this_cannot_read_selects_that_loader(self):
        bases = {loader: self.artifact for loader in ALL}
        bases["1.2.5-forge"] = "0" * 40
        chosen = self.select({"mods/buildcraft/code/wrench-Item.grug": "x\n"}, bases)
        self.assertEqual(chosen, ["1.2.5-forge"])
        self.assertIn("cannot be read", self.lines)

    # The union with the run's own diff keeps one entry per loader.

    def test_a_leg_the_run_already_selected_appears_once(self):
        chosen = self.select(
            {
                "loaders/1.2.5-forge/src/main/java/net/grug/minecraft/forge125/Grug125ServerAdapter.java": "class A { int x; }\n"
            },
            already=["1.2.5-forge"],
        )
        self.assertEqual(chosen, ["1.2.5-forge"])

    def test_an_already_selected_leg_with_a_matching_recording_is_kept(self):
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "x\n"}, already=["1.20.6-forge"]),
            ["1.20.6-forge"],
        )

    # The command the discover job runs.

    def base_args(self) -> list[str]:
        """Every loader's artifact from the same run, as the discover step passes them."""
        args: list[str] = []
        for loader in ALL:
            args += ["--base", f"{loader}={self.artifact}"]
        return args

    def run_cli(self, changes: dict, *extra: str):
        head = self.repo.commit(changes)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
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
        return json.loads(result.stdout)

    def test_the_cli_prints_the_union_and_the_reasons(self):
        args = self.base_args()
        self.assertEqual(
            self.run_cli(
                {
                    "loaders/b1.7.3-ornithe/src/main/java/net/grug/minecraft/ornithe/ServerGrugModLoader.java": "class S { int x; }\n"
                },
                *args,
            ),
            ["b1.7.3-ornithe"],
        )
        self.assertIn("server-exec-needed:", self.reason)
        self.assertIn("no server code changed", self.reason)

    def test_the_cli_keeps_the_runs_own_selection(self):
        args = self.base_args()
        self.assertEqual(
            self.run_cli(
                {"mods/buildcraft/code/wrench-Item.grug": "x\n"},
                *args,
                "--already",
                json.dumps(["1.20.6-forge"]),
            ),
            ["1.20.6-forge"],
        )

    def test_the_cli_treats_a_base_with_no_sha_as_missing(self):
        args: list[str] = []
        for loader in ALL:
            if loader != "1.2.5-forge":
                args += ["--base", f"{loader}={self.artifact}"]
        args += ["--base", "1.2.5-forge="]
        self.assertEqual(
            self.run_cli({"mods/buildcraft/code/wrench-Item.grug": "x\n"}, *args),
            ["1.2.5-forge"],
        )


if __name__ == "__main__":
    unittest.main()
