#!/usr/bin/env python3
"""Fail when code only a client test reaches has no recorded reason (#1033).

    server-reachable.py [--map <coverage-map.json>] [--server-tests <dir>]
                        [--baseline <file>] [--record] [--out <file>]

The multiplayer job runs only the tests that define ``tick_any_side``, plus the ``forwarded_``
tests on a loader whose server forwards client calls, and its server log says which tests it
actually ran. This script compares that record with the coverage map the coverage job builds from
every coverage test's own exec data. A class method that some coverage test reaches on a client,
and that no test a dedicated server ran reaches, is either code no server test covers or client
code whose reason nobody wrote down. It fails the run unless every such method is recorded in
server-reachable.json, which gives its class a reason.

Server-reachable is therefore defined without a hand-kept class list: a method is server-reachable
when a test that a dedicated server ran reaches it, and nothing here needs to know which classes a
server can load or which lines reference a client. The list of tests each server ran is the
server's own log, so this keeps working when #1040 gives every test a server run: the set of tests
the servers ran grows, the recorded set shrinks, and this script does not change. A method reached
on any one loader's server counts, because the property this guards is that a dedicated server can
reach the code at all, and a loader whose server skips a test for a reason of its own (the lists in
the loaders' server-unsupported-tests.txt) is not a second, loader-shaped copy of this file.

The map is the ``coverage-map`` artifact of the run, built from this run's own per-test exec data
against this run's own classes, so a key never survives a rename or a body edit that changes the
flow. Two kinds of key are left out:

- a class the coverage gate excludes, because the gate does not count it and nothing here should
  make an author record a reason for a mixin or a screen;
- ``<clinit>``, because a class initializer runs once per JVM and the map attributes it to whichever
  test first used the class, which says nothing about whether a dedicated server can reach it.

Only a dispatch runs the multiplayer job, so a pull request and a push hold no server test lists,
and the comparison says so and passes. A run that holds the lists holds one per loader the map
names, and a missing one is a failure: an absent list would silently shrink what counts as
server-reachable and let the drift this guard exists for through.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Reuse the gate's exclusion list, so a class counts as counted in exactly one place.
_COMBINE = importlib.util.spec_from_file_location("combine_coverage", HERE / "combine-coverage.py")
combine = importlib.util.module_from_spec(_COMBINE)
_COMBINE.loader.exec_module(combine)

ARTIFACT_PREFIX = "coverage-server-tests-"
TESTS_FILE = "tests.txt"
CLASS_INITIALIZER = "<clinit>"
# A test that is meant to fail still passes, and the runner's line says so after the path. The map
# keys a test by its path alone, so the note has to come off before the two are compared: kept, it
# would make every test of an expected error look like one no server ran.
EXPECTED_ERROR = " (expected error)"


def counted(name: str) -> bool:
    """Whether the coverage gate counts this class, by the rule combine-coverage.py applies."""
    return name.rsplit("/", 1)[-1].split("$", 1)[0] not in combine.EXCLUDED_CLASSES


def test_name(line: str) -> str:
    """A path the runner printed, without the note that says the test was meant to fail."""
    line = line.strip()
    return line[: -len(EXPECTED_ERROR)] if line.endswith(EXPECTED_ERROR) else line


def server_tests(directory: Path) -> dict[str, set[str]]:
    """The tests each loader's dedicated server ran, keyed by the loader its artifact names."""
    lists: dict[str, set[str]] = {}
    for entry in sorted(directory.iterdir()):
        if not entry.is_dir() or not entry.name.startswith(ARTIFACT_PREFIX):
            continue
        loader = entry.name[len(ARTIFACT_PREFIX) :]
        path = entry / TESTS_FILE
        if not path.is_file():
            print(
                f"error: {path} is missing, so the tests the dedicated server of {loader} ran are"
                f" unknown and what it reached cannot be compared",
                file=sys.stderr,
            )
            lists[loader] = set()
            continue
        lists[loader] = {name for name in map(test_name, path.read_text().splitlines()) if name}
    return lists


def compare(
    map_data: dict, ran: dict[str, set[str]]
) -> tuple[dict[tuple[str, str], set[str]], list[str]]:
    """The counted methods no dedicated server reached, and the loaders that ran no coverage test.

    A method's value is the set of tests that reached it on a client, which is what the failure
    message shows: the test to give ``tick_any_side``.
    """
    covered: dict[tuple[str, str], set[str]] = {}
    reached: set[tuple[str, str]] = set()
    selected_away: list[str] = []
    for loader, data in sorted(map_data.get("loaders", {}).items()):
        tests = data.get("tests", {})
        if not tests:
            selected_away.append(loader)
            continue
        on_server = ran.get(loader, set())
        for test in sorted(tests):
            for name, info in tests[test].items():
                for method in info.get("methods", []):
                    if method.split(":", 1)[0] == CLASS_INITIALIZER:
                        continue
                    if not counted(name):
                        continue
                    key = (name, method)
                    covered.setdefault(key, set()).add(test)
                    if test in on_server:
                        reached.add(key)
    return {key: tests for key, tests in covered.items() if key not in reached}, selected_away


def load_baseline(path: Path) -> dict:
    if not path.is_file():
        return {"why": {}, "methods": {}}
    with path.open() as handle:
        data = json.load(handle)
    data.setdefault("why", {})
    data.setdefault("methods", {})
    return data


def recorded(data: dict) -> dict[tuple[str, str], None]:
    """The baseline's entries as (class, method) pairs."""
    return {
        (name, method): None
        for name, methods in data.get("methods", {}).items()
        for method in methods
    }


