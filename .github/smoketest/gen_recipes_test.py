#!/usr/bin/env python3
"""Tests gen_recipes.py: shaped and shapeless rows, tags, and the recipes it refuses."""

import importlib.util
import json
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "gen_recipes", Path(__file__).resolve().parent / "gen_recipes.py"
)
gr = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(gr)

SHAPED = {
    "type": "minecraft:crafting_shaped",
    "pattern": ["a ", " b"],
    "key": {"a": {"item": "minecraft:stick"}, "b": {"tag": "c:gears"}},
    "result": {"id": "grug:out", "count": 3},
}


class GenRecipes(unittest.TestCase):
    def test_shaped_keeps_blanks_and_marks_tags(self):
        self.assertEqual(
            gr.row("x", SHAPED),
            '{1, 2, 2, "grug:out", 3, {"minecraft:stick", "", "", "#c:gears"}}',
        )

    def test_short_lines_are_padded_and_result_may_be_named_item(self):
        recipe = dict(SHAPED, pattern=["a", " b"], result={"item": "grug:o"})
        self.assertEqual(
            gr.row("x", recipe),
            '{1, 2, 2, "grug:o", 1, {"minecraft:stick", "", "", "#c:gears"}}',
        )

    def test_shapeless_lists_its_ingredients(self):
        recipe = {
            "type": "minecraft:crafting_shapeless",
            "ingredients": [{"item": "a:b"}, {"item": "a:c"}],
            "result": {"id": "grug:o"},
        }
        self.assertEqual(gr.row("x", recipe), '{0, 2, 1, "grug:o", 1, {"a:b", "a:c"}}')

    def test_refuses_an_unknown_type(self):
        with self.assertRaises(SystemExit):
            gr.row("x", dict(SHAPED, type="minecraft:smelting"))

    def test_refuses_a_pattern_past_the_grid(self):
        with self.assertRaises(SystemExit):
            gr.row("x", dict(SHAPED, pattern=["abab"]))

    def test_main_writes_every_mod_recipe(self):
        with tempfile.TemporaryDirectory() as d:
            recipes = Path(d, "m", "data", "grug", "recipes", "crafting")
            recipes.mkdir(parents=True)
            (recipes / "r.json").write_text(json.dumps(SHAPED))
            out = Path(d, "out.inc")
            gr.main(["gen_recipes.py", d, str(out)])
            self.assertIn('"grug:out"', out.read_text())


if __name__ == "__main__":
    unittest.main()
