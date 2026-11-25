#!/usr/bin/env python3
"""Write mod_config.inc, one row per mod option and per mod key binding, for smoketest.c.

    gen_config.py <mods dir> <out.inc>

A mod declares its configuration options and its key bindings in its about.json, and the host owns
both: the options' file, defaults and ranges, and the binding's registration with the game. The
smoketest has no game and no config file, so it reads what each mod declares here, as text a C
literal holds, and serves a declared default where the real host would read the file. A field the
smoketest needs but a declaration cannot supply is an error, so a declaration the real host would
reject is not silently missing from the table.
"""

import json
import sys
from pathlib import Path

TYPES = ("boolean", "number", "color")


def default_text(option: dict, where: str) -> str:
    """The declared default as the config file writes it: what a read falls back to."""
    if "default" not in option:
        raise SystemExit("%s: the option %s has no default" % (where, option.get("key")))
    value = option["default"]
    kind = option.get("type")
    if kind == "boolean":
        if not isinstance(value, bool):
            raise SystemExit("%s: the boolean option %s is not a boolean" % (where, option["key"]))
        return "true" if value else "false"
    if kind == "number":
        if not isinstance(value, (int, float)) or isinstance(value, bool):
            raise SystemExit("%s: the number option %s is not a number" % (where, option["key"]))
        return str(value) if isinstance(value, int) else repr(value)
    if kind == "color":
        if not isinstance(value, str):
            raise SystemExit("%s: the colour option %s is not a text" % (where, option["key"]))
        return value
    raise SystemExit(
        "%s: the option %s has the type %r, not one of %s" % (where, option["key"], kind, TYPES)
    )


def rows(mods: Path):
    """Every declared option and key binding, sorted by mod and then by name."""
    options = []
    keys = []
    for about in sorted(Path(mods).glob("*/about.json")):
        where = str(about)
        data = json.loads(about.read_text(encoding="utf-8"))
        mod = about.parent.name
        for option in data.get("config", []):
            options.append((mod, option["key"], option["type"], default_text(option, where)))
        for key in data.get("keys", []):
            default = key.get("default_key")
            if not isinstance(default, str) or len(default) != 1:
                raise SystemExit(
                    "%s: the key %s has no one-character default_key" % (where, key.get("id"))
                )
            keys.append((mod, key["id"], default.upper()))
    return sorted(options), sorted(keys)


def c_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def main(argv):
    options, keys = rows(Path(argv[1]))
    lines = []
    lines.append(
        "struct mod_option { const char* mod; const char* key; const char* type; const char* def; };"
    )
    lines.append("static const struct mod_option mod_options[] = {")
    for mod, key, kind, default in options:
        lines.append(
            "    {%s, %s, %s, %s},"
            % (c_string(mod), c_string(key), c_string(kind), c_string(default))
        )
    # A C array cannot be empty, so a mods directory that declares nothing gets one row no loop
    # reads, because the count is what the loops walk.
    if not options:
        lines.append("    {0, 0, 0, 0},")
    lines.append("};")
    lines.append("static const size_t nmod_options = %d;" % len(options))
    lines.append("struct mod_key { const char* mod; const char* id; const char* def; };")
    lines.append("static const struct mod_key mod_keys[] = {")
    for mod, key, default in keys:
        lines.append("    {%s, %s, %s}," % (c_string(mod), c_string(key), c_string(default)))
    if not keys:
        lines.append("    {0, 0, 0},")
    lines.append("};")
    lines.append("static const size_t nmod_keys = %d;" % len(keys))
    Path(argv[2]).write_text("\n".join(lines) + "\n", encoding="utf-8")


if __name__ == "__main__":
    main(sys.argv)
