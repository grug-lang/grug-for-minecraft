#!/usr/bin/env python3
"""Fails when the names in mod_api.json are not alphabetical, so a new entry has one place to go.

The dicts keyed by a name the mod author sees are checked: ``entities``, ``classes``,
``host_functions``, ``constraints``, and each entity's and class's ``methods`` and
``static_methods``. Lists keep their order because it means something (``export_functions``,
``parameters``), and so do the field names inside an entry (``description``, ``return_type``), so
neither is checked. Names sort by code point, like Python's ``sorted``. Run by pre-commit.
"""

import json
import sys
from pathlib import Path

TOP_LEVEL = ("constraints", "entities", "classes", "host_functions")
MEMBERS = ("static_methods", "methods")


def unsorted(path: str, names: dict) -> list[str]:
    """One message for the first name in ``names`` that is out of order."""
    keys = list(names)
    for above, key in zip(keys, keys[1:]):
        if key < above:
            return [f'{path}: "{key}" must come above "{above}"']
    return []


def violations(mod_api: dict) -> list[str]:
    messages = []
    for section in TOP_LEVEL:
        if section not in mod_api:
            continue
        messages += unsorted(section, mod_api[section])
        for name, entry in mod_api[section].items():
            if section in ("entities", "classes"):
                for member in MEMBERS:
                    if member in entry:
                        messages += unsorted(f"{section}.{name}.{member}", entry[member])
    return messages


def main() -> int:
    path = Path(__file__).resolve().parent / "mod_api.json"
    messages = violations(json.loads(path.read_text(encoding="utf-8")))
    for message in messages:
        print(f"{path.name}: {message}", file=sys.stderr)
    return 1 if messages else 0


if __name__ == "__main__":
    sys.exit(main())
