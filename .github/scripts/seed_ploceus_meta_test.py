#!/usr/bin/env python3
"""Tests .github/scripts/seed-ploceus-meta.py.

Ploceus reads its metadata cache before asking the Ornithe meta only while the file is under a day
old, and a restored cache entry carries the archived times rather than the time of the restore. So
the seeder has two jobs that pull against each other: it must not rewrite an answer the restored
cache already holds, because that is what keeps a complete restored entry's bytes, and it must
leave the file looking fresh, or the build spends a request on the meta that the cache was there to
save (#1250). The script runs as a process of its own, the way the action runs it, against a
temporary fixture and cache directory.
"""

import json
import os
import shutil
import subprocess
import sys
import tempfile
import time
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "seed-ploceus-meta.py"

# Shaped like the real fixtures: a base version per OSL generation, a version per module for the
# game, and the game's library upgrades.
FIXTURE = {
    "osl-base-versions.json": {"gen2": {"0.20.3": {"core": "0.9.0", "blocks": "0.1.1"}}},
    "osl-versions.json": {"gen2": {"core": {"0.9.0": {"merged": "0.9.0+mcb1.7.3"}}}},
    "library-upgrades.json": {"gen2": ["org.slf4j:slf4j-api:2.0.17"]},
}

MINECRAFT = "b1.7.3"


class SeedPloceusMetaTest(unittest.TestCase):
    """Runs the seeder against a temporary fixture and a temporary fabric-loom cache."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.fixture = self.tmp / "loaders" / "fake-loader" / "ci" / "osl-cache"
        self.fixture.mkdir(parents=True)
        for name, content in FIXTURE.items():
            (self.fixture / name).write_text(json.dumps(content))
        self.loom = self.tmp / "fabric-loom"

    def run_seeder(self):
        return subprocess.run(
            [sys.executable, str(SCRIPT), str(self.fixture), str(self.loom), MINECRAFT],
            capture_output=True,
            text=True,
        )

    def destinations(self):
        return {
            "osl-base-versions.json": self.loom / "osl-base-versions.json",
            "osl-versions.json": self.loom / MINECRAFT / "osl-versions.json",
            "library-upgrades.json": self.loom / MINECRAFT / "library-upgrades.json",
        }

    def age(self, path, seconds):
        stamp = time.time() - seconds
        os.utime(path, (stamp, stamp))

    def test_an_empty_cache_gets_every_answer(self):
        result = self.run_seeder()

        self.assertEqual(result.returncode, 0, result.stderr)
        for name, destination in self.destinations().items():
            self.assertEqual(json.loads(destination.read_text()), FIXTURE[name], name)
            self.assertIn(f"Seeded {name} for {MINECRAFT}.", result.stdout)

    def test_a_complete_restored_cache_keeps_its_bytes_and_gets_a_fresh_mtime(self):
        # The restored entry is what a cache restore leaves: the archived times, days old, and the
        # answers already there. Rewriting any of them would change bytes that need not change.
        for name, destination in self.destinations().items():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(json.dumps(FIXTURE[name], indent=4))
            self.age(destination, 2 * 24 * 60 * 60)
        before = {name: path.read_bytes() for name, path in self.destinations().items()}

        result = self.run_seeder()

        self.assertEqual(result.returncode, 0, result.stderr)
        for name, destination in self.destinations().items():
            self.assertIn(f"Kept the restored {name} for {MINECRAFT}.", result.stdout)
            self.assertEqual(destination.read_bytes(), before[name], name)
            # Ploceus asks the meta first once the file is a day old, so a kept file has to be newer
            # than that, which a restored entry is not.
            self.assertLess(time.time() - destination.stat().st_mtime, 60, name)

    def test_an_answer_the_restored_cache_holds_survives_and_a_gap_is_filled(self):
        destination = self.loom / "osl-base-versions.json"
        destination.parent.mkdir(parents=True)
        destination.write_text(json.dumps({"gen2": {"0.20.3": {"core": "0.8.0"}}}))
        older = self.loom / MINECRAFT / "osl-versions.json"
        older.parent.mkdir(parents=True)
        older.write_text("{}")

        result = self.run_seeder()

        self.assertEqual(result.returncode, 0, result.stderr)
        merged = json.loads(destination.read_text())
        self.assertEqual(merged["gen2"]["0.20.3"]["core"], "0.8.0")
        self.assertEqual(merged["gen2"]["0.20.3"]["blocks"], "0.1.1")
        self.assertEqual(json.loads(older.read_text()), FIXTURE["osl-versions.json"])

    def test_a_cache_file_that_is_not_json_is_seeded_rather_than_left_broken(self):
        destination = self.loom / "osl-base-versions.json"
        destination.parent.mkdir(parents=True)
        destination.write_text("{not json")

        result = self.run_seeder()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(json.loads(destination.read_text()), FIXTURE["osl-base-versions.json"])

    def test_a_missing_fixture_file_is_named(self):
        (self.fixture / "library-upgrades.json").unlink()

        result = self.run_seeder()

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("library-upgrades.json", result.stderr)

    def test_no_arguments_is_a_usage_error(self):
        result = subprocess.run([sys.executable, str(SCRIPT)], capture_output=True, text=True)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("usage:", result.stderr)


if __name__ == "__main__":
    unittest.main()
