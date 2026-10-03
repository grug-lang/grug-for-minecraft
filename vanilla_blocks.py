#!/usr/bin/env python3
"""The audit of vanilla block names across the loaders, and the check that keeps it honest.

Minecraft renamed blocks and spells them differently per version, so a mod that places a vanilla
block by name can get a different block on a different loader. The result of working out where that
happens is ``core/src/main/resources/vanilla_blocks.txt``: one row per block, the canonical name
mods write, and the spelling each loader uses for that same block. ``GrugVanillaBlocks`` reads it at
run time, so this file is not documentation but the thing that makes a name mean one block on all
five loaders.

Run with no arguments it checks that file, and that is what CI runs. A canonical name has to be a
``minecraft:`` name in lower case snake case, every loader under ``loaders/`` needs a column, a
canonical name cannot be listed twice, and no two rows may give one loader the same spelling:
``get_block`` could not then say which block a name means. A row that stops early is a failure
rather than a partial one, because a short row leaves a loader believing a block it does not have
is one it can place.

``--verify-blocks`` additionally cross-checks a loader's column against that loader's own block
names. That needs the game jars, so CI cannot do it, and it is how the table was produced and how a
Minecraft version bump gets re-audited. The dump it reads is one ``<block id> <name>`` line per
block, which is what each loader's own registry gives up: 1.2.5 and the Ornithe loaders name their
blocks by static field, and StationAPI names them in the byte code of its vanilla block
registration. Only one direction can be checked this way, since pairing a dump entry to a table row
needs the canonical name, which is the curated part of the table. So every spelling a loader's
column uses has to be a name that loader actually has, and the blocks a column does not mention are
counted and printed, for whoever is doing the re-audit to look at.
"""

import argparse
import re
import sys
from pathlib import Path

TABLE = Path("core/src/main/resources/vanilla_blocks.txt")

# A canonical name is the one modern Minecraft uses, so it is lower case snake case under the
# minecraft namespace. A loader's own column is not held to that: 1.2.5 spells its blocks as MCP
# field names, because that is what its obfuscated runtime resolves.
CANONICAL_NAME = re.compile(r"^minecraft:[a-z0-9_]+$")

# One column's worth of dashes: no loader in the repository has the block. Only used to keep a
# hand-written test table readable.
NO_BLOCK = "-"


class Table:
    """A parsed vanilla_blocks.txt: the loader ids, and the rows keyed by canonical name."""

    def __init__(self, loaders: list, rows: list):
        self.loaders = loaders
        self.rows = rows

    def canonical_names(self) -> list:
        return [canonical for canonical, _row in self.rows]

    def spellings(self, loader_id: str) -> dict:
        """Canonical name to this loader's spelling, without the blocks it does not have."""
        column = self.loaders.index(loader_id)
        return {canonical: row[column] for canonical, row in self.rows if row[column] != NO_BLOCK}

    def blocks_on(self, loader_ids: list) -> list:
        """The rows whose block at least one of these loaders has."""
        columns = [self.loaders.index(loader_id) for loader_id in loader_ids]
        return [
            (canonical, row)
            for canonical, row in self.rows
            if any(row[column] != NO_BLOCK for column in columns)
        ]


def loaders_in_repository(loaders_dir: Path) -> list:
    """Every loader directory, sorted, which is the set the table's columns have to cover."""
    return sorted(path.name for path in loaders_dir.iterdir() if path.is_dir())