def unreasoned(entries, reasons: dict) -> list[str]:
    """The classes entries name that have no reason, sorted."""
    return sorted({name for name, _ in entries if not reasons.get(name, "").strip()})


def check(required: dict, baseline: dict, path: Path) -> int:
    """Fail on a method no dedicated server reached that the baseline does not record."""
    reasons = baseline.get("why", {})
    entries = recorded(baseline)
    missing = sorted(key for key in required if key not in entries)
    stale = sorted(key for key in entries if key not in required)

    classes = {name for name, _ in required}
    print(
        f"server-reachable: {len(required)} class method(s) in {len(classes)} class(es) are"
        f" reached on a client by tests no dedicated server ran, and {len(entries)} are recorded"
    )
    for key in missing:
        name, method = key
        tests = sorted(required[key])
        print(
            f"  {name} {method} (reached by {', '.join(tests[:3])}"
            + (f" and {len(tests) - 3} more" if len(tests) > 3 else "")
            + ")"
        )
    if stale:
        print(
            f"server-reachable: {len(stale)} recorded method(s) are reached on a dedicated"
            f" server now and can be removed from {path}"
        )
    broken = unreasoned(entries, reasons)
    if broken:
        print(
            f"error: {path} records method(s) of "
            + ", ".join(broken)
            + " without a reason; every class it names needs one",
            file=sys.stderr,
        )
        return 1
    if missing:
        without = unreasoned(missing, reasons)
        print(
            f"error: {len(missing)} class method(s) no dedicated server reaches are not recorded"
            f" in {path}. Give the test that covers it tick_any_side so it runs on a dedicated"
            f" server, or, when the code needs a client, record the method under its class in that"
            f" file with the reason it does."
            + (f" A class with no reason yet: {', '.join(without)}." if without else ""),
            file=sys.stderr,
        )
        return 1
    return 0


def record(required: dict, baseline: dict, out: Path | None) -> int:
    """Write the baseline that makes this run pass, keeping every existing reason."""
    methods: dict[str, list[str]] = {}
    for name, method in sorted(required):
        methods.setdefault(name, []).append(method)
    data = dict(baseline)
    data["methods"] = {name: sorted(values) for name, values in sorted(methods.items())}
    text = json.dumps(data, indent=2, sort_keys=True) + "\n"
    if out is None:
        print(text)
    else:
        out.write_text(text)
    reasons = data.get("why", {})
    for name in sorted(methods):
        if not reasons.get(name, "").strip():
            covered = sorted(
                {test for method in methods[name] for test in required[(name, method)]}
            )
            print(
                f"needs a reason: {name} ({len(methods[name])} method(s), reached on a client by"
                f" {', '.join(covered[:4])}" + (" and others)" if len(covered) > 4 else ")"),
                file=sys.stderr,
            )
    if out is not None:
        print(f"recorded {len(required)} method(s) in {len(methods)} class(es) in {out}")
    return 0


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument(
        "--map",
        type=Path,
        default=Path("coverage/coverage-map.json"),
        help="the coverage map the coverage job built (default: coverage/coverage-map.json)",
    )
    parser.add_argument(
        "--server-tests",
        type=Path,
        default=Path("coverage/artifacts"),
        help=(
            "the directory the run's coverage artifacts were downloaded to, holding one"
            " coverage-server-tests-<loader> per dedicated server (default: coverage/artifacts)"
        ),
    )
    parser.add_argument(
        "--baseline",
        type=Path,
        default=HERE / "server-reachable.json",
        help="the recorded client-only code (default: the file next to this script)",
    )
    parser.add_argument(
        "--record",
        action="store_true",
        help="write the baseline that makes this run pass instead of checking it",
    )
    parser.add_argument("--out", type=Path, help="where --record writes (default: stdout)")
    args = parser.parse_args(argv)

    if not args.map.is_file():
        print(
            f"server-reachable: {args.map} is missing, so this run recorded no coverage map to"
            f" compare and there is nothing to check",
            file=sys.stderr,
        )
        return 0
    with args.map.open() as handle:
        map_data = json.load(handle)
    lists = server_tests(args.server_tests) if args.server_tests.is_dir() else {}
    if not lists:
        print(
            "server-reachable: this run holds no dedicated-server test lists, which only a"
            " dispatch runs, so there is nothing to compare"
        )
        return 0
    empty = sorted(loader for loader, tests in lists.items() if not tests)
    if empty:
        print(
            "error: the dedicated server of "
            + ", ".join(empty)
            + " ran no test, so what it reached cannot be told from what it did not. The"
            " multiplayer job requires at least one test per leg, so a leg that ran none has"
            " failed already; its list is checked here so an empty one cannot pass as data",
            file=sys.stderr,
        )
        return 1
    absent = sorted(
        loader
        for loader, data in map_data.get("loaders", {}).items()
        if data.get("tests") and loader not in lists
    )
    if absent:
        print(
            "error: the run holds no dedicated-server test list for "
            + ", ".join(absent)
            + ", so the code its server reached cannot be told from code it did not",
            file=sys.stderr,
        )
        return 1
    required, selected_away = compare(map_data, lists)
    for loader in selected_away:
        print(
            f"server-reachable: {loader} ran no coverage test in this run, so its server test"
            f" list has nothing to compare"
        )
    if args.record:
        return record(required, load_baseline(args.baseline), args.out)
    return check(required, load_baseline(args.baseline), args.baseline)


if __name__ == "__main__":
    sys.exit(main())
