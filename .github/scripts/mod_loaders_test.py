#!/usr/bin/env python3
"""Tests .github/scripts/mod-loaders.py: which mods' tests a loader runs, from their about.json.

The rule is a mod's top-level ``loaders`` (#910): a loader the set does not name never runs the
mod's tests, and an absent, unreadable or unparsable declaration runs it everywhere. The direction
matters more than the format: a declaration that cannot answer must never be read as "supports
nothing", because that would drop tests silently.
"""

import importlib.util
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "mod-loaders.py"
SPEC = importlib.util.spec_from_file_location("mod_loaders", SCRIPT)
mod_loaders = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(mod_loaders)

LOADER = "b1.7.3-stationapi"
OTHER = "1.20.6-forge"


class ModLoadersTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.mods = Path(self.tmp.name) / "mods"
        self.mods.mkdir()
        # The loaders a declaration can name, so a test does not depend on the repository's own.
        self.loaders = Path(self.tmp.name) / "loaders"
        for loader in (LOADER, OTHER):
            (self.loaders / loader).mkdir(parents=True)

    def add(self, name: str, about: dict | None = None, raw: str | None = None) -> None:
        directory = self.mods / name
        directory.mkdir(exist_ok=True)
        if raw is not None:
            (directory / "about.json").write_text(raw, encoding="utf-8")
        elif about is not None:
            (directory / "about.json").write_text(json.dumps(about), encoding="utf-8")

    def run_script(self, loader: str, *mods: str) -> subprocess.CompletedProcess:
        return subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                loader,
                "--mods-dir",
                str(self.mods),
                "--loaders-dir",
                str(self.loaders),
                "--",
                *mods,
            ],
            capture_output=True,
            text=True,
            check=False,
        )

    # The four answers the declaration can give.

    def test_a_declared_loader_runs_the_mod(self):
        self.add("mod", {"loaders": [LOADER, OTHER]})
        self.assertEqual(self.run_script(LOADER, "mod").stdout, "mod\n")

    def test_a_loader_the_mod_does_not_declare_runs_no_test_of_it(self):
        self.add("mod", {"loaders": [OTHER]})
        result = self.run_script(LOADER, "mod")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout, "\n")
        self.assertIn(f"mod declares {OTHER}, which is not {LOADER}", result.stderr)
        self.assertIn("runs the tests of no mod (0 of 1)", result.stderr)

    def test_an_absent_declaration_runs_the_mod_everywhere(self):
        self.add("mod", {})
        result = self.run_script(LOADER, "mod")

        self.assertEqual(result.stdout, "mod\n")
        # Silent when nothing is dropped, so a run without declarations keeps the log it had.
        self.assertEqual(result.stderr, "")

    def test_a_mod_without_an_about_json_runs_everywhere(self):
        self.add("mod")
        self.assertEqual(self.run_script(LOADER, "mod").stdout, "mod\n")

    def test_a_declaration_that_does_not_parse_runs_the_mod_everywhere(self):
        self.add("mod", raw="{not json")
        result = self.run_script(LOADER, "mod")

        self.assertEqual(result.stdout, "mod\n")
        self.assertIn("does not parse", result.stderr)

    def test_an_empty_or_odd_declaration_runs_the_mod_everywhere(self):
        # about_schema.py refuses both, and a hand-run file is not evidence for skipping tests.
        for about in ({"loaders": []}, {"loaders": "b1.7.3-stationapi"}, {"loaders": {}}):
            self.add("mod", about)
            self.assertEqual(self.run_script(LOADER, "mod").stdout, "mod\n", about)

    def test_a_non_string_entry_is_ignored_rather_than_naming_a_loader(self):
        self.add("mod", {"loaders": [LOADER, 7]})
        self.assertEqual(self.run_script(LOADER, "mod").stdout, "mod\n")

    def test_a_name_that_is_no_loader_directory_keeps_the_mod(self):
        # A removed or renamed loader leaves the name behind, and validate-about.yml does not run on
        # the pull request that removed the directory. Dropping the mod on every loader would pass a
        # later run with none of its tests, so a set naming something that cannot be run is not
        # trusted: the mod runs everywhere.
        self.add("mod", {"loaders": ["b1.7.3-gone"]})
        result = self.run_script(LOADER, "mod")

        self.assertEqual(result.stdout, "mod\n")
        self.assertIn(
            "mod declares b1.7.3-gone, which is no directory under loaders/", result.stderr
        )
        self.assertIn("runs on every loader", result.stderr)

    def test_one_unknown_name_makes_the_whole_set_untrusted(self):
        # The loader it does declare would be run, but the set is asking for a job nothing can
        # provide, so the answer is still to run the mod everywhere rather than partly trust it.
        self.add("mod", {"loaders": [OTHER, "b1.7.3-gone"]})
        result = self.run_script(LOADER, "mod")

        self.assertEqual(result.stdout, "mod\n")
        self.assertIn("b1.7.3-gone", result.stderr)

    def test_the_answer_carries_no_carriage_return(self):
        # Windows writes a line ending as \r\n, and run-loader.sh reads this line as a comma
        # separated list, so a stray \r would become part of the last mod's name.
        self.add("mod", {"loaders": [LOADER]})
        result = self.run_script(LOADER, "mod")

        self.assertNotIn("\r", result.stdout)
        self.assertEqual(result.stdout, "mod\n")

    # The list it prints.

    def test_the_mods_keep_the_order_they_were_given_in(self):
        for name in ("a", "b", "c"):
            self.add(name, {"loaders": [LOADER]})
        self.assertEqual(self.run_script(LOADER, "c", "a", "b").stdout, "c,a,b\n")

    def test_the_dropped_mods_are_named_in_the_log(self):
        self.add("kept", {"loaders": [LOADER]})
        self.add("dropped", {"loaders": [OTHER]})
        result = self.run_script(LOADER, "kept", "dropped")

        self.assertEqual(result.stdout, "kept\n")
        self.assertIn("1 of 2", result.stderr)

    def test_no_mod_prints_an_empty_line(self):
        result = self.run_script(LOADER)
        self.assertEqual(result.stdout, "\n")
        self.assertEqual(result.stderr, "")


if __name__ == "__main__":
    unittest.main()
