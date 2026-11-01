#!/usr/bin/env python3
"""Tests the lineage entry's schema and the recreation rules jsonschema cannot express.

The recreation fields are what make a port diffable against the real mod it recreates, so a mistake
in them is a mistake in every recreation mod. They live on the first derived_from entry, the build
the port recreates; later entries are that build's own sources and may not carry them. The rule that
matters most here is the one the schema delegates to about_schema.py: only the first lineage entry
may carry the recreation fields.
"""

import json
import os
import sys
import tempfile
import unittest
from pathlib import Path

import jsonschema

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import about_schema

ROOT = Path(__file__).resolve().parent


def base_entry() -> dict:
    """A minimal lineage entry that recreates its reference."""
    return {
        "name": "Reference",
        "repository": {"url": "https://example.com/reference"},
        "revision": "0" * 40,
        "licenses": [{"name": "MIT", "url": "https://example.com/reference-license"}],
        "artifacts": [
            {
                "name": "reference.jar",
                "url": "https://example.com/reference.jar",
                "sha256": "a" * 64,
                "size": 10,
            }
        ],
        "runtime": {
            "minecraft": "b1.7.3",
            "loader": "b1.7.3-stationapi",
            "loader_version": "1.0.0",
            "java": "17",
        },
        "install": {"type": "mods_folder", "files": ["reference.jar"]},
    }


def base_mod() -> dict:
    """A minimal mod whose first lineage entry recreates its reference."""
    return {
        "name": "Test",
        "version": "1.0.0",
        "game_version": "b1.7.3",
        "authors": ["someone"],
        "repository": {"url": "https://example.com/mod"},
        "licenses": [{"name": "MIT", "url": "https://example.com/license"}],
        "derived_from": [base_entry()],
    }


class SchemaCase(unittest.TestCase):
    """The schema, plus the rules about_schema.py enforces over the parsed file."""

    @classmethod
    def setUpClass(cls):
        schema = json.loads((ROOT / "about_schema.json").read_text(encoding="utf-8"))
        validator_cls = jsonschema.validators.validator_for(schema)
        validator_cls.check_schema(schema)
        cls.validator = validator_cls(schema)

    def errors(self, data: dict, loaders_dir=None) -> list:
        messages = [error.message for error in self.validator.iter_errors(data)]
        messages += [
            message for _, message in about_schema.recreation_errors(data.get("derived_from"))
        ]
        messages += [
            message for _, message in about_schema.loaders_errors(data, loaders_dir=loaders_dir)
        ]
        return messages

    def assertValid(self, data: dict):
        self.assertEqual([], self.errors(data))

    def assertInvalid(self, data: dict, fragment: str):
        errors = self.errors(data)
        self.assertTrue(
            any(fragment in message for message in errors),
            f"expected {fragment!r} in {errors!r}",
        )


