#!/usr/bin/env python3
"""Write recipes.inc, one row per mod crafting recipe under mods/*/data/*/recipes/, for smoketest.c.

    gen_recipes.py <mods dir> <out.inc>

A row is ``{shaped, width, height, "result", count, {cells}}``. A shaped recipe keeps its pattern, a
blank cell being "" (a cell the grid must leave empty); a shapeless one lists its ingredients as a
row. A tag ingredient is written as "#<tag>", which names no item, so a recipe that uses one never
matches: the smoketest has no tag tables. An unknown recipe type or a pattern wider or taller than
the 3x3 grid is an error, so a recipe the host cannot match is not dropped silently.
"""

import json
import sys
from pathlib import Path


def cell(path, ingredient):
    if "item" in ingredient:
        return ingredient["item"]
    if "tag" in ingredient:
        return "#" + ingredient["tag"]
    raise SystemExit(
        "%s: the ingredient %s is neither an item nor a tag" % (path, json.dumps(ingredient))
    )


def row(path, recipe):
    kind = recipe["type"]
    result = recipe["result"]
    name = result.get("id", result.get("item"))
    count = result.get("count", 1)
    if kind == "minecraft:crafting_shaped":
        pattern = recipe["pattern"]
        width, height = max(len(line) for line in pattern), len(pattern)
        cells = [
            cell(path, recipe["key"][ch]) if ch != " " else ""
            for line in pattern
            for ch in line.ljust(width)
        ]
        shaped = 1
    elif kind == "minecraft:crafting_shapeless":
        cells = [cell(path, i) for i in recipe["ingredients"]]
        width, height, shaped = len(cells), 1, 0
    else:
        raise SystemExit("%s: the recipe type %s is not modelled" % (path, kind))
    if shaped and (width > 3 or height > 3) or len(cells) > 9:
        raise SystemExit("%s: the recipe does not fit a 3x3 grid" % path)
    return "{%d, %d, %d, %s, %d, {%s}}" % (
        shaped,
        width,
        height,
        json.dumps(name),
        count,
        ", ".join(json.dumps(c) for c in cells),
    )


def main(argv):
    rows = []
    for path in sorted(Path(argv[1]).glob("*/data/*/recipes/**/*.json")):
        rows.append(row(path, json.loads(path.read_text(encoding="utf-8"))))
    body = ",\n    ".join(rows)
    Path(argv[2]).write_text(
        "static const struct recipe mod_recipes[] = {\n    %s,\n};\n" % body, encoding="utf-8"
    )


if __name__ == "__main__":
    main(sys.argv)
