#!/usr/bin/env python3
"""Fail when code only a client test reaches has no recorded reason (#1033).

    server-reachable.py [--map <dir-or-coverage-map.json>] [--server-tests <dir>]
                        [--baseline <file>] [--record --recorded-from <text> [--prune]]
                        [--out <file>]

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
the servers ran grows, the recorded set shrinks, and this script does not change.

Where one loader's server skips a test for a reason of its own (the loader's
server-unsupported-tests.txt), a server of another loader that ran it still counts, because the
property this guards is that some dedicated server reaches the code. That union is only sound for
code the loaders share: the map lists the classes each loader compiles itself, and two loaders can
hold different classes of the same name (a1.1.2_01-ornithe's and b1.7.3-ornithe's OrnitheAdapter are
separate files), so a method of a loader's own class is judged by that loader's server alone.

The map is the newest ``coverage-map-<attempt>`` artifact of the run, built from this run's own
per-test exec data against this run's own classes, so a key never survives a rename or a body edit
that changes the flow. Two kinds of key are left out:

- a class the coverage gate excludes, because the gate does not count it and nothing here should
  make an author record a reason for a mixin or a screen;
- ``<clinit>``, because a class initializer runs once per JVM and the map attributes it to whichever
  test first used the class, which says nothing about whether a dedicated server can reach it.

The map also leaves the boot segment out: ``CoverageMapTool`` skips ``000-boot.exec``, so code only
the boot reaches is in no test's data. It cannot be checked here and needs no reason, which is why
the game directory shows up through the client's captures rather than through the boot.

Only a dispatch runs the multiplayer job, so a pull request and a push hold no server test lists,
and the comparison says so and passes. A run that holds the lists holds one per loader the map
names, and a missing one is a failure: an absent list would silently shrink what counts as
server-reachable and let the drift this guard exists for through. Each list names the run attempt
that wrote it, because a re-run of one leg uploads again while the other legs keep their earlier
artifact, so a loader's newest attempt is the list its leg wrote last (#1142).

``--record`` writes the file, and it needs ``--recorded-from`` so the record says which run it came
from. It refuses to drop a recorded method unless ``--prune`` says so, because a dispatch narrowed
with ``-f mods=`` runs a subset of the tests and would otherwise shrink the record silently.
"""

from __future__ import annotations

import argparse
import importlib.util
import json
import re
import sys
from pathlib import Path

HERE = Path(__file__).resolve().parent

# Reuse the gate's exclusion list, so a class counts as counted in exactly one place.
_COMBINE = importlib.util.spec_from_file_location("combine_coverage", HERE / "combine-coverage.py")
combine = importlib.util.module_from_spec(_COMBINE)
_COMBINE.loader.exec_module(combine)

ARTIFACT_PREFIX = "coverage-server-tests-"
# The multiplayer job names its artifact after the loader and the run attempt, so a re-run of one
# leg adds a list beside the older one instead of overwriting the name it shares with it.
ATTEMPT_SUFFIX = re.compile(r"^(.*)-(\d+)$")
TESTS_FILE = "tests.txt"
# The coverage job names its map artifact after the run attempt the same way, and the map itself
# keeps its file name inside, so the attempt is the only thing the artifact name adds.
MAP_ATTEMPT = re.compile(r"^coverage-map-(\d+)$")
MAP_FILE = "coverage-map.json"
CLASS_INITIALIZER = "<clinit>"
# A test that is meant to fail still passes, and the runner's line says so after the path. The map
# keys a test by its path alone, so the note has to come off before the two are compared: kept, it
# would make every test of an expected error look like one no server ran.
EXPECTED_ERROR = " (expected error)"
# javac numbers the lambdas of a class in source order, so adding a lambda anywhere in the class
# renames every later one. The record is keyed by method name, so without this a host function added
# elsewhere would look like client-only code that lost its reason. Two lambdas of one method can
# share a descriptor, so the counter is not an identity: the enclosing name, the descriptor and the
# rank within that pair are.
LAMBDA_COUNTER = re.compile(r"^lambda\$([^$]+)\$(\d+)(:.*)$")


