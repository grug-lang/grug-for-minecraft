#!/usr/bin/env python3
"""Tests the recreation artifact handling, which decides whether the reference tier can trust bytes.

The hash check is the whole point of the artifacts block, so it is worth a test that it actually
notices a changed size and a changed byte, rather than only that it is happy with matching files.
"""

import hashlib
import json
import tempfile
import unittest
from pathlib import Path

import recreation

PAYLOAD = b"a small stand-in for a reference jar"
OTHER_PAYLOAD = b"a second stand-in, for a reference that ships several files"


def artifact(name: str, payload: bytes) -> dict:
    return {
        "name": name,
        "url": f"https://example.com/{name}",
        "sha256": hashlib.sha256(payload).hexdigest(),
        "size": len(payload),
        "redistribution": {"allowed": False},
    }


def recreation_block(**overrides) -> dict:
    block = {
        "fidelity": "behavioral",
        "reference": {
            "name": "Reference",
            "revision": "0" * 40,
        },
        "artifacts": [artifact("reference.jar", PAYLOAD)],
        "runtime": {
            "minecraft": "b1.7.3",
            "loader": "b1.7.3-stationapi",
            "loader_version": "1.0.0",
            "java": "17",
        },
        "install": {"type": "mods_folder", "files": ["reference.jar"]},
    }
    block.update(overrides)
    return block


class RecreationTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.artifacts = Path(self.directory.name) / "artifacts"
        self.artifacts.mkdir()
        self.reference = self.artifacts / "reference.jar"
        self.reference.write_bytes(PAYLOAD)

    def test_a_matching_artifact_verifies(self):
        block = recreation_block()
        self.assertEqual([], recreation.verify_artifact(block["artifacts"][0], self.reference))

    def test_a_changed_size_is_reported(self):
        item = recreation_block()["artifacts"][0]
        item["size"] = len(PAYLOAD) + 1
        errors = recreation.verify_artifact(item, self.reference)
        self.assertTrue(any("size is" in error for error in errors), errors)

    def test_a_changed_byte_is_reported(self):
        item = recreation_block()["artifacts"][0]
        item["sha256"] = "a" * 64
        errors = recreation.verify_artifact(item, self.reference)
        self.assertTrue(any("sha256 is" in error for error in errors), errors)

    def test_sha256_matches_hashlib(self):
        self.assertEqual(hashlib.sha256(PAYLOAD).hexdigest(), recreation.sha256_of(self.reference))

    def test_artifact_named_finds_by_name(self):
        block = recreation_block(
            artifacts=[artifact("a.jar", PAYLOAD), artifact("b.jar", OTHER_PAYLOAD)]
        )
        self.assertEqual("b.jar", recreation.artifact_named(block, "b.jar")["name"])
        self.assertIsNone(recreation.artifact_named(block, "missing.jar"))

    def test_load_recreation_reads_the_block(self):
        about = Path(self.directory.name) / "about.json"
        about.write_text(json.dumps({"recreation": recreation_block()}), encoding="utf-8")
        self.assertEqual(recreation_block(), recreation.load_recreation(about))

    def test_load_recreation_is_none_without_the_block(self):
        about = Path(self.directory.name) / "about.json"
        about.write_text(json.dumps({"name": "No recreation"}), encoding="utf-8")
        self.assertIsNone(recreation.load_recreation(about))

    def test_recreation_mods_selects_only_mods_with_a_recreation_block(self):
        mods = Path(self.directory.name) / "mods"
        (mods / "with").mkdir(parents=True)
        (mods / "without").mkdir(parents=True)
        (mods / "stray.txt").write_text("not a mod", encoding="utf-8")
        about_with = mods / "with" / "about.json"
        about_with.write_text(json.dumps({"recreation": recreation_block()}), encoding="utf-8")
        (mods / "without" / "about.json").write_text(
            json.dumps({"name": "No recreation"}), encoding="utf-8"
        )

        selected = recreation.recreation_mods(mods)

        self.assertEqual([(about_with, recreation_block())], selected)
        # The artifacts of the selected mod are exactly the recreation block's own.
        self.assertEqual(
            ["reference.jar"],
            [item["name"] for item in recreation.artifacts_of(selected[0][1])],
        )

    def test_recreation_mods_is_empty_without_a_mods_directory(self):
        self.assertEqual([], recreation.recreation_mods(Path(self.directory.name) / "missing"))

    def test_install_plan_names_the_runtime_and_the_files(self):
        plan = recreation.install_plan(recreation_block())
        self.assertIn("Minecraft b1.7.3", plan)
        self.assertIn("loader directory b1.7.3-stationapi", plan)
        self.assertIn("loader version 1.0.0", plan)
        self.assertIn("Java 17", plan)
        self.assertIn("mods_folder", plan)
        self.assertIn("reference.jar", plan)

    def test_install_plan_lists_each_artifact_with_its_hash(self):
        block = recreation_block(
            artifacts=[artifact("a.jar", PAYLOAD), artifact("b.jar", OTHER_PAYLOAD)]
        )
        plan = recreation.install_plan(block)
        self.assertIn("artifact: a.jar https://example.com/a.jar sha256", plan)
        self.assertIn("artifact: b.jar https://example.com/b.jar sha256", plan)

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
        created = recreation.stage_install(recreation_block(), self.artifacts, destination)
        self.assertEqual([destination / "reference.jar"], created)
        self.assertEqual(PAYLOAD, (destination / "reference.jar").read_bytes())

    def test_stage_copies_several_artifacts(self):
        block = recreation_block(
            artifacts=[artifact("a.jar", PAYLOAD), artifact("b.jar", OTHER_PAYLOAD)],
            install={"type": "mods_folder", "files": ["a.jar", "b.jar"]},
        )
        (self.artifacts / "a.jar").write_bytes(PAYLOAD)
        (self.artifacts / "b.jar").write_bytes(OTHER_PAYLOAD)

        destination = Path(self.directory.name) / "mods"
        created = recreation.stage_install(block, self.artifacts, destination)

        self.assertEqual([destination / "a.jar", destination / "b.jar"], created)
        self.assertEqual(PAYLOAD, (destination / "a.jar").read_bytes())
        self.assertEqual(OTHER_PAYLOAD, (destination / "b.jar").read_bytes())

    def test_stage_detects_one_bad_file_among_several(self):
        block = recreation_block(
            artifacts=[artifact("a.jar", PAYLOAD), artifact("b.jar", OTHER_PAYLOAD)],
            install={"type": "mods_folder", "files": ["a.jar", "b.jar"]},
        )
        (self.artifacts / "a.jar").write_bytes(PAYLOAD)
        (self.artifacts / "b.jar").write_bytes(b"a corrupted second file")

        with self.assertRaises(ValueError) as context:
            recreation.stage_install(block, self.artifacts, Path(self.directory.name) / "mods")

        self.assertIn("b.jar", str(context.exception))
        self.assertIn("sha256", str(context.exception))

    def test_stage_rejects_an_install_file_without_an_artifact(self):
        block = recreation_block(install={"type": "mods_folder", "files": ["missing.jar"]})
        with self.assertRaises(ValueError) as context:
            recreation.stage_install(block, self.artifacts, Path(self.directory.name) / "mods")
        self.assertIn("names no artifact", str(context.exception))

    def test_stage_is_idempotent(self):
        destination = Path(self.directory.name) / "mods"
        recreation.stage_install(recreation_block(), self.artifacts, destination)
        recreation.stage_install(recreation_block(), self.artifacts, destination)
        self.assertEqual(PAYLOAD, (destination / "reference.jar").read_bytes())

    def test_stage_rejects_an_unautomated_install(self):
        block = recreation_block(install={"type": "installer", "files": ["setup.exe"]})
        with self.assertRaises(ValueError) as context:
            recreation.stage_install(block, self.artifacts, Path(self.directory.name) / "staged")
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