def parse(text: str, loaders: list, table_path: Path = TABLE) -> Table:
    """The rows, or the complaint that stopped the file being read.

    The column count comes from the repository rather than from the file, so a new loader cannot be
    added without a column to fill, and a file with the wrong width is a failure rather than a
    table quietly covering fewer loaders than it claims.
    """
    rows = []
    for number, raw in enumerate(text.splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        columns = line.split()
        if len(columns) != len(loaders) + 1:
            raise ValueError(
                f"{table_path}:{number}: {len(columns)} columns, expected {len(loaders) + 1}"
                " (a canonical name and one per loader:"
                f" {', '.join(loaders)})"
            )
        rows.append((columns[0], columns[1:]))
    if not rows:
        raise ValueError(f"{table_path}: it has no rows")
    return Table(loaders, rows)


def table_errors(table: Table, table_path: Path = TABLE) -> list:
    """What is wrong with the table, as (canonical name or path, message) pairs."""
    errors = []

    seen = set()
    for canonical, row in table.rows:
        if canonical in seen:
            errors.append((canonical, "it is listed more than once"))
        seen.add(canonical)
        if not CANONICAL_NAME.match(canonical):
            errors.append((canonical, "a canonical name is minecraft: then lower case snake case"))
        if all(spelling == NO_BLOCK for spelling in row):
            errors.append((canonical, "no loader has it, so a mod could never place it anywhere"))

    for loader_id in table.loaders:
        owner = {}
        for canonical, row in table.rows:
            spelling = row[table.loaders.index(loader_id)]
            if spelling == NO_BLOCK:
                continue
            if ":" in spelling:
                errors.append(
                    (canonical, f"{loader_id} spells it {spelling!r}, which carries a namespace")
                )
            if spelling in owner:
                errors.append(
                    (
                        canonical,
                        f"{loader_id} spells both it and {owner[spelling]} {spelling!r}, so"
                        " get_block could not say which block that name means",
                    )
                )
            owner[spelling] = canonical

    return errors


def read_blocks(path: Path) -> dict:
    """A dump of one loader's block names, as block id to that loader's spelling.

    Two whitespace-separated columns per line, the id first. Anything else is an error rather than
    a guess at which column is the name: the one 1.2.5 writes itself is ``B <key> <id>``, so it has
    to be turned into this shape before it can be checked.
    """
    names = {}
    for number, raw in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = raw.strip()
        if not line or line.startswith("#"):
            continue
        columns = line.split()
        if len(columns) != 2:
            raise ValueError(
                f"{path}:{number}: expected a block id and a name, got {len(columns)} columns:"
                f" {line!r}"
            )
        names[columns[0]] = columns[1]
    return names


def verify_blocks_errors(table: Table, dumps: dict) -> list:
    """Where a loader's column disagrees with the blocks that loader actually has.

    Every spelling a loader's column uses has to be a name that loader has, because a spelling it
    does not have is a name ``place_block`` cannot resolve and ``get_block`` can never return.
    """
    errors = []
    for loader_id, names in sorted(dumps.items()):
        if loader_id not in table.loaders:
            errors.append(
                (loader_id, "it is not a loader under loaders/, so it has no column to check")
            )
            continue

        spellings = set(table.spellings(loader_id).values())
        if not spellings & set(names.values()):
            errors.append(
                (
                    loader_id,
                    "none of its block names appear in its column, so the dump and the column"
                    " cannot be talking about the same loader",
                )
            )
            continue

        for canonical, spelling in sorted(table.spellings(loader_id).items()):
            if spelling not in names.values():
                errors.append((canonical, f"{loader_id} has no block called {spelling!r}"))
    return errors


def unmentioned_blocks(table: Table, dumps: dict) -> list:
    """How many blocks each dump holds that its column does not mention, for a re-audit to read."""
    report = []
    for loader_id, names in sorted(dumps.items()):
        if loader_id not in table.loaders:
            continue
        spellings = set(table.spellings(loader_id).values())
        missing = sorted(set(names.values()) - spellings)
        report.append((loader_id, len(names), missing))
    return report


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--table", type=Path, default=TABLE, help="the vanilla_blocks.txt to check")
    parser.add_argument(
        "--loaders",
        type=Path,
        default=Path("loaders"),
        help="the loaders directory whose loader ids the table's columns must match",
    )
    parser.add_argument(
        "--verify-blocks",
        action="append",
        default=[],
        metavar="LOADER=FILE",
        help=(
            "cross-check a loader's column against a dump of its real block names"
            " (<block id> <name> per line); repeatable"
        ),
    )
    args = parser.parse_args()

    if not args.table.is_file():
        print(f"FAILED: {args.table} does not exist.", file=sys.stderr)
        return 1
    if not args.loaders.is_dir():
        print(f"FAILED: {args.loaders} does not exist.", file=sys.stderr)
        return 1

    try:
        table = parse(
            args.table.read_text(encoding="utf-8"),
            loaders_in_repository(args.loaders),
            args.table,
        )
    except ValueError as e:
        print(f"FAILED: {e}", file=sys.stderr)
        return 1

    errors = table_errors(table, args.table)

    dumps = {}
    for entry in args.verify_blocks:
        loader_id, separator, path = entry.partition("=")
        if not separator or not path:
            print(f"FAILED: --verify-blocks wants LOADER=FILE, got {entry!r}", file=sys.stderr)
            return 1
        dump_path = Path(path)
        if not dump_path.is_file():
            print(f"FAILED: {dump_path} does not exist.", file=sys.stderr)
            return 1
        dumps[loader_id] = read_blocks(dump_path)

    if dumps:
        errors += verify_blocks_errors(table, dumps)

    if errors:
        for canonical, message in errors:
            print(f"FAILED: {canonical}: {message}", file=sys.stderr)
        print(f"{len(errors)} problem(s) in {args.table}", file=sys.stderr)
        return 1

    print(
        f"{args.table}: {len(table.rows)} canonical names across {len(table.loaders)} loaders"
        f" ({', '.join(table.loaders)})"
    )
    for loader_id, total, missing in unmentioned_blocks(table, dumps):
        print(
            f"{loader_id}: {total} blocks, {len(missing)} of them not in its column"
            + (f": {', '.join(missing)}" if missing else "")
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
