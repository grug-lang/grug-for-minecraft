#!/usr/bin/env python3
"""Tests main-selection.py: what a run of build.yml selects, per event, and each conservative default."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "main-selection.py"

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

# The repository's five loaders, each built the way it is, because the family a loader belongs to and
# the code two of them share are read from their own build files rather than listed here.
ORNITHE = """plugins { id 'fabric-loom' }
sourceSets {
    main {
        java { srcDir '../../shared/ornithe/src/main/java' }
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
    "loaders/b1.7.3-stationapi/build.gradle.kts": 'plugins { id("fabric-loom") }\n',
    "loaders/b1.7.3-stationapi/src/main/java/Station.java": "class Station {}\n",
    "shared/ornithe/src/main/java/net/grug/Shared.java": "class Shared {}\n",
    "core/src/Main.java": "class Main {}\n",
    "mod_api.json": "{}",
    # affected-mods.py always selects coverage and refuses to narrow without it.
    "mods/coverage/about.json": '{"name": "coverage"}',
    "mods/coverage/code/frame-Block.grug": "on_tick() {}\n",
    # The mod the reference job runs, whose harness is nothing here.
    "mods/buildcraft/about.json": '{"name": "BuildCraft"}',
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    # A mod named by nothing and naming nothing, which only its own changes reach.
    "mods/examplemod/about.json": '{"name": "Example"}',
    "mods/examplemod/code/foo-Block.grug": "on_tick() {}\n",
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
# A port that runs only on a loader no family represents, which is the normal shape of one.
PORT = {
    "mods/betaonly/about.json": '{"loaders": ["b1.7.3-ornithe"]}',
    "mods/betaonly/code/thing-Block.grug": "on_tick() {}\n",
}


class SelectionCase(unittest.TestCase):
    """A throwaway repository whose base commit is FIXTURE, and the script's answer for a run."""

    FIXTURE = BASE

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(self.FIXTURE, "base")

    def select(self, event: str, changes: dict, base: str | None = None, coverage_only=False):
        """The script's outputs for a run whose head commit holds these changes."""
        self.repo.commit(changes)
        args = [
            sys.executable,
            str(SCRIPT),
            event,
            self.base if base is None else base,
            "--repo",
            str(self.repo.path),
        ]
        if coverage_only:
            args.append("--coverage-only")
        result = subprocess.run(args, capture_output=True, text=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        outputs = {}
        for line in result.stdout.splitlines():
            key, _, value = line.partition("=")
            outputs[key] = value
        return outputs

    def every(self) -> dict:
        """The outputs of a run that selects nothing away."""
        return {
            "build": {loader: "" for loader in ALL},
            "installed": {loader: "" for loader in ALL},
            "mods": "",
            "smoke": "true",
            "reference": "true",
        }

    def answer(self, outputs: dict) -> dict:
        """The outputs as the loader-to-mods maps the workflow reads out of them."""
        return {
            "build": {entry["dir"]: entry["mods"] for entry in json.loads(outputs["build"])},
            "installed": {
                entry["dir"]: entry["mods"] for entry in json.loads(outputs["installed"])
            },
            "mods": outputs["mods"],
            "smoke": outputs["smoke"],
            "reference": outputs["reference"],
        }


class PushTest(SelectionCase):
    """A push to main runs the mods it merged, on the loaders that reach them, and the gate elsewhere."""

    def test_a_mod_only_merge_runs_that_mod_and_coverage_on_the_representatives(self):
        outputs = self.answer(
            self.select("push", {"mods/examplemod/code/foo-Block.grug": "on_tick() { x() }\n"})
        )
        # The representatives run the merged mod's tests, and every other loader still runs
        # coverage's, which is what keeps the coverage gate counting every loader.
        self.assertEqual(
            outputs["build"],
            {
                "1.2.5-forge": "coverage,examplemod",
                "1.20.6-forge": "coverage,examplemod",
                "a1.1.2_01-ornithe": "coverage",
                "b1.7.3-ornithe": "coverage",
                "b1.7.3-stationapi": "coverage,examplemod",
            },
        )
        self.assertEqual(
            outputs["installed"],
            {
                "1.2.5-forge": "coverage,examplemod",
                "1.20.6-forge": "coverage,examplemod",
                "b1.7.3-stationapi": "coverage,examplemod",
            },
        )
        self.assertEqual(outputs["mods"], "coverage,examplemod")
        # A smoke game boots the jar with no layer, so a mod's own files cannot reach it, and the
        # reference harness holds neither this mod's code nor its screenshots.
        self.assertEqual(outputs["smoke"], "false")
        self.assertEqual(outputs["reference"], "false")
        self.assertIn("the tests of coverage, examplemod run", self.reason)

    def test_a_port_that_declares_a_loader_runs_it_with_the_selection(self):
        outputs = self.answer(self.select("push", PORT))
        self.assertEqual(outputs["mods"], "betaonly,coverage")
        # b1.7.3-ornithe is no family's representative, so without the declaration the port's tests
        # would never run on the loader it says they are for.
        self.assertEqual(outputs["build"]["b1.7.3-ornithe"], "betaonly,coverage")
        self.assertEqual(outputs["build"]["a1.1.2_01-ornithe"], "coverage")
        self.assertEqual(sorted(outputs["installed"]), sorted([*REPRESENTATIVES, "b1.7.3-ornithe"]))

    def test_a_core_change_runs_every_mod_on_the_representatives(self):
        outputs = self.answer(
            self.select("push", {"core/src/Main.java": "class Main { int x; }\n"})
        )
        self.assertEqual(outputs["mods"], "")
        # An empty mod list is every mod, so the representatives run the whole suite.
        self.assertEqual(
            outputs["build"],
            {
                "1.2.5-forge": "",
                "1.20.6-forge": "",
                "a1.1.2_01-ornithe": "coverage",
                "b1.7.3-ornithe": "coverage",
                "b1.7.3-stationapi": "",
            },
        )
        self.assertEqual(outputs["installed"], {loader: "" for loader in REPRESENTATIVES})
        self.assertEqual(outputs["smoke"], "true")
        self.assertEqual(outputs["reference"], "true")

    def test_a_loader_only_change_runs_every_mod_on_its_family(self):
        outputs = self.answer(
            self.select(
                "push", {"loaders/1.20.6-forge/src/main/java/Forge.java": "class F { int x; }\n"}
            )
        )
        # A loaders/ path is outside every mod, so the mod axis is every mod; the loader axis is the
        # representatives, which this loader already is.
        self.assertEqual(outputs["mods"], "")
        self.assertEqual(
            outputs["build"],
            {
                "1.2.5-forge": "",
                "1.20.6-forge": "",
                "a1.1.2_01-ornithe": "coverage",
                "b1.7.3-ornithe": "coverage",
                "b1.7.3-stationapi": "",
            },
        )
        self.assertEqual(outputs["smoke"], "true")

    def test_a_change_confined_to_a_representative_runs_the_representatives(self):
        # The loader axis does not add a loader nothing in the diff reaches, so the two Ornithe
        # loaders, which no representative of their family is, run the gate's mod alone.
        outputs = self.answer(
            self.select(
                "push",
                {"loaders/b1.7.3-stationapi/src/main/java/Station.java": "class S { int x; }\n"},
            )
        )
        self.assertEqual(
            outputs["build"],
            {
                "1.2.5-forge": "",
                "1.20.6-forge": "",
                "a1.1.2_01-ornithe": "coverage",
                "b1.7.3-ornithe": "coverage",
                "b1.7.3-stationapi": "",
            },
        )

    def test_a_change_to_a_shared_directory_runs_the_loaders_that_compile_it(self):
        outputs = self.answer(
            self.select(
                "push",
                {"shared/ornithe/src/main/java/net/grug/Shared.java": "class S { int x; }\n"},
            )
        )
        # Both Ornithe loaders compile the shared directory, so both run the suite, and with the
        # representatives that is every loader.
        self.assertEqual(outputs["build"], {loader: "" for loader in ALL})
        self.assertEqual(sorted(outputs["installed"]), ALL)

    def test_a_script_change_runs_the_representatives(self):
        outputs = self.answer(
            self.select("push", {".github/scripts/run-loader.sh": "# run again\n"})
        )
        self.assertEqual(sorted(outputs["installed"]), REPRESENTATIVES)
        self.assertEqual(outputs["build"]["a1.1.2_01-ornithe"], "coverage")
        self.assertEqual(outputs["smoke"], "true")

    def test_a_workflow_change_runs_every_loader_with_every_mod_once(self):
        outputs = self.answer(
            self.select("push", {".github/workflows/build.yml": "name: Build\non: push\n"})
        )
        # No family's representative covers a job condition, so a workflow edit runs every loader.
        self.assertEqual(outputs["build"], {loader: "" for loader in ALL})
        self.assertEqual(outputs["installed"], {loader: "" for loader in ALL})
        self.assertEqual(outputs["smoke"], "true")
        self.assertEqual(outputs["reference"], "true")

    def test_an_action_change_runs_every_loader_too(self):
        # The setup action is the same machinery as the workflow that calls it: a change there can
        # reach every loader job, and only a full run says it still runs them.
        outputs = self.answer(
            self.select("push", {".github/actions/setup-loader-build/action.yml": "name: setup\n"})
        )
        self.assertEqual(outputs["build"], {loader: "" for loader in ALL})

    def test_the_default_reference_mod_running_is_what_keeps_its_job(self):
        # buildcraft's own harness is not in this diff, so only its loader could need the job.
        outputs = self.answer(
            self.select("push", {"loaders/1.2.5-forge/src/main/java/Forge125.java": "class F {}\n"})
        )
        self.assertEqual(outputs["reference"], "true")

    # What the selector does when it cannot narrow: everything runs.

    def test_an_empty_diff_runs_every_loader_with_every_mod(self):
        self.assertEqual(self.answer(self.select("push", {})), self.every())

    def test_a_base_that_cannot_be_read_runs_everything(self):
        outputs = self.select(
            "push", {"core/src/Main.java": "class Main { int y; }\n"}, base="0" * 40
        )
        self.assertEqual(self.answer(outputs), self.every())
        self.assertIn("cannot be read", self.reason)

    def test_a_push_with_no_base_runs_everything(self):
        outputs = self.select("push", {"core/src/Main.java": "class Main { int z; }\n"}, base="")
        self.assertEqual(self.answer(outputs), self.every())

    def test_a_mod_that_declares_a_loader_no_directory_provides_runs_on_the_representatives(self):
        outputs = self.answer(
            self.select(
                "push",
                {
                    "mods/stale/about.json": '{"loaders": ["b1.7.3-gone"]}',
                    "mods/stale/code/thing-Block.grug": "on_tick() {}\n",
                },
            )
        )
        # The name nothing provides cannot select a job, so the mod runs on every loader instead,
        # which here means the representatives' jobs.
        self.assertEqual(sorted(outputs["installed"]), REPRESENTATIVES)
        self.assertEqual(outputs["mods"], "coverage,stale")

    def test_a_head_tree_without_the_coverage_mod_runs_every_mod_everywhere(self):
        # The gate's own mod is what a loader narrowed out of the suite still runs, so without it
        # nothing can be narrowed: the loader axis then runs the selection on every loader.
        outputs = self.answer(
            self.select(
                "push",
                {
                    "mods/coverage/about.json": None,
                    "mods/coverage/code/frame-Block.grug": None,
                    "core/src/Main.java": "class Main { int w; }\n",
                },
            )
        )
        self.assertEqual(outputs["mods"], "")
        self.assertEqual(outputs["build"], {loader: "" for loader in ALL})


class PullRequestTest(SelectionCase):
    """A pull request keeps the matrix it has: only the loaders its diff reaches (#637)."""

    def test_a_mod_only_pull_request_runs_its_selected_loaders_only(self):
        outputs = self.answer(
            self.select(
                "pull_request", {"mods/examplemod/code/foo-Block.grug": "on_tick() { x() }\n"}
            )
        )
        # No coverage-only job: a pull request's 20-job budget is what #637 narrowed, and its gate is
        # whole through the representatives.
        self.assertEqual(
            outputs["build"], {loader: "coverage,examplemod" for loader in REPRESENTATIVES}
        )
        self.assertEqual(outputs["smoke"], "false")
        self.assertEqual(outputs["reference"], "false")

    def test_a_core_pull_request_runs_the_representatives_with_every_mod(self):
        outputs = self.answer(
            self.select("pull_request", {"core/src/Main.java": "class Main { int x; }\n"})
        )
        self.assertEqual(outputs["build"], {loader: "" for loader in REPRESENTATIVES})

    def test_a_pull_request_that_touches_the_reference_harness_runs_the_reference_job(self):
        outputs = self.answer(
            self.select(
                "pull_request",
                {"mods/buildcraft/tests/screen-Test.grug": 'tick() { Test.screenshot("x") }\n'},
            )
        )
        self.assertEqual(outputs["reference"], "true")

    def test_a_pull_request_that_touches_only_another_mod_skips_the_reference_job(self):
        outputs = self.answer(
            self.select("pull_request", {"mods/examplemod/code/foo-Block.grug": "# new\n"})
        )
        self.assertEqual(outputs["reference"], "false")

    def test_a_pull_request_that_cannot_be_diffed_runs_everything(self):
        outputs = self.select(
            "pull_request", {"core/src/Main.java": "class Main { int y; }\n"}, base="1" * 40
        )
        self.assertEqual(self.answer(outputs), self.every())


class FullRunTest(SelectionCase):
    """A dispatch and the weekly schedule select nothing away: every loader, every mod."""

    def test_the_weekly_schedule_runs_every_loader_with_every_mod(self):
        outputs = self.select("schedule", {"mods/examplemod/code/foo-Block.grug": "# new\n"})
        self.assertEqual(self.answer(outputs), self.every())

    def test_a_dispatch_runs_every_loader_with_every_mod(self):
        outputs = self.select(
            "workflow_dispatch", {"mods/examplemod/code/foo-Block.grug": "# new\n"}
        )
        self.assertEqual(self.answer(outputs), self.every())

    def test_an_event_the_script_does_not_know_runs_everything(self):
        outputs = self.select("repository_dispatch", {})
        self.assertEqual(self.answer(outputs), self.every())

    def test_the_coverage_only_dispatch_narrows_the_development_jobs_to_the_gate(self):
        outputs = self.answer(self.select("workflow_dispatch", {}, coverage_only=True))
        self.assertEqual(outputs["build"], {loader: "coverage" for loader in ALL})
        # Every other job runs as in a full run, which is what makes the dispatch a measurement of
        # the gate rather than of a smaller pipeline.
        self.assertEqual(outputs["installed"], {loader: "" for loader in ALL})
        self.assertEqual(outputs["mods"], "")
        self.assertEqual(outputs["smoke"], "true")
        self.assertEqual(outputs["reference"], "true")


class EverythingFlagTest(unittest.TestCase):
    """`--everything` is what a discover step that wrote no selection falls back to."""

    def test_the_fallback_is_the_full_run(self):
        tmp = tempfile.TemporaryDirectory()
        self.addCleanup(tmp.cleanup)
        repo = repo_module.Repo(Path(tmp.name))
        repo.commit(BASE, "base")
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "--everything", "--repo", str(repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        outputs = {
            key: value
            for key, _, value in (line.partition("=") for line in result.stdout.splitlines())
        }
        build = {entry["dir"]: entry["mods"] for entry in json.loads(outputs["build"])}
        self.assertEqual(build, {loader: "" for loader in ALL})
        self.assertEqual(outputs["mods"], "")
        self.assertEqual((outputs["smoke"], outputs["reference"]), ("true", "true"))


if __name__ == "__main__":
    unittest.main()
