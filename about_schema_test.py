#!/usr/bin/env python3
"""Tests the recreation block's schema and the rules that jsonschema cannot express.

The recreation block is what makes a port diffable against the real mod it recreates, so a mistake
in it is a mistake in every recreation mod. The rules that matter most here are the two the schema
delegates to about_schema.py: a mirror needs redistribution rights, and an exact recreation needs
artifacts to diff against.
"""

import copy
import json
import os
import sys
import unittest
from pathlib import Path

import jsonschema

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import about_schema

ROOT = Path(__file__).resolve().parent


def base_mod() -> dict:
    """A minimal mod that already satisfies everything outside recreation."""
    return {
        "name": "Test",
        "version": "1.0.0",
        "game_version": "b1.7.3",
        "authors": ["someone"],
        "repository": {"url": "https://example.com/mod"},
        "licenses": [{"name": "MIT", "url": "https://example.com/license"}],
    }


def base_recreation() -> dict:
    return {
        "fidelity": "behavioral",
        "reference": {
            "name": "Reference",
            "repository": {"url": "https://example.com/reference"},
            "revision": "0" * 40,
        },
        "artifacts": [
            {
                "name": "reference.jar",
                "url": "https://example.com/reference.jar",
                "sha256": "a" * 64,
                "size": 10,
                "redistribution": {"allowed": False},
            }
        ],
        "runtime": {
            "minecraft": "b1.7.3",
            "loader": "b1.7.3-stationapi",
            "loader_version": "1.0.0",
            "java": "17",
        },
        "install": {"type": "mods_folder", "files": ["reference.jar"]},
        "capture": {"width": 1280, "height": 720, "ticks": 20},
    }


class RecreationSchemaTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        schema = json.loads((ROOT / "about_schema.json").read_text(encoding="utf-8"))
        validator_cls = jsonschema.validators.validator_for(schema)
        validator_cls.check_schema(schema)
        cls.validator = validator_cls(schema)

    def errors(self, data: dict) -> list:
        messages = [error.message for error in self.validator.iter_errors(data)]
        messages += [message for _, message in about_schema.recreation_errors(data)]
        return messages

    def assertValid(self, data: dict):
        self.assertEqual([], self.errors(data))

    def assertInvalid(self, data: dict, fragment: str):
        errors = self.errors(data)
        self.assertTrue(
            any(fragment in message for message in errors),
            f"expected {fragment!r} in {errors!r}",
        )

    def mod_with(self, recreation: dict) -> dict:
        mod = base_mod()
        mod["recreation"] = recreation
        return mod

    def test_a_minimal_recreation_is_valid(self):
        self.assertValid(self.mod_with(base_recreation()))

    def test_recreation_is_optional(self):
        self.assertValid(base_mod())

    def test_an_exact_recreation_needs_an_artifact(self):
        recreation = base_recreation()
        recreation["fidelity"] = "exact"
        del recreation["artifacts"]
        self.assertInvalid(self.mod_with(recreation), "exact recreation needs artifacts")

    def test_an_exact_recreation_with_an_artifact_is_valid(self):
        recreation = base_recreation()
        recreation["fidelity"] = "exact"
        self.assertValid(self.mod_with(recreation))

    def test_a_mirror_without_redistribution_rights_is_rejected(self):
        recreation = base_recreation()
        recreation["artifacts"][0]["mirrors"] = ["https://mirror.example.com/reference.jar"]
        self.assertInvalid(self.mod_with(recreation), "mirror")

    def test_a_mirror_with_redistribution_rights_is_accepted(self):
        recreation = base_recreation()
        recreation["artifacts"][0]["redistribution"] = {"allowed": True, "basis": "MIT"}
        recreation["artifacts"][0]["mirrors"] = ["https://mirror.example.com/reference.jar"]
        self.assertValid(self.mod_with(recreation))

    def test_duplicate_artifact_names_are_rejected(self):
        recreation = base_recreation()
        recreation["artifacts"].append(
            {
                "name": "reference.jar",
                "url": "https://example.com/other.jar",
                "sha256": "b" * 64,
                "size": 20,
                "redistribution": {"allowed": False},
            }
        )
        self.assertInvalid(self.mod_with(recreation), "unique")

    def test_an_install_file_must_name_an_artifact(self):
        recreation = base_recreation()
        recreation["install"]["files"] = ["missing.jar"]
        self.assertInvalid(self.mod_with(recreation), "not the name of any artifact")

    def test_an_unknown_install_type_is_rejected(self):
        recreation = base_recreation()
        recreation["install"] = {"type": "magic", "files": ["reference.jar"]}
        self.assertInvalid(self.mod_with(recreation), "is not one of")

    def test_a_mods_folder_install_needs_files(self):
        recreation = base_recreation()
        recreation["install"] = {"type": "mods_folder"}
        self.assertInvalid(self.mod_with(recreation), "'files' is a required property")

    def test_a_launcher_profile_install_needs_config(self):
        recreation = base_recreation()
        recreation["install"] = {"type": "launcher_profile"}
        self.assertInvalid(self.mod_with(recreation), "'config' is a required property")

    def test_a_launcher_profile_install_with_config_is_valid(self):
        recreation = base_recreation()
        recreation["install"] = {"type": "launcher_profile", "config": {"name": "Reference"}}
        self.assertValid(self.mod_with(recreation))

    def test_a_malformed_sha256_is_rejected(self):
        recreation = base_recreation()
        recreation["artifacts"][0]["sha256"] = "not-a-hash"
        self.assertInvalid(self.mod_with(recreation), "does not match")

    def test_a_reference_needs_a_revision(self):
        recreation = base_recreation()
        del recreation["reference"]["revision"]
        self.assertInvalid(self.mod_with(recreation), "'revision' is a required property")

    def test_a_runtime_loader_must_be_a_loader_directory(self):
        recreation = base_recreation()
        recreation["runtime"]["loader"] = "no-such-loader"
        self.assertInvalid(self.mod_with(recreation), "is not a directory under loaders/")

    def test_a_runtime_minecraft_must_match_its_loader(self):
        recreation = base_recreation()
        recreation["runtime"]["minecraft"] = "1.20.6"
        self.assertInvalid(self.mod_with(recreation), "is for 'b1.7.3'")

    def test_every_known_loader_accepts_its_minecraft_version(self):
        for loader, minecraft in about_schema.LOADER_MINECRAFT.items():
            recreation = base_recreation()
            recreation["runtime"]["loader"] = loader
            recreation["runtime"]["minecraft"] = minecraft
            self.assertValid(self.mod_with(recreation))

    def test_every_known_loader_rejects_another_minecraft_version(self):
        for loader in about_schema.LOADER_MINECRAFT:
            recreation = base_recreation()
            recreation["runtime"]["loader"] = loader
            recreation["runtime"]["minecraft"] = "0.0.0"
            self.assertInvalid(self.mod_with(recreation), "is for")

    def test_a_runtime_java_must_be_supported(self):
        recreation = base_recreation()
        recreation["runtime"]["java"] = "12"
        self.assertInvalid(self.mod_with(recreation), "must be one of 8, 17, 21")

    def test_each_supported_java_version_is_valid(self):
        for java in ("8", "17", "21"):
            recreation = base_recreation()
            recreation["runtime"]["java"] = java
            self.assertValid(self.mod_with(recreation))

    def test_a_capture_diff_is_rejected(self):
        # The comparison is chosen by the test (.equals or .tolerance), so capture carries only the
        # viewport and timing. A diff left in the metadata would drift away from the call that runs.
        recreation = base_recreation()
        recreation["capture"]["diff"] = {"mode": "tolerance", "max_pixel_percent": 1.0}
        self.assertInvalid(self.mod_with(recreation), "Additional properties are not allowed")

    def test_an_unknown_recreation_property_is_rejected(self):
        recreation = copy.deepcopy(base_recreation())
        recreation["bogus"] = 1
        self.assertInvalid(self.mod_with(recreation), "Additional properties are not allowed")

    def test_an_unknown_capture_property_is_rejected(self):
        recreation = base_recreation()
        recreation["capture"]["bogus"] = 1
        self.assertInvalid(self.mod_with(recreation), "Additional properties are not allowed")


if __name__ == "__main__":
    unittest.main()