def coverage_map(path: Path) -> tuple[Path | None, int | None]:
    """The coverage map to read, and the run attempt whose artifact holds it.

    The guard hands over the directory the run's ``coverage-map-<attempt>`` artifacts were
    downloaded to, and a re-run of the coverage job leaves several attempts in it. Every attempt of
    a run is that run's commit, so the newest is the map the run built last; reading the newest
    keeps the map on the same side of a re-run as the test lists, which are read the same way
    (#1145). ``path`` may also be a map file, which is how a map downloaded by hand is passed, and
    that has no attempt to name.
    """
    if path.is_file():
        return path, None
    if not path.is_dir():
        return None, None
    # A directory that holds the map itself, without an attempt directory around it, is accepted
    # too: the download action writes that layout when it fetches a single artifact by name, and a
    # map a person downloaded and unpacked lands there as well. There is no attempt to name then.
    flat = path / MAP_FILE
    if flat.is_file():
        return flat, None
    newest: tuple[int, Path] | None = None
    for entry in path.iterdir():
        match = MAP_ATTEMPT.match(entry.name)
        if match is None:
            continue
        candidate = entry / MAP_FILE
        if not candidate.is_file():
            continue
        attempt = int(match.group(1))
        if newest is None or attempt > newest[0]:
            newest = (attempt, candidate)
    return (newest[1], newest[0]) if newest else (None, None)


def counted(name: str) -> bool:
    """Whether the coverage gate counts this class, by the rule combine-coverage.py applies."""
    return name.rsplit("/", 1)[-1].split("$", 1)[0] not in combine.EXCLUDED_CLASSES


def test_name(line: str) -> str:
    """A path the runner printed, without the note that says the test was meant to fail."""
    line = line.strip()
    return line[: -len(EXPECTED_ERROR)] if line.endswith(EXPECTED_ERROR) else line


def artifact_loader(name: str) -> tuple[str, int]:
    """The loader and the run attempt an artifact directory names.

    ``coverage-server-tests-<loader>-<attempt>`` is what the multiplayer job uploads; a bare
    ``coverage-server-tests-<loader>`` is an artifact downloaded by hand, and the attempt is 0.
    """
    name = name[len(ARTIFACT_PREFIX) :]
    match = ATTEMPT_SUFFIX.match(name)
    return (match.group(1), int(match.group(2))) if match else (name, 0)


def server_tests(directory: Path) -> dict[str, set[str]]:
    """The tests each loader's dedicated server ran, keyed by the loader its artifact names.

    A re-run of a dispatch runs only the jobs that failed, so one leg writes a list under a higher
    attempt while the other legs keep the earlier one. Each loader's newest attempt is the list its
    leg wrote last, and an older one is a list from a run whose leg may have stopped early (#1142).
    """
    newest: dict[str, tuple[int, Path]] = {}
    for entry in sorted(directory.iterdir()):
        if not entry.is_dir() or not entry.name.startswith(ARTIFACT_PREFIX):
            continue
        loader, attempt = artifact_loader(entry.name)
        if loader in newest and newest[loader][0] >= attempt:
            continue
        newest[loader] = (attempt, entry)
    lists: dict[str, set[str]] = {}
    for loader, (attempt, entry) in sorted(newest.items()):
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
        source = f" from attempt {attempt}" if attempt else ""
        print(f"server-reachable: {loader} ran {len(lists[loader])} test(s){source}")
    return lists


def compare(
    map_data: dict, ran: dict[str, set[str]]
) -> tuple[dict[tuple[str, str], set[tuple[str, str]]], list[str]]:
    """The counted methods no dedicated server reached, and the loaders that ran no coverage test.

    A method's value is the set of (loader, test) pairs that reached it on a client, which is what
    the failure message shows: the test to give ``tick_any_side`` and the loader whose client runs
    it. A class a loader compiles itself is only judged by that loader's server, because two
    loaders' classes can share a name without being the same code; a class no loader lists is core
    code every loader runs, so any loader's server that reached it counts.
    """
    own = {
        loader: set(data.get("classes", {})) for loader, data in map_data.get("loaders", {}).items()
    }
    owned = set().union(*own.values()) if own else set()
    covered: dict[tuple[str, str], set[tuple[str, str]]] = {}
    reached: set[tuple[str, str, str]] = set()
    selected_away: list[str] = []
    for loader, data in sorted(map_data.get("loaders", {}).items()):
        tests = data.get("tests", {})
        if not tests:
            selected_away.append(loader)
            continue
        on_server = ran.get(loader, set())
        for test in sorted(tests):
            for name, info in tests[test].items():
                if not counted(name):
                    continue
                for method in info.get("methods", []):
                    if method.split(":", 1)[0] == CLASS_INITIALIZER:
                        continue
                    covered.setdefault((name, method), set()).add((loader, test))
                    if test in on_server:
                        reached.add((loader, name, method))
    required: dict[tuple[str, str], set[tuple[str, str]]] = {}
    for (name, method), reaches in covered.items():
        if name in owned:
            server_reached = all((loader, name, method) in reached for loader, _ in reaches)
        else:
            server_reached = any((loader, name, method) in reached for loader in own)
        if not server_reached:
            required[(name, method)] = reaches
    return required, selected_away


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