class RecreationSchemaTest(SchemaCase):
    def mod_with(self, entry: dict) -> dict:
        mod = base_mod()
        mod["derived_from"] = [entry]
        return mod

    def test_a_minimal_recreation_is_valid(self):
        self.assertValid(base_mod())

    def test_recreation_fields_are_optional(self):
        # A lineage entry that records where a port came from does not have to recreate it.
        mod = base_mod()
        for field in about_schema.RECREATION_FIELDS:
            mod["derived_from"][0].pop(field, None)
        self.assertValid(mod)

    def test_a_partial_recreation_is_rejected(self):
        entry = base_entry()
        del entry["runtime"]
        self.assertInvalid(self.mod_with(entry), "'runtime' is a required property")

    def test_a_revision_is_optional(self):
        entry = base_entry()
        del entry["revision"]
        self.assertValid(self.mod_with(entry))

    def test_only_the_first_entry_may_carry_recreation_fields(self):
        # Later entries are the first entry's own sources, so a recreation field on one of them is a
        # mistake about what the array records.
        mod = base_mod()
        mod["derived_from"].append(
            {
                "name": "Original",
                "repository": {"url": "https://example.com/original"},
                "licenses": [{"name": "MIT", "url": "https://example.com/original-license"}],
                "artifacts": base_entry()["artifacts"],
            }
        )
        self.assertEqual(
            ["/derived_from/1/artifacts"],
            [path for path, _ in about_schema.recreation_errors(mod["derived_from"])],
        )
        self.assertInvalid(mod, "only the first derived_from entry")

    def test_a_recreation_entry_needs_artifacts(self):
        entry = base_entry()
        del entry["artifacts"]
        self.assertInvalid(self.mod_with(entry), "'artifacts' is a required property")

    def test_a_reference_that_is_the_game_needs_no_artifacts_or_install(self):
        # Vanilla Minecraft is the reference itself: there is no build of it to stage and nothing
        # to install, since the loader already runs it. runtime says which game it is, and the
        # deviations record what the port does differently, so the entry is valid without either.
        entry = base_entry()
        for field in ("artifacts", "install", "revision"):
            del entry[field]
        entry["deviations"] = [{"what": "x", "why": "y", "tracking_issue": 1}]
        self.assertValid(self.mod_with(entry))

    def test_an_entry_with_artifacts_needs_an_install(self):
        entry = base_entry()
        del entry["install"]
        self.assertInvalid(self.mod_with(entry), "'install' is a required property")

    def test_a_recreation_with_a_deviation_is_valid(self):
        entry = base_entry()
        entry["deviations"] = [{"what": "x", "why": "y", "tracking_issue": 1}]
        self.assertValid(self.mod_with(entry))

    def test_a_finished_port_has_no_deviations_key(self):
        # An empty list is refused rather than read as finished, so there is one way to say it.
        entry = base_entry()
        entry["deviations"] = []
        self.assertInvalid(self.mod_with(entry), "should be non-empty")

    def test_a_deviation_without_a_tracking_issue_is_rejected(self):
        entry = base_entry()
        entry["deviations"] = [{"what": "x", "why": "y"}]
        self.assertInvalid(self.mod_with(entry), "'tracking_issue' is a required property")

    def test_a_deviation_whose_tracking_issue_is_not_an_integer_is_rejected(self):
        entry = base_entry()
        entry["deviations"] = [{"what": "x", "why": "y", "tracking_issue": "#1"}]
        self.assertInvalid(self.mod_with(entry), "is not of type 'integer'")

    def test_a_deviation_whose_tracking_issue_is_zero_is_rejected(self):
        entry = base_entry()
        entry["deviations"] = [{"what": "x", "why": "y", "tracking_issue": 0}]
        self.assertInvalid(self.mod_with(entry), "less than the minimum of 1")

    def test_duplicate_artifact_names_are_rejected(self):
        entry = base_entry()
        entry["artifacts"].append(
            {
                "name": "reference.jar",
                "url": "https://example.com/other.jar",
                "sha256": "b" * 64,
                "size": 20,
            }
        )
        self.assertInvalid(self.mod_with(entry), "unique")

    def test_an_install_file_must_name_an_artifact(self):
        entry = base_entry()
        entry["install"]["files"] = ["missing.jar"]
        self.assertInvalid(self.mod_with(entry), "not the name of any artifact")

    def test_an_unknown_install_type_is_rejected(self):
        entry = base_entry()
        entry["install"] = {"type": "magic", "files": ["reference.jar"]}
        self.assertInvalid(self.mod_with(entry), "is not one of")

    def test_a_mods_folder_install_needs_files(self):
        entry = base_entry()
        entry["install"] = {"type": "mods_folder"}
        self.assertInvalid(self.mod_with(entry), "'files' is a required property")

    def test_a_launcher_profile_install_needs_config(self):
        entry = base_entry()
        entry["install"] = {"type": "launcher_profile"}
        self.assertInvalid(self.mod_with(entry), "'config' is a required property")

    def test_a_launcher_profile_install_with_config_is_valid(self):
        entry = base_entry()
        entry["install"] = {"type": "launcher_profile", "config": {"name": "Reference"}}
        self.assertValid(self.mod_with(entry))

    def test_a_malformed_sha256_is_rejected(self):
        entry = base_entry()
        entry["artifacts"][0]["sha256"] = "not-a-hash"
        self.assertInvalid(self.mod_with(entry), "does not match")

    def test_a_runtime_loader_must_be_a_loader_directory(self):
        entry = base_entry()
        entry["runtime"]["loader"] = "no-such-loader"
        self.assertInvalid(self.mod_with(entry), "is not a directory under loaders/")

    def test_a_runtime_minecraft_must_match_its_loader(self):
        entry = base_entry()
        entry["runtime"]["minecraft"] = "1.20.6"
        self.assertInvalid(self.mod_with(entry), "is for 'b1.7.3'")

    def test_every_known_loader_accepts_its_minecraft_version(self):
        for loader, minecraft in about_schema.LOADER_MINECRAFT.items():
            entry = base_entry()
            entry["runtime"]["loader"] = loader
            entry["runtime"]["minecraft"] = minecraft
            self.assertValid(self.mod_with(entry))

    def test_every_known_loader_rejects_another_minecraft_version(self):
        for loader in about_schema.LOADER_MINECRAFT:
            entry = base_entry()
            entry["runtime"]["loader"] = loader
            entry["runtime"]["minecraft"] = "0.0.0"
            self.assertInvalid(self.mod_with(entry), "is for")

    def test_a_runtime_java_must_be_supported(self):
        entry = base_entry()
        entry["runtime"]["java"] = "12"
        self.assertInvalid(self.mod_with(entry), "must be one of 8, 17, 21")

    def test_each_supported_java_version_is_valid(self):
        for java in ("8", "17", "21"):
            entry = base_entry()
            entry["runtime"]["java"] = java
            self.assertValid(self.mod_with(entry))

    def test_a_capture_is_rejected(self):
        # The test drives the capture: its tick branches decide when, the camera it sets up decides
        # from where, and the screenshot call decides what. A capture block would be metadata
        # nothing reads.
        entry = base_entry()
        entry["capture"] = {"width": 1280, "height": 720, "ticks": 20}
        self.assertInvalid(self.mod_with(entry), "Additional properties are not allowed")

    def test_an_unknown_recreation_property_is_rejected(self):
        entry = base_entry()
        entry["bogus"] = 1
        self.assertInvalid(self.mod_with(entry), "Additional properties are not allowed")


