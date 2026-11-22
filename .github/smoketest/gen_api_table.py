#!/usr/bin/env python3
"""Write api_table.inc, one stub and one row per entry of mod_api.json, for smoketest.c (#1585).

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
    rows_out = []
    stubs = []
    for i, (class_name, name) in enumerate(rows(api)):
        cls = "NULL" if class_name is None else json.dumps(class_name)
        rows_out.append("{%s, %s, stub_%d}" % (cls, json.dumps(name), i))
        label = name if class_name is None else class_name + "." + name
        # Each unimplemented entry gets its own stub, so a failing test names the entry it needs.
        stubs.append(
            'static union grug_value stub_%d(ARGS) { UNUSED; return fail("the smoketest does not '
            'implement %s"); }' % (i, label)
        )
    # One output file, so a build never sees the rows without the stubs they name.
    with open(argv[2], "w", encoding="utf-8") as f:
        f.write("\n".join(stubs) + "\n#define API_ROWS \\\n")
        f.write(" \\\n".join("    " + r + "," for r in rows_out) + "\n")


if __name__ == "__main__":
    main(sys.argv)
