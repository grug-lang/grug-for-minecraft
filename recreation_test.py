#!/usr/bin/env python3
"""Tests the recreation artifact handling, which decides whether the reference tier can trust bytes.

The hash check is the whole point of the artifact block, so it is worth a test that it actually
notices a changed size and a changed byte, rather than only that it is happy with a matching file.
"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import recreation

PAYLOAD = b"a small stand-in for a reference jar"


def recreation_block(**overrides) -> dict:
    block = {
        "fidelity": "behavioral",
        "reference": {
            "name": "Reference",
            "repository": {"url": "https://example.com/reference"},
            "revision": "0" * 40,
        },
        "artifact": {
            "url": "https://example.com/reference.jar",
            "sha256": hashlib.sha256(PAYLOAD).hexdigest(),
            "size": len(PAYLOAD),
            "redistribution": {"allowed": False},
        },
        "runtime": {
            "minecraft": "b1.7.3",
            "loader": "stationapi",
            "loader_version": "1.0.0",
            "java": "17",
        },
        "install": {"type": "mods_folder", "files": ["reference.jar"]},
        "capture": {
            "width": 1280,
            "height": 720,
            "ticks": 20,
            "diff": {"mode": "tolerance", "max_channel_delta": 8},
        },
    }
    block.update(overrides)
    return block


class RecreationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.artifact = Path(self.directory.name) / "reference.jar"
        self.artifact.write_bytes(PAYLOAD)

    def test_a_matching_artifact_verifies(self):
        self.assertEqual([], recreation.verify_artifact(recreation_block(), self.artifact))

    def test_a_changed_size_is_reported(self):
        block = recreation_block()
        block["artifact"]["size"] = len(PAYLOAD) + 1
        errors = recreation.verify_artifact(block, self.artifact)
        self.assertTrue(any("size is" in error for error in errors), errors)

    def test_a_changed_byte_is_reported(self):
        block = recreation_block()
        block["artifact"]["sha256"] = "a" * 64
        errors = recreation.verify_artifact(block, self.artifact)
        self.assertTrue(any("sha256 is" in error for error in errors), errors)

    def test_a_missing_artifact_is_reported(self):
        block = recreation_block()
        del block["artifact"]
        self.assertEqual(
            ["This recreation block has no artifact to verify."],
            recreation.verify_artifact(block, self.artifact),
        )

    def test_sha256_matches_hashlib(self):
        self.assertEqual(hashlib.sha256(PAYLOAD).hexdigest(), recreation.sha256_of(self.artifact))

    def test_load_recreation_reads_the_block(self):
        about = Path(self.directory.name) / "about.json"
        about.write_text(json.dumps({"recreation": recreation_block()}), encoding="utf-8")
        self.assertEqual(recreation_block(), recreation.load_recreation(about))

    def test_load_recreation_is_none_without_the_block(self):
        about = Path(self.directory.name) / "about.json"
        about.write_text(json.dumps({"name": "No recreation"}), encoding="utf-8")
        self.assertIsNone(recreation.load_recreation(about))

    def test_install_plan_names_the_runtime_and_the_files(self):
        plan = recreation.install_plan(recreation_block())
        self.assertIn("Minecraft b1.7.3", plan)
        self.assertIn("stationapi 1.0.0", plan)
        self.assertIn("Java 17", plan)
        self.assertIn("mods_folder", plan)
        self.assertIn("reference.jar", plan)
        self.assertIn("1280x720", plan)
        self.assertIn("tolerance", plan)

    def test_install_plan_mentions_deviations(self):
        block = recreation_block(deviations=[{"what": "w", "why": "y", "status": "open"}])
        self.assertIn("Intentional deviations: 1", recreation.install_plan(block))

    def test_install_plan_prints_config(self):
        block = recreation_block(
            install={"type": "launcher_profile", "config": {"name": "Reference"}}
        )
        self.assertIn('config: {"name": "Reference"}', recreation.install_plan(block))

    def test_stage_mods_folder_copies_the_artifact(self):
        destination = Path(self.directory.name) / "mods"
        created = recreation.stage_install(recreation_block(), self.artifact, destination)
        self.assertEqual([destination / "reference.jar"], created)
        self.assertEqual(PAYLOAD, (destination / "reference.jar").read_bytes())

    def test_stage_is_idempotent(self):
        destination = Path(self.directory.name) / "mods"
        recreation.stage_install(recreation_block(), self.artifact, destination)
        recreation.stage_install(recreation_block(), self.artifact, destination)
        self.assertEqual(PAYLOAD, (destination / "reference.jar").read_bytes())

    def test_stage_rejects_an_unautomated_install(self):
        block = recreation_block(install={"type": "installer", "files": ["setup.exe"]})
        with self.assertRaises(ValueError) as context:
            recreation.stage_install(block, self.artifact, Path(self.directory.name) / "staged")
        self.assertIn("installer", str(context.exception))

    def test_every_install_type_in_the_schema_has_a_summary(self):
        # The union lives in the schema, the summaries, and the plan. A type added to the schema
        # without a summary would only fail with a KeyError at plan time, so pin the two together.
        root = Path(recreation.__file__).resolve().parent
        schema = json.loads((root / "about_schema.json").read_text(encoding="utf-8"))
        schema_types = schema["definitions"]["install"]["properties"]["type"]["enum"]
        self.assertEqual(sorted(schema_types), sorted(recreation.INSTALL_SUMMARIES))


if __name__ == "__main__":
    unittest.main()
