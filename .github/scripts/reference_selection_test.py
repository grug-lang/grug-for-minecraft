#!/usr/bin/env python3
"""Tests reference-selection.py: the mod's first derived_from entry is the one the reference runs."""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "reference-selection.py"
SPEC = importlib.util.spec_from_file_location("reference_selection", SCRIPT)
reference = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(reference)

# affected_mods_test.py already has the throwaway repository the tests commit into.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

MOD = "refmod"
# The loaders the runtimes below may name, so the script's "is a real loaders/ directory" check
# has something to find.
LOADERS = {
    "loaders/1.20.6-forge/build.gradle": "// 1.20.6\n",
    "loaders/1.2.5-forge/build.gradle": "// 1.2.5\n",
}


def about(derived_from) -> str:
    """An about.json whose only job is to carry this derived_from array."""
    return json.dumps({"name": MOD, "derived_from": derived_from})


def entry(loader: str, java: str, name: str = "Ref", revision: str = "rev1") -> dict:
    return {
        "name": name,
        "revision": revision,
        "runtime": {
            "minecraft": "1.20.6" if loader == "1.20.6-forge" else "1.2.5",
            "loader": loader,
            "loader_version": "1",
            "java": java,
        },
    }


class ReferenceSelectionTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit({f"mods/{MOD}/about.json": about([]), **LOADERS}, "base")

    def select(self, about_json: str) -> subprocess.CompletedProcess:
        """The script's run over a repository where the mod's about.json holds about_json."""
        self.repo.commit({f"mods/{MOD}/about.json": about_json})
        return subprocess.run(
            [sys.executable, str(SCRIPT), MOD, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )

    def test_the_first_derived_from_entry_is_the_one_that_runs(self):
        # Two entries, each a full runtime; the first is what the reference runs, not the second.
        derived = [entry("1.20.6-forge", "21"), entry("1.2.5-forge", "8")]
        result = self.select(about(derived))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("loader=1.20.6-forge", result.stdout)
        self.assertIn("java=21", result.stdout)
        self.assertIn(f"mod={MOD}", result.stdout)

    def test_the_chosen_entry_is_said_in_the_log(self):
        derived = [entry("1.20.6-forge", "21", name="Ref", revision="rev1")]
        result = self.select(about(derived))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("first derived_from", result.stderr)
        self.assertIn("Ref at rev1", result.stderr)
        self.assertIn("1.20.6-forge", result.stderr)

    def test_a_mod_with_no_derived_from_fails_clearly(self):
        result = self.select(json.dumps({"name": MOD}))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no derived_from", result.stderr)

    def test_a_mod_with_an_empty_derived_from_fails_clearly(self):
        result = self.select(about([]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no derived_from", result.stderr)

    def test_a_first_entry_with_no_runtime_fails_clearly(self):
        result = self.select(about([{"name": "Ref"}]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no runtime", result.stderr)

    def test_a_runtime_naming_no_loader_fails_clearly(self):
        result = self.select(about([{"runtime": {"minecraft": "1.20.6", "java": "21"}}]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("no loader", result.stderr)

    def test_a_loader_the_repository_does_not_have_fails_clearly(self):
        derived = [entry("9.9-noloader", "21")]
        result = self.select(about(derived))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("9.9-noloader", result.stderr)

    def test_a_java_the_loaders_are_not_tested_on_fails_clearly(self):
        result = self.select(about([entry("1.20.6-forge", "11")]))
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("11", result.stderr)

    def test_a_missing_mod_fails_clearly(self):
        result = subprocess.run(
            [sys.executable, str(SCRIPT), "nomod", "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
        )
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("nomod", result.stderr)

    def test_an_unparseable_about_json_fails_clearly(self):
        result = self.select("{ not json")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("does not parse", result.stderr)


if __name__ == "__main__":
    unittest.main()
