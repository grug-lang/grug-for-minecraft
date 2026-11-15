#!/usr/bin/env python3
"""Tests smoke-selection.py: which smoke games a run boots, and each conservative default (#1344)."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "smoke-selection.py"

# main_selection_test.py already holds the five loaders as the repository builds them.
SPEC = importlib.util.spec_from_file_location(
    "main_selection_test", HERE / "main_selection_test.py"
)
fixture = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(fixture)

FABRIC = {"babric-b1.7.3"}
FORGE = {"forge-1.16.5", "forge-1.21.1"}


def full() -> set[str]:
    out = subprocess.run(
        [sys.executable, str(HERE / "universal_jar.py"), "smoke-matrix"],
        capture_output=True,
        text=True,
        check=True,
    )
    return {g["recipe"] for g in json.loads(out.stdout)}


class SmokeSelectionCase(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = fixture.repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(fixture.BASE, "base")

    def games(self, changes: dict, event="push", base=None) -> set[str]:
        self.repo.commit(changes)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                event,
                self.base if base is None else base,
                "--repo",
                str(self.repo.path),
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return {g["recipe"] for g in json.loads(result.stdout)}

    def test_the_full_matrix_has_every_mechanism(self):
        self.assertGreater(len(full()), len(FORGE | FABRIC))

    def test_one_loader_boots_the_games_of_its_mechanism(self):
        changes = {"loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n"}
        self.assertEqual(self.games(changes), FORGE)

    def test_a_loader_whose_mechanism_has_no_game_boots_none(self):
        changes = {"loaders/1.2.5-forge/src/main/java/Forge125.java": "class Forge125 { int x; }\n"}
        self.assertEqual(self.games(changes), set())

    def test_the_shared_code_of_two_loaders_boots_their_mechanism_once(self):
        changes = {"shared/ornithe/src/main/java/net/grug/Shared.java": "class Shared { int x; }\n"}
        self.assertEqual(self.games(changes), FABRIC)

    def test_loaders_of_two_mechanisms_boot_both(self):
        changes = {
            "loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n",
            "loaders/b1.7.3-stationapi/src/main/java/Station.java": "class Station { int x; }\n",
        }
        self.assertEqual(self.games(changes), FORGE | FABRIC)

    def test_a_mod_file_beside_a_loader_file_is_ignored(self):
        changes = {
            "loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n",
            "mods/coverage/code/frame-Block.grug": "on_tick() { }\n",
        }
        self.assertEqual(self.games(changes), FORGE)

    def test_a_wide_diff_boots_every_game(self):
        for path in (
            "core/src/Main.java",
            "mod_api.json",
            ".github/workflows/build.yml",
            ".github/scripts/run-loader.sh",
        ):
            with self.subTest(path=path):
                self.assertEqual(self.games({path: "changed\n"}), full())

    def test_one_outside_path_beats_a_loader_path(self):
        changes = {
            "loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n",
            "core/src/Main.java": "class Main { int x; }\n",
        }
        self.assertEqual(self.games(changes), full())

    def test_a_file_straight_in_loaders_boots_every_game(self):
        self.assertEqual(self.games({"loaders/README.md": "x\n"}), full())

    def test_an_unknown_loader_directory_boots_every_game(self):
        self.assertEqual(self.games({"loaders/newcomer/src/A.java": "class A {}\n"}), full())

    def test_an_empty_diff_boots_every_game(self):
        self.repo.commit({"loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge {}\n"})
        self.assertEqual(self.games({}, base="HEAD"), full())

    def test_an_unreadable_base_boots_every_game(self):
        changes = {"loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n"}
        self.assertEqual(self.games(changes, base="0" * 40), full())

    def test_a_missing_base_boots_every_game(self):
        changes = {"loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n"}
        self.assertEqual(self.games(changes, base=""), full())

    def test_a_pull_request_a_dispatch_and_the_schedule_boot_every_game(self):
        changes = {"loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge { int x; }\n"}
        for event in ("pull_request", "workflow_dispatch", "schedule"):
            with self.subTest(event=event):
                self.assertEqual(self.games(changes, event=event), full())


if __name__ == "__main__":
    unittest.main()