def lambda_groups(keys) -> dict[tuple[str, str, str], list[str]]:
    """Lambdas by class, enclosing method and descriptor, in the order javac numbered them.

    The counter is per class in source order, so it moves when a lambda is added anywhere in the
    class, but the order of the lambdas that share an enclosing method and descriptor does not.
    Two lambdas of one method can share a descriptor (a resource pack provider's ``lambda$register$0``
    and ``lambda$register$1`` both answer a ResourcePack), so that pair plus the rank is the
    identity a renumbering keeps.
    """
    groups: dict[tuple[str, str, str], list[tuple[int, str]]] = {}
    for name, method in keys:
        match = LAMBDA_COUNTER.match(method)
        if match is None:
            continue
        groups.setdefault((name, match.group(1), match.group(3)), []).append(
            (int(match.group(2)), method)
        )
    return {key: [method for _, method in sorted(members)] for key, members in groups.items()}


def renumbered(required: dict, entries: dict) -> dict[tuple[str, str], tuple[str, str]]:
    """Required methods whose lambda counter moved, each mapped to the entry it replaces.

    javac numbers the lambdas of a class in source order, so a lambda added anywhere in the class
    renames every later one. A moved lambda is the same code, so it is not client-only code that
    lost its reason, and the entry it replaces is not stale.

    A lambda the record still holds under its exact name is matched first. Only the leftovers are
    paired by rank inside a class, enclosing method and descriptor: the entries the record holds
    that the run does not need, and the names the run needs that the record does not hold. That
    keeps a class-wide shift from reading as new code without letting a new lambda take the place
    of a recorded one: a record of one of a shared-descriptor pair does not explain the other, and
    a record that holds ``$1`` does not explain a run that needs ``$0`` and ``$1``.
    """
    recorded_lambdas = lambda_groups(entries)
    moved: dict[tuple[str, str], tuple[str, str]] = {}
    for (name, enclosing, descriptor), methods in lambda_groups(required).items():
        recorded_methods = recorded_lambdas.get((name, enclosing, descriptor), [])
        wanted = set(methods)
        held = set(recorded_methods)
        gone = [method for method in recorded_methods if method not in wanted]
        fresh = [method for method in methods if method not in held]
        for before, after in zip(gone, fresh):
            moved[(name, after)] = (name, before)
    return moved


def check(required: dict, baseline: dict, path: Path) -> int:
    """Fail on a method no dedicated server reached that the baseline does not record."""
    reasons = baseline.get("why", {})
    entries = recorded(baseline)
    moved = renumbered(required, entries)
    missing = sorted(key for key in required if key not in entries and key not in moved)
    stale = sorted(key for key in entries if key not in required and key not in moved.values())

    classes = {name for name, _ in required}
    print(
        f"server-reachable: {len(required)} class method(s) in {len(classes)} class(es) are"
        f" reached on a client by tests no dedicated server ran, and {len(entries)} are recorded"
    )
    for key in missing:
        name, method = key
        tests = sorted(f"{test} on {loader}" for loader, test in required[key])
        print(
            f"  {name} {method} (reached by {', '.join(tests[:3])}"
            + (f" and {len(tests) - 3} more" if len(tests) > 3 else "")
            + ")"
        )
    for key, before in sorted(moved.items()):
        print(
            f"server-reachable: {key[0]} numbers {key[1]} where the record has {before[1]}, which"
            f" a new lambda elsewhere in the class renumbered; the record covers it"
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
            f" in {path}. Give the test that covers one tick_any_side so a dedicated server runs"
            f" it, or, when the code needs a client, record the method under its class in that file"
            f" with the reason it does. A test a loader's server skips (see the loader's"
            f" server-unsupported-tests.txt) covers nothing on that server, and a loader's own class"
            f" is judged by that loader's server alone: when either of those is what leaves a method"
            f" unreached, record it there with that reason."
            + (f" A class with no reason yet: {', '.join(without)}." if without else ""),
            file=sys.stderr,
        )
        return 1
    return 0


