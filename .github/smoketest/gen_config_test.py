#!/usr/bin/env python3
"""Tests gen_config.py: the option and key rows it writes, and the declarations it refuses."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "gen_config", Path(__file__).resolve().parent / "gen_config.py"
)
gc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gc)


def about(config=None, keys=None):
    data = {"name": "m"}
    if config is not None:
        data["config"] = config
    if keys is not None:
        data["keys"] = keys
    return data


class GenConfig(unittest.TestCase):
    def test_a_default_is_written_as_the_file_writes_it(self):
        self.assertEqual(
            gc.default_text({"key": "a", "type": "boolean", "default": True}, "x"), "true"
        )
        self.assertEqual(gc.default_text({"key": "b", "type": "number", "default": 16}, "x"), "16")
        self.assertEqual(
            gc.default_text({"key": "c", "type": "number", "default": 0.125}, "x"), "0.125"
        )
        self.assertEqual(
            gc.default_text({"key": "d", "type": "color", "default": "#DDDD00"}, "x"), "#DDDD00"
        )

    def test_refuses_a_missing_default(self):
        with self.assertRaises(SystemExit):
            gc.default_text({"key": "a", "type": "boolean"}, "some/about.json")

    def test_refuses_a_value_of_the_wrong_type(self):
        with self.assertRaises(SystemExit):
            gc.default_text({"key": "a", "type": "boolean", "default": "yes"}, "x")
        with self.assertRaises(SystemExit):
            gc.default_text({"key": "a", "type": "number", "default": True}, "x")

    def test_refuses_an_unknown_type(self):
        with self.assertRaises(SystemExit) as caught:
            gc.default_text({"key": "a", "type": "float", "default": 1}, "x")
        self.assertIn("float", str(caught.exception))

    def test_writes_every_option_and_binding(self):
        with tempfile.TemporaryDirectory() as d:
            mod = Path(d, "m")
            mod.mkdir()
            (mod / "about.json").write_text(
                json.dumps(
                    about(
                        config=[{"key": "on", "type": "boolean", "default": False}],
                        keys=[{"id": "toggle", "name": "Toggle", "default_key": "l"}],
                    )
                )
            )
            out = Path(d, "out.inc")
            gc.main(["gen_config.py", d, str(out)])
            text = out.read_text()
            self.assertIn('{"m", "on", "boolean", "false"}', text)
            self.assertIn('{"m", "toggle", "L"}', text)

    def test_refuses_a_key_with_no_one_character_default(self):
        with tempfile.TemporaryDirectory() as d:
            mod = Path(d, "m")
            mod.mkdir()
            (mod / "about.json").write_text(
                json.dumps(about(keys=[{"id": "toggle", "name": "Toggle", "default_key": "Esc"}]))
            )
            with self.assertRaises(SystemExit):
                gc.rows(Path(d))


if __name__ == "__main__":
    unittest.main()
