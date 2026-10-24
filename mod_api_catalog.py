#!/usr/bin/env python3
"""Writes mod_api.md, a names-only catalog of mod_api.json, so grug can be written against it
without reading every description.

Each line is written the way it appears in a .grug file: an entity's export functions as ``export
name(...)``, a class's methods with a leading dot, its static methods with the type name, and a
return type after the parameters with no colon. Names are sorted within each group, so regenerating
only touches the lines that changed. Run by pre-commit, which fails when the committed file is stale.
"""

import json
import sys
from pathlib import Path

HEADER = (
    "Generated from mod_api.json by mod_api_catalog.py; do not edit."
    " Descriptions are in mod_api.json."
)


def type_name(type_: dict) -> str:
    """A type as grug writes it: ``number``, ``$Type`` or ``Option[BlockEntity]``."""
    generics = type_.get("generics")
    if not generics:
        return type_["name"]
    return f"{type_['name']}[{', '.join(type_name(generic) for generic in generics)}]"


def signature(name: str, function: dict, prefix: str = "") -> str:
    """``prefix name(parameter: type, ...) return_type``, with no return type when there is none."""
    parameters = ", ".join(
        f"{parameter['name']}: {type_name(parameter['type'])}"
        for parameter in function.get("parameters", [])
    )
    line = f"{prefix}{name}({parameters})"
    if "return_type" in function:
        line += f" {type_name(function['return_type'])}"
    return line


def sorted_lines(functions: dict, prefix: str) -> list:
    return [signature(name, functions[name], prefix) for name in sorted(functions)]


def entity_lines(entity: dict, name: str) -> list:
    exports = {function["name"]: function for function in entity.get("export_functions", [])}
    return (
        sorted_lines(exports, "export ")
        + sorted_lines(entity.get("methods", {}), ".")
        + sorted_lines(entity.get("static_methods", {}), f"{name}.")
    )


def class_lines(class_: dict, name: str) -> list:
    return sorted_lines(class_.get("methods", {}), ".") + sorted_lines(
        class_.get("static_methods", {}), f"{name}."
    )


def catalog(mod_api: dict) -> str:
    lines = [HEADER, "", "# Entities"]
    for name in sorted(mod_api["entities"]):
        lines += ["", f"## {name}", *entity_lines(mod_api["entities"][name], name)]
    lines += ["", "# Classes"]
    for name in sorted(mod_api["classes"]):
        lines += ["", f"## {name}", *class_lines(mod_api["classes"][name], name)]
    lines += ["", "# Host functions", ""]
    lines += sorted_lines(mod_api["host_functions"], "")
    return "\n".join(lines) + "\n"


def main() -> int:
    root = Path(__file__).resolve().parent
    mod_api = json.loads((root / "mod_api.json").read_text(encoding="utf-8"))
    (root / "mod_api.md").write_text(catalog(mod_api), encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