def record(
    required: dict, baseline: dict, out: Path | None, recorded_from: str, prune: bool
) -> int:
    """Write the baseline that makes this run pass, keeping every existing reason."""
    methods: dict[str, list[str]] = {}
    for name, method in sorted(required):
        methods.setdefault(name, []).append(method)
    dropped = sorted(key for key in recorded(baseline) if key not in required)
    if dropped and not prune:
        print(
            f"error: recording this run would drop {len(dropped)} recorded method(s), starting with"
            f" {dropped[0][0]} {dropped[0][1]}. A dispatch narrowed with -f mods= runs only some"
            f" tests, and its record would silently lose the rest; pass --prune when this run ran"
            f" every test.",
            file=sys.stderr,
        )
        return 1
    data = {
        "methods": {name: sorted(values) for name, values in sorted(methods.items())},
        "recorded_from": recorded_from,
        "why": baseline.get("why", {}),
    }
    text = json.dumps(data, indent=2, sort_keys=True) + "\n"
    if out is None:
        print(text)
    else:
        out.write_text(text)
    reasons = data["why"]
    for name in sorted(methods):
        if not reasons.get(name, "").strip():
            covered = sorted(
                {
                    f"{test} on {loader}"
                    for method in methods[name]
                    for loader, test in required[(name, method)]
                }
            )
            print(
                f"needs a reason: {name} ({len(methods[name])} method(s), reached on a client by"
                f" {', '.join(covered[:4])}" + (" and others)" if len(covered) > 4 else ")"),
                file=sys.stderr,
            )
    if dropped:
        print(
            f"server-reachable: the record drops {len(dropped)} method(s) this run does not need,"
            f" starting with {dropped[0][0]} {dropped[0][1]}"
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
        help=(
            "the coverage map to judge, or the directory the run's coverage-map-<attempt>"
            " artifacts were downloaded to, of which the newest attempt is read (default:"
            " coverage/coverage-map.json)"
        ),
    )
    parser.add_argument(
        "--server-tests",
        type=Path,
        default=Path("coverage/artifacts"),
        help=(
            "the directory the run's coverage artifacts were downloaded to, holding one"
            " coverage-server-tests-<loader>-<attempt> per dedicated server and attempt, of which"
            " each loader's newest attempt is read (default: coverage/artifacts)"
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
    parser.add_argument(
        "--recorded-from",
        metavar="TEXT",
        help="the run this record was taken from; required with --record",
    )
    parser.add_argument(
        "--prune",
        action="store_true",
        help="let --record drop recorded methods this run no longer needs",
    )
    parser.add_argument("--out", type=Path, help="where --record writes (default: stdout)")
    args = parser.parse_args(argv)

    lists = server_tests(args.server_tests) if args.server_tests.is_dir() else {}
    map_path, map_attempt = coverage_map(args.map)
    if map_path is None:
        if not lists:
            print(
                "server-reachable: this run holds neither a coverage map nor dedicated-server"
                " test lists, which only a dispatch runs, so there is nothing to compare"
            )
            return 0
        where = (
            f"{args.map} holds no coverage-map-<attempt> artifact"
            if args.map.is_dir()
            else f"{args.map} is missing"
        )
        print(
            f"error: {where}, so the tests the dedicated server of "
            + ", ".join(sorted(lists))
            + " ran have no coverage map to be compared against. The run's download step found"
            " lists and no map, so the map artifact is missing or was saved under another name",
            file=sys.stderr,
        )
        return 1
    if map_attempt is not None:
        print(f"server-reachable: read the coverage map of attempt {map_attempt}")
    with map_path.open() as handle:
        map_data = json.load(handle)
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
        if not args.recorded_from:
            print(
                "error: --record needs --recorded-from, naming the run and the commit the record"
                " was taken from, so the file says where its methods were seen",
                file=sys.stderr,
            )
            return 1
        return record(
            required, load_baseline(args.baseline), args.out, args.recorded_from, args.prune
        )
    return check(required, load_baseline(args.baseline), args.baseline)


if __name__ == "__main__":
    sys.exit(main())
