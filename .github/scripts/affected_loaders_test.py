#!/usr/bin/env python3
"""Tests affected-loaders.py: a pull request runs the loaders its diff reaches, plus one per family."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "affected-loaders.py"
SPEC = importlib.util.spec_from_file_location("affected_loaders", SCRIPT)
affected_loaders = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(affected_loaders)

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

GAME = affected_loaders.sibling("universal_jar").Game

# The repository's five loaders, each named and built the way it is, because the family a loader
# belongs to comes from universal_jar.py's games rather than from this file, and the code two of them
# share comes from their own build files.
ORNITHE = """plugins { id 'fabric-loom' }
sourceSets {
    main {
        java { srcDir '../../shared/ornithe/src/main/java' }
        resources { srcDir '../../shared/ornithe/src/main/resources' }
    }
}
"""
BASE = {
    "loaders/1.20.6-forge/build.gradle": "plugins { id 'net.minecraftforge.gradle' }\n",
    "loaders/1.20.6-forge/src/main/java/Forge.java": "class Forge {}\n",
    "loaders/1.2.5-forge/build.gradle": "sourceSets { main { java { srcDir generatedSourcesDir } } }\n",
    "loaders/1.2.5-forge/src/main/java/Forge125.java": "class Forge125 {}\n",
    "loaders/a1.1.2_01-ornithe/build.gradle": ORNITHE,
    "loaders/a1.1.2_01-ornithe/src/main/java/Alpha.java": "class Alpha {}\n",
    "loaders/b1.7.3-ornithe/build.gradle": ORNITHE,
    "loaders/b1.7.3-ornithe/src/main/java/Beta.java": "class Beta {}\n",
    "loaders/b1.7.3-stationapi/build.gradle.kts": (
        "// srcDir '../../shared/ornithe/src/main/java' is not this loader's source: it is Ornithe's\n"
        'plugins { id("fabric-loom") }\n'
    ),
    "loaders/b1.7.3-stationapi/src/main/java/Station.java": "class Station {}\n",
    "shared/ornithe/src/main/java/net/grug/Shared.java": "class Shared {}\n",
    "shared/ornithe/src/main/resources/grug.mixins.json": "{}\n",
    "core/src/Main.java": "class Main {}\n",
    "mod_api.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    # affected-mods.py always selects coverage, and refuses to narrow without it, so the fixture
    # carries it: a mod-only diff must narrow to that mod here as it does in the real repository.
    "mods/coverage/about.json": '{"name": "coverage"}',
    "mods/coverage/code/frame-Block.grug": "on_tick() {}\n",
    # A mod that declares none, and one whose declaration does not parse (#910).
    "mods/everywhere/about.json": '{"name": "Everywhere"}',
    "mods/everywhere/code/other-Block.grug": "on_tick() {}\n",
    "mods/broken/about.json": "{not json",
    "mods/broken/code/third-Block.grug": "on_tick() {}\n",
    ".github/scripts/run-loader.sh": "# run\n",
    ".github/workflows/build.yml": "name: Build\n",
}
ALL = [
    "1.2.5-forge",
    "1.20.6-forge",
    "a1.1.2_01-ornithe",
    "b1.7.3-ornithe",
    "b1.7.3-stationapi",
]
# One per mechanism a mod loader reads a jar by: the newest Forge, the 1.2.5 Forge, and of the three
# Fabric-family loaders the one no other loader shares code with.
REPRESENTATIVES = ["1.2.5-forge", "1.20.6-forge", "b1.7.3-stationapi"]
# A port that runs on an Ornithe loader no family represents, which is the normal shape of one.
PORT = {
    "mods/betaonly/about.json": '{"loaders": ["b1.7.3-ornithe"]}',
    "mods/betaonly/code/thing-Block.grug": "on_tick() {}\n",
}


class SelectionCase(unittest.TestCase):
    """A throwaway repository whose base commit is FIXTURE, and the script's answer for a diff."""

    FIXTURE = BASE

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(self.FIXTURE, "base")

    def select(self, changes: dict, base=None):
        """The script's stdout for a commit with these changes, as the list of loaders."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [sys.executable, str(SCRIPT), base or self.base, head, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return json.loads(result.stdout)


class AffectedLoadersTest(SelectionCase):
    # What a diff outside the loaders' own code rests on: one representative per family.

    def test_a_change_in_a_mod_runs_the_representatives(self):
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "on_use() { x() }\n"}),
            REPRESENTATIVES,
        )

    # A mod's about.json loaders keeps a job where its tests can run (#910).

    def test_a_mod_change_adds_the_loaders_it_declares(self):
        # b1.7.3-ornithe is no family's representative, so without the declaration this mod's tests
        # would never run on the loader it says they are for.
        self.assertEqual(
            self.select(
                {
                    "mods/betaonly/about.json": '{"loaders": ["b1.7.3-ornithe"]}',
                    "mods/betaonly/code/thing-Block.grug": "on_tick() {}\n",
                }
            ),
            ["1.2.5-forge", "1.20.6-forge", "b1.7.3-ornithe", "b1.7.3-stationapi"],
        )
        self.assertIn(
            "mods/betaonly declares b1.7.3-ornithe, so b1.7.3-ornithe runs too", self.reason
        )

    def test_a_declared_loader_a_representative_already_has_adds_no_job(self):
        self.assertEqual(
            self.select(
                {
                    "mods/forgeonly/about.json": '{"loaders": ["1.20.6-forge"]}',
                    "mods/forgeonly/code/thing-Block.grug": "on_tick() {}\n",
                }
            ),
            REPRESENTATIVES,
        )

    def test_a_mod_declaring_a_representative_and_another_loader_runs_both(self):
        # One declared loader being a family's representative does not stand in for the mod's other
        # declared loader: #909 rule 2 runs the loaders a changed mod declares, and b1.7.3-ornithe is
        # a mechanism 1.20.6-forge's job does not exercise for it (the review of #920: before this,
        # `declared & chosen` suppressed the rest, so the mod never ran on its own loader).
        self.assertEqual(
            self.select(
                {
                    "mods/both/about.json": ('{"loaders": ["1.20.6-forge", "b1.7.3-ornithe"]}'),
                    "mods/both/code/thing-Block.grug": "on_tick() {}\n",
                }
            ),
            ["1.2.5-forge", "1.20.6-forge", "b1.7.3-ornithe", "b1.7.3-stationapi"],
        )
        self.assertIn("b1.7.3-ornithe runs too", self.reason)
        self.assertNotIn("1.20.6-forge runs too", self.reason)

    def test_a_declared_loader_that_is_no_directory_adds_no_job(self):
        # A name nothing provides cannot be run, and the mod runs on every loader instead, so the
        # representatives are where its tests go. The log says why rather than claiming a job.
        self.assertEqual(
            self.select(
                {
                    "mods/stale/about.json": '{"loaders": ["b1.7.3-gone"]}',
                    "mods/stale/code/thing-Block.grug": "on_tick() {}\n",
                }
            ),
            REPRESENTATIVES,
        )
        self.assertIn(
            "mods/stale declares b1.7.3-gone, which is no loaders/ directory", self.reason
        )

    def test_a_mod_that_declares_no_loader_adds_none(self):
        # Absence means every loader, which every representative already satisfies, so the sample
        # stays the sample rather than becoming every loader.
        self.assertEqual(
            self.select({"mods/everywhere/code/other-Block.grug": "on_tick() { x() }\n"}),
            REPRESENTATIVES,
        )

    def test_a_declaration_that_does_not_parse_adds_none(self):
        # It fails validate-about.py, so it never lands; the answer here runs more, never fewer.
        self.assertEqual(
            self.select({"mods/broken/code/third-Block.grug": "on_tick() { x() }\n"}),
            REPRESENTATIVES,
        )

    def test_a_change_in_core_runs_the_representatives(self):
        self.assertEqual(
            self.select({"core/src/Main.java": "class Main { int x; }\n"}), REPRESENTATIVES
        )

    def test_a_change_in_the_host_api_runs_the_representatives(self):
        self.assertEqual(self.select({"mod_api.json": "[]"}), REPRESENTATIVES)

    def test_a_change_in_the_scripts_runs_the_representatives(self):
        self.assertEqual(
            self.select({".github/scripts/run-loader.sh": "# run again\n"}), REPRESENTATIVES
        )

    def test_a_change_in_the_workflow_runs_the_representatives(self):
        self.assertEqual(
            self.select({".github/workflows/build.yml": "name: Build\non: push\n"}),
            REPRESENTATIVES,
        )

    # What a diff confined to a loader rests on: its own job and the code it shares, on top of the
    # representatives, which run whatever the diff holds.

    def test_a_change_confined_to_a_representative_runs_only_the_representatives(self):
        self.assertEqual(
            self.select({"loaders/b1.7.3-stationapi/src/main/java/Station.java": "class S {}\n"}),
            REPRESENTATIVES,
        )
        self.assertEqual(
            self.select({"loaders/1.20.6-forge/src/main/java/Forge.java": "class F {}\n"}),
            REPRESENTATIVES,
        )

    def test_a_change_confined_to_a_shared_loader_runs_the_loaders_sharing_its_code(self):
        self.assertEqual(
            self.select({"loaders/a1.1.2_01-ornithe/src/main/java/Alpha.java": "class A {}\n"}),
            ALL,
        )

    def test_a_change_confined_to_the_other_shared_loader_runs_the_same(self):
        self.assertEqual(
            self.select({"loaders/b1.7.3-ornithe/src/main/java/Beta.java": "class B {}\n"}),
            ALL,
        )

    def test_a_change_to_a_shared_directory_runs_the_loaders_that_compile_it(self):
        self.assertEqual(
            self.select({"shared/ornithe/src/main/java/net/grug/Shared.java": "class S {}\n"}),
            ALL,
        )

    def test_a_change_mixing_a_loader_and_core_runs_that_loader_and_the_representatives(self):
        self.assertEqual(
            self.select(
                {
                    "core/src/Main.java": "class Main { int y; }\n",
                    "loaders/b1.7.3-stationapi/build.gradle.kts": "plugins { }\n",
                }
            ),
            REPRESENTATIVES,
        )

    def test_a_deleted_shared_directory_still_reaches_the_loaders_that_compile_it(self):
        # Deleting the shared files leaves the builds that include them unchanged, so the directory
        # it was is still what the loaders compile.
        self.assertEqual(
            self.select(
                {
                    "shared/ornithe/src/main/java/net/grug/Shared.java": None,
                    "shared/ornithe/src/main/resources/grug.mixins.json": None,
                }
            ),
            ALL,
        )

    def test_a_source_directory_named_in_a_comment_is_not_one(self):
        # stationapi's build names the Ornithe directory in a comment only, so a change there is not
        # its: the fixture holds that comment, and this diff is the shared code alone.
        self.assertIn("// srcDir", BASE["loaders/b1.7.3-stationapi/build.gradle.kts"])
        self.assertEqual(
            self.select({"shared/ornithe/src/main/java/net/grug/Shared.java": "class S {}\n"}),
            ALL,
        )

    # What the selector does when it cannot tell: it runs more, never fewer.

    def test_an_empty_diff_runs_every_loader(self):
        self.assertEqual(self.select({}), ALL)

    def test_a_diff_that_cannot_be_computed_runs_every_loader(self):
        self.assertEqual(
            self.select({"core/src/Main.java": "class Main { int z; }\n"}, base="0" * 40), ALL
        )

    def test_a_file_directly_in_loaders_runs_the_representatives(self):
        self.assertEqual(self.select({"loaders/README.md": "loaders\n"}), REPRESENTATIVES)

    def test_a_deleted_loader_runs_the_representatives_of_the_rest(self):
        # The Ornithe pair is what is left of the Fabric family, and the newer of the two takes it.
        self.assertEqual(
            self.select(
                {
                    "loaders/b1.7.3-stationapi/build.gradle.kts": None,
                    "loaders/b1.7.3-stationapi/src/main/java/Station.java": None,
                }
            ),
            ["1.2.5-forge", "1.20.6-forge", "b1.7.3-ornithe"],
        )

    def test_a_loader_no_game_names_runs_on_every_pull_request(self):
        # A loader being added is a family of its own, so it runs rather than being dropped until
        # universal_jar.py's games name it.
        self.assertEqual(
            self.select(
                {
                    "loaders/1.21-forge/build.gradle": "plugins { }\n",
                    "mods/buildcraft/code/wrench-Item.grug": "on_use() { y() }\n",
                }
            ),
            ["1.2.5-forge", "1.20.6-forge", "1.21-forge", "b1.7.3-stationapi"],
        )


class RepresentativesTest(unittest.TestCase):
    def test_one_representative_per_mechanism_newest_minecraft_first(self):
        games = [
            GAME("old fabric", "fabric", "1.0.0-beta.7.3", {}, "x-ornithe"),
            GAME("new fabric", "fabric", "1.0.0-beta.7.4", {}, "y-ornithe"),
            GAME("forge", "forge", "1.20.6", {}, "1.20.6-forge"),
        ]
        self.assertEqual(
            affected_loaders.representatives(["x-ornithe", "y-ornithe", "1.20.6-forge"], games),
            {"fabric": "y-ornithe", "forge": "1.20.6-forge"},
        )

    def test_a_mechanism_prefers_the_loader_no_other_shares_code_with(self):
        games = [
            GAME("a", "fabric", "1.0.0-beta.7.3", {}, "a-ornithe"),
            GAME("b", "fabric", "1.0.0-beta.7.3", {}, "b-stationapi"),
        ]
        self.assertEqual(
            affected_loaders.representatives(
                ["a-ornithe", "b-stationapi"], games, {"a-ornithe": {"c-ornithe"}}
            ),
            {"fabric": "b-stationapi"},
        )

    def test_a_mechanism_with_two_loaders_on_one_minecraft_takes_the_first_name(self):
        games = [
            GAME("a", "fabric", "1.0.0-beta.7.3", {}, "a-ornithe"),
            GAME("b", "fabric", "1.0.0-beta.7.3", {}, "b-ornithe"),
        ]
        self.assertEqual(
            affected_loaders.representatives(["b-ornithe", "a-ornithe"], games),
            {"fabric": "a-ornithe"},
        )

    def test_every_loader_in_this_repository_is_named_by_a_game(self):
        # A loader no game names still runs on every pull request, but as a family of its own, which
        # is a gap in universal_jar.py's games rather than a selection this script can make.
        root = HERE.parent.parent
        loaders = affected_loaders.loader_dirs(root)
        self.assertEqual(
            sorted(affected_loaders.game_of(loaders)),
            loaders,
            "universal_jar.py's GAMES names no game for a loaders/ directory",
        )


class DeclaredLoaderTest(SelectionCase):
    """A pull request leaves every mod whose tests it runs at least one job where they run (#910)."""

    FIXTURE = {**BASE, **PORT}

    def test_a_core_change_runs_the_loader_an_ornithe_only_port_declares(self):
        # A core change runs every mod's tests, so a port that runs only on b1.7.3-ornithe has to get
        # a job even though no path of its own is in the diff, which is its normal shape.
        self.assertEqual(
            self.select({"core/src/Main.java": "class Main { int y; }\n"}),
            ["1.2.5-forge", "1.20.6-forge", "b1.7.3-ornithe", "b1.7.3-stationapi"],
        )
        self.assertIn(
            "mods/betaonly declares b1.7.3-ornithe, so b1.7.3-ornithe runs too", self.reason
        )

    def test_a_change_to_another_mod_does_not_add_it(self):
        # The port's tests do not run for a diff confined to buildcraft, so its loader is not paid
        # for: only the mods whose tests the pull request runs need a job.
        self.assertEqual(
            self.select({"mods/buildcraft/code/wrench-Item.grug": "on_use() { x() }\n"}),
            REPRESENTATIVES,
        )


if __name__ == "__main__":
    unittest.main()