class LoadersTest(SchemaCase):
    """The top-level loader set: which loaders a mod's tests run on (#910)."""

    def test_a_declared_set_is_valid(self):
        # The reference's own loader has to be in it, but the other loaders are the port's choice.
        mod = base_mod()
        mod["loaders"] = ["b1.7.3-stationapi", "1.20.6-forge"]
        self.assertValid(mod)

    def test_an_absent_set_is_valid_and_means_every_loader(self):
        mod = base_mod()
        mod.pop("loaders", None)
        self.assertValid(mod)

    def test_a_mod_without_a_recreation_entry_may_declare_a_set(self):
        # coverage and examplemod recreate nothing, and a port that runs on a subset of the loaders
        # says so the same way whether or not it has a reference.
        mod = base_mod()
        del mod["derived_from"]
        mod["loaders"] = ["a1.1.2_01-ornithe", "b1.7.3-ornithe"]
        self.assertValid(mod)

    def test_a_declared_loader_must_be_a_loader_directory(self):
        mod = base_mod()
        mod["loaders"] = ["b1.7.3-stationapi", "no-such-loader"]
        self.assertInvalid(mod, "is not a directory under loaders/")

    def test_the_error_names_the_entry_that_is_wrong(self):
        mod = base_mod()
        mod["loaders"] = ["b1.7.3-stationapi", "no-such-loader"]
        paths = [
            path
            for path, message in about_schema.loaders_errors(mod)
            if "no-such-loader" in message
        ]
        self.assertEqual(["/loaders/1"], paths)

    def test_a_declared_loader_needs_a_minecraft_version(self):
        # Every directory under loaders/ is in LOADER_MINECRAFT today; a loader added without its
        # table row is what this refuses, so the case is driven with a directory of its own.
        with tempfile.TemporaryDirectory() as loaders_dir:
            Path(loaders_dir, "fake-loader").mkdir()
            mod = base_mod()
            mod["loaders"] = ["b1.7.3-stationapi", "fake-loader"]
            errors = self.errors(mod, loaders_dir=Path(loaders_dir))
        self.assertTrue(
            any("has no Minecraft version in LOADER_MINECRAFT" in error for error in errors), errors
        )

    def test_the_runtime_loader_must_be_in_the_declared_set(self):
        mod = base_mod()
        mod["loaders"] = ["1.20.6-forge"]
        self.assertInvalid(mod, "is not in the declared set")

    def test_an_empty_set_is_rejected(self):
        # An empty list would read as supporting no loader, which is what absence already says the
        # opposite of, so there is one way to say each.
        mod = base_mod()
        mod["loaders"] = []
        self.assertInvalid(mod, "should be non-empty")

    def test_a_repeated_loader_is_rejected(self):
        mod = base_mod()
        mod["loaders"] = ["b1.7.3-stationapi", "b1.7.3-stationapi"]
        self.assertInvalid(mod, "non-unique")

    def test_a_non_string_loader_is_rejected(self):
        mod = base_mod()
        mod["loaders"] = ["b1.7.3-stationapi", 1]
        self.assertInvalid(mod, "is not of type 'string'")


if __name__ == "__main__":
    unittest.main()
