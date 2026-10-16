"""Writes the redstone engine's models for the five facings other than up.

RenderEngine turns the engine's boxes about the block's centre by a quarter turn per orientation, so
each facing's model is the facing-up model with every element rotated the same way. A face moves to
the side it now points to, and its texture turns with it, which a face "rotation" of 0, 90, 180 or
270 degrees says. The parent holds the blue elements and the other trunk colours are children of it,
as they are for the facing-up model.

Run it from the repository root: python3 mods/buildcraft/generate_engine_facings.py
"""

import json
from pathlib import Path

MODELS = Path(__file__).parent / "assets/grug/models/block"

SIDES = ["down", "up", "north", "south", "west", "east"]
NORMALS = [(0, -1, 0), (0, 1, 0), (0, 0, -1), (0, 0, 1), (-1, 0, 0), (1, 0, 0)]

# GrugModels.CORNERS: which bound each corner of a face takes on x, y and z, 0 for the minimum.
CORNERS = [
    [0, 0, 1, 0, 0, 0, 1, 0, 0, 1, 0, 1],
    [0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0],
    [1, 1, 0, 1, 0, 0, 0, 0, 0, 0, 1, 0],
    [0, 1, 1, 0, 0, 1, 1, 0, 1, 1, 1, 1],
    [0, 1, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1],
    [1, 1, 1, 1, 0, 1, 1, 0, 0, 1, 1, 0],
]

# The facings, as RenderEngine turns them: an axis and a number of quarter turns, counter-clockwise
# seen from the positive end of the axis, so that the trunk, which points up, ends up pointing
# along the facing.
FACINGS = {
    "down": ("z", 2),
    "north": ("x", -1),
    "south": ("x", 1),
    "west": ("z", 1),
    "east": ("z", -1),
}
COLOURS = ["green", "yellow", "red"]


def turn(point, axis, quarters):
    """Turns a point about the block's centre, which is 8 on every axis."""
    x, y, z = (c - 8 for c in point)
    for _ in range(quarters % 4):
        if axis == "x":
            y, z = -z, y
        elif axis == "z":
            x, y = -y, x
    return (x + 8, y + 8, z + 8)


def corners(frm, to, side):
    row = CORNERS[side]
    return [tuple((frm, to)[row[c * 3 + a]][a] for a in range(3)) for c in range(4)]


def turn_element(element, axis, quarters):
    frm, to = element["from"], element["to"]
    ends = [turn(p, axis, quarters) for p in (tuple(frm), tuple(to))]
    new_from = [min(a, b) for a, b in zip(*ends)]
    new_to = [max(a, b) for a, b in zip(*ends)]
    faces = {}
    for name, face in element["faces"].items():
        side = SIDES.index(name)
        moved = turn(tuple(n + 8 for n in NORMALS[side]), axis, quarters)
        new_side = NORMALS.index(tuple(m - 8 for m in moved))
        old_corners = [turn(p, axis, quarters) for p in corners(frm, to, side)]
        new_corners = corners(new_from, new_to, new_side)
        # The baker gives corner j the texture coordinate of corner (j + k) % 4.
        shifts = {(old_corners.index(new_corners[j]) - j) % 4 for j in range(4)}
        assert len(shifts) == 1, (name, shifts)
        turned = dict(face)
        turned.pop("rotation", None)
        if shifts != {0}:
            turned["rotation"] = shifts.pop() * 90
        faces[SIDES[new_side]] = turned
    result = {k: v for k, v in element.items() if k != "rotation"}
    result["from"], result["to"] = new_from, new_to
    result["faces"] = {s: faces[s] for s in SIDES if s in faces}
    return result


def main():
    blue = json.loads((MODELS / "redstone_engine.json").read_text())
    for facing, (axis, quarters) in FACINGS.items():
        model = dict(blue)
        model["elements"] = [turn_element(e, axis, quarters) for e in blue["elements"]]
        write(f"redstone_engine_{facing}", model)
        for colour in COLOURS:
            child = json.loads((MODELS / f"redstone_engine_{colour}.json").read_text())
            child["parent"] = f"grug:block/redstone_engine_{facing}"
            write(f"redstone_engine_{colour}_{facing}", child)


def dump(value, depth=0):
    """json.dumps with tabs, keeping lists and the objects inside a faces object on one line, as the
    facing-up model is written."""
    pad = "\t" * depth
    if isinstance(value, dict) and depth >= 4 or not isinstance(value, dict | list):
        return json.dumps(value)
    if isinstance(value, list) and not any(isinstance(v, dict) for v in value):
        return json.dumps(value)
    inner = "\t" * (depth + 1)
    if isinstance(value, list):
        items = [inner + dump(v, depth + 1) for v in value]
        return "[\n" + ",\n".join(items) + "\n" + pad + "]"
    items = [f"{inner}{json.dumps(k)}: {dump(v, depth + 1)}" for k, v in value.items()]
    return "{\n" + ",\n".join(items) + "\n" + pad + "}"


def write(name, model):
    (MODELS / f"{name}.json").write_text(dump(model) + "\n")


if __name__ == "__main__":
    main()
