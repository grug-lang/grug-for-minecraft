#!/usr/bin/env python3
"""Tests the mod_api.json order check: a dict out of order at each level, and the committed file."""

import contextlib
import io
import json
import os
import sys
import unittest
from pathlib import Path

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

import mod_api_order

ROOT = Path(__file__).resolve().parent


def sorted_mod_api() -> dict:
    return {
        "entities": {
            "Alpha": {
                "methods": {"ask": {}, "tell": {}},
                "static_methods": {"build": {}, "count": {}},
                "export_functions": [{"name": "use"}, {"name": "init"}],
            },
            "Zed": {},
        },
        "classes": {"Box": {"methods": {"get": {}, "set": {}}}, "Level": {}},
        "host_functions": {"print": {}, "random": {}},
    }


class ModApiOrderTest(unittest.TestCase):
    def test_sorted_file_passes(self):
        self.assertEqual(mod_api_order.violations(sorted_mod_api()), [])

    def test_lists_and_field_names_are_not_checked(self):
        # export_functions is deliberately out of order above, and so are the field names below.
        mod_api = sorted_mod_api()
        mod_api["entities"]["Alpha"] = {"methods": {"ask": {"return_type": 1, "description": ""}}}
        self.assertEqual(mod_api_order.violations(mod_api), [])

    def test_missing_sections_are_skipped(self):
        self.assertEqual(mod_api_order.violations({}), [])

    def test_each_level_is_checked(self):
        cases = {
            "entities": lambda m: m.update(entities=dict(reversed(m["entities"].items()))),
            "classes": lambda m: m.update(classes=dict(reversed(m["classes"].items()))),
            "host_functions": lambda m: m.update(
                host_functions=dict(reversed(m["host_functions"].items()))
            ),
            "constraints": lambda m: m.update(constraints={"Show": {}, "Hash": {}}),
            "entities.Alpha.methods": lambda m: m["entities"]["Alpha"].update(
                methods={"tell": {}, "ask": {}}
            ),
            "entities.Alpha.static_methods": lambda m: m["entities"]["Alpha"].update(
                static_methods={"count": {}, "build": {}}
            ),
            "classes.Box.methods": lambda m: m["classes"]["Box"].update(
                methods={"set": {}, "get": {}}
            ),
        }
        for path, break_order in cases.items():
            with self.subTest(path):
                mod_api = sorted_mod_api()
                break_order(mod_api)
                messages = mod_api_order.violations(mod_api)
                self.assertEqual(len(messages), 1)
                self.assertTrue(messages[0].startswith(f"{path}: "), messages[0])

    def test_the_message_names_the_key_to_move(self):
        mod_api = sorted_mod_api()
        mod_api["host_functions"] = {"random": {}, "print": {}}
        self.assertEqual(
            mod_api_order.violations(mod_api),
            ['host_functions: "print" must come above "random"'],
        )

    def test_names_sort_by_code_point(self):
        # Uppercase before lowercase, as sorted() does, so "Zed" is above "alpha".
        mod_api = {"classes": {"Zed": {}, "alpha": {}}}
        self.assertEqual(mod_api_order.violations(mod_api), [])
        mod_api = {"classes": {"alpha": {}, "Zed": {}}}
        self.assertEqual(len(mod_api_order.violations(mod_api)), 1)

    def test_only_the_first_misplaced_name_in_a_dict_is_reported(self):
        mod_api = {"host_functions": {"c": {}, "b": {}, "a": {}}}
        self.assertEqual(len(mod_api_order.violations(mod_api)), 1)

    def test_committed_file_is_ordered(self):
        mod_api = json.loads((ROOT / "mod_api.json").read_text(encoding="utf-8"))
        self.assertEqual(mod_api_order.violations(mod_api), [])

    def test_main_fails_on_a_violation(self):
        real = mod_api_order.violations
        try:
            mod_api_order.violations = lambda _: ["x: out of order"]
            with contextlib.redirect_stderr(io.StringIO()) as stderr:
                self.assertEqual(mod_api_order.main(), 1)
            self.assertEqual(stderr.getvalue(), "mod_api.json: x: out of order\n")
        finally:
            mod_api_order.violations = real

    def test_main_passes_on_the_committed_file(self):
        self.assertEqual(mod_api_order.main(), 0)


if __name__ == "__main__":
    unittest.main()
