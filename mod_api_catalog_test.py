#!/usr/bin/env python3
"""Tests the mod_api.md generator: every kind of entry, the generics, and the committed file."""

import json
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mod_api_catalog

ROOT = Path(__file__).resolve().parent


def number(name: str) -> dict:
    return {"name": name, "type": {"name": "number"}}


MOD_API = {
    "entities": {
        "Zed": {"export_functions": [{"name": "tick", "engine_called": True}]},
        "Alpha": {
            "export_functions": [
                {"name": "use", "parameters": [number("x")]},
                {"name": "init"},
            ],
            "methods": {"level": {"return_type": {"name": "Level"}}, "ask": {}},
            "static_methods": {
                "make": {"return_type": {"name": "Alpha"}},
                "assert": {"parameters": [number("n")]},
            },
        },
    },
    "classes": {
        "Option": {
            "methods": {
                "unwrap": {"return_type": {"name": "$Value"}},
                "set": {"parameters": [{"name": "value", "type": {"name": "$Value"}}]},
            },
            "static_methods": {
                "new": {
                    "return_type": {"name": "Option", "generics": [{"name": "$Value"}]},
                }
            },
        },
        "Color": {
            "static_methods": {
                "rgb": {
                    "parameters": [number("r"), number("g")],
                    "return_type": {"name": "Color"},
                }
            }
        },
    },
    "host_functions": {
        "print": {"parameters": [{"name": "message", "type": {"name": "string"}}]},
        "equals": {
            "parameters": [
                {"name": "a", "type": {"name": "$Type"}},
                {"name": "b", "type": {"name": "$Type"}},
            ],
            "return_type": {"name": "bool"},
        },
        "find": {
            "parameters": [],
            "return_type": {"name": "Option", "generics": [{"name": "BlockEntity"}]},
        },
    },
}


class CatalogTest(unittest.TestCase):
    def setUp(self):
        self.lines = mod_api_catalog.catalog(MOD_API).split("\n")

    def section(self, heading: str) -> list:
        start = self.lines.index(heading) + 1
        end = start
        while end < len(self.lines) and self.lines[end] != "":
            end += 1
        return self.lines[start:end]

    def test_the_file_starts_with_the_generated_notice(self):
        self.assertEqual(mod_api_catalog.HEADER, self.lines[0])
        self.assertEqual("", self.lines[1])

    def test_the_file_ends_with_one_newline(self):
        self.assertTrue(mod_api_catalog.catalog(MOD_API).endswith("\n"))
        self.assertFalse(mod_api_catalog.catalog(MOD_API).endswith("\n\n"))

    def test_entities_and_classes_are_sorted(self):
        self.assertLess(self.lines.index("## Alpha"), self.lines.index("## Zed"))
        self.assertLess(self.lines.index("## Color"), self.lines.index("## Option"))

    def test_export_functions_are_written_as_grug_defines_them(self):
        self.assertEqual(
            [
                "export init()",
                "export use(x: number)",
                ".ask()",
                ".level() Level",
                "Alpha.assert(n: number)",
                "Alpha.make() Alpha",
            ],
            self.section("## Alpha"),
        )

    def test_an_entity_method_starts_with_a_dot_like_a_class_method(self):
        self.assertIn(".level() Level", self.section("## Alpha"))

    def test_an_engine_called_export_has_no_flag(self):
        self.assertEqual(["export tick()"], self.section("## Zed"))

    def test_methods_start_with_a_dot_and_static_methods_with_the_type(self):
        self.assertEqual(
            [
                ".set(value: $Value)",
                ".unwrap() $Value",
                "Option.new() Option[$Value]",
            ],
            self.section("## Option"),
        )
        self.assertEqual(["Color.rgb(r: number, g: number) Color"], self.section("## Color"))

    def test_host_functions_keep_generics_and_have_no_return_type_when_they_return_nothing(self):
        self.assertEqual(
            [
                "equals(a: $Type, b: $Type) bool",
                "find() Option[BlockEntity]",
                "print(message: string)",
            ],
            self.lines[self.lines.index("# Host functions") + 2 : -1],
        )

    def test_a_nested_generic_is_written_inside_its_parent(self):
        nested = {"name": "Option", "generics": [{"name": "Option", "generics": [{"name": "A"}]}]}
        self.assertEqual("Option[Option[A]]", mod_api_catalog.type_name(nested))

    def test_the_committed_catalog_is_current(self):
        mod_api = json.loads((ROOT / "mod_api.json").read_text(encoding="utf-8"))
        self.assertEqual(
            mod_api_catalog.catalog(mod_api),
            (ROOT / "mod_api.md").read_text(encoding="utf-8"),
        )


if __name__ == "__main__":
    unittest.main()
