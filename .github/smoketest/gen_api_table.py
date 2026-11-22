#!/usr/bin/env python3
"""Write api_table.inc, one row per entry of mod_api.json, for smoketest.c (#1585).

    gen_api_table.py <mod_api.json> <out.inc>

A row is ``{class, name}``: class is NULL for a host function. Methods and static methods of both
classes and entities are rows, because grug-rs registers every one of them through
grug_register_host_method, which is what core/generate.py does for the real game.
"""

import json
import sys


def rows(api):
    for name in api.get("host_functions", {}):
        yield None, name
    for category in ("classes", "entities"):
        for class_name, decl in api.get(category, {}).items():
            for section in ("methods", "static_methods"):
                for method in decl.get(section, {}):
                    yield class_name, method


def main(argv):
    with open(argv[1], encoding="utf-8") as f:
        api = json.load(f)
    out = []
    for class_name, name in rows(api):
        cls = "NULL" if class_name is None else json.dumps(class_name)
        out.append("{%s, %s}," % (cls, json.dumps(name)))
    with open(argv[2], "w", encoding="utf-8") as f:
        f.write("\n".join(out) + "\n")


if __name__ == "__main__":
    main(sys.argv)
