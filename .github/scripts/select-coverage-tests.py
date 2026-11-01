#!/usr/bin/env python3
"""Select the coverage tests a pull request may carry over instead of rerunning (#786).

    select-coverage-tests.py <base> <head> [--repo <dir>]

prints one line holding, comma separated, the runner paths of the coverage tests whose
recorded coverage from the base commit's run the gate may count in place of the tests: the
tests the proof shows the diff cannot affect. An empty line carries over nothing, so every
coverage test runs. The reasons go to stderr, one line per test, so the CI log says why each
test ran or was carried over.

The proof follows the issue's affected-test rules exactly, and every doubt resolves to
running the test, never to carrying it over:

- a test runs when its own file changed, and a new test runs, since it has no recorded
  coverage to carry;
- a test nested under its tests directory runs every coverage test: the map flattens a test
  path into dots and names it back as <mod>/tests/<name>, so a deeper path cannot be matched
  and carrying it over would leave the gate short;
- a test path holding a comma, a quote or a newline runs every coverage test: the carried-over
  names travel as one comma-separated value and as one line each in a file, and such a path
  cannot be represented in them;
- a change to core/, the runner, the test framework or mod_api.json runs every coverage
  test, and so does any other change outside mods/ (workflows, scripts, loaders, shared/):
  the gate cannot attribute such a change to a test, and a host entry's implementation
  lives in core, so a test that calls a changed host entry is covered by the same rule;
- a non-test file of the coverage mod itself runs every coverage test, and a block or item
  file anywhere runs every coverage test, which moves the ids the tests read;
- an empty diff, a base that cannot be read, or any other error runs every coverage test.

A carried-over test's assertions do not rerun, which is the guarantee the pull request
states it loses; the gate makes it up by proving 100% of core from the run plus the
carried-over tests' recorded coverage, which combine-coverage.py's --carry-over path does.
"""

from __future__ import annotations

import argparse
import importlib.util
import re
import sys
from pathlib import Path

# Reuse affected-mods.py's git helpers so both selectors read a diff the same way.
_MODS = importlib.util.spec_from_file_location(
    "affected_mods", Path(__file__).resolve().parent / "affected-mods.py"
)
aff = importlib.util.module_from_spec(_MODS)
_MODS.loader.exec_module(aff)

# A coverage test, as the runner names it: <mod>/tests/<name>-Test.grug. The counted mod.
COUNTED_MOD = "coverage"
# A changed core unit test runs in the core job, not in a game, so it cannot change what a
# coverage test does or records: it is no run trigger for the coverage tests.
CORE_TEST_JAVA = "core/src/test/"
# A block or item file, by its name: adding, changing or removing one moves the ids handed
# out after it, which the tests read.
_BLOCK_ITEM = re.compile(r"-Block\.grug$|-Item\.grug$")
# The carried-over names travel in one comma-separated value and one file line each, so a path
# holding a separator or a quote cannot be represented in them.
_UNREPRESENTABLE = re.compile("[\"',\n\r]")


def is_block_or_item_file(repo_path: str) -> bool:
    return bool(_BLOCK_ITEM.search(repo_path))


def _is_coverage_mod_file(repo_path: str) -> bool:
    # A file of the coverage mod itself, other than its tests: its blocks, entities, textures,
    # recipes and metadata are what the coverage tests observe, so a change to one runs them all.
    # A changed test is scoped to itself by the fine rule instead.
    return repo_path.startswith("mods/" + COUNTED_MOD + "/") and "/tests/" not in repo_path


def _is_no_game_input(repo_path: str) -> bool:
    # A changed core unit test is outside mods/ but is not an input of a game run: it runs in
    # the core job, so it cannot change what a coverage test does or records.
    return repo_path.startswith(CORE_TEST_JAVA)


def select_coverage_tests(
    *,
    changed: frozenset[str],
    coverage_tests: frozenset[str],
    base_coverage_tests: frozenset[str],
) -> dict[str, str]:
    """The coverage tests that must run, keyed by runner path, with the reason each ran.

    Every coverage test not in the result may be carried over: the diff provably cannot
    change what it does or what it records.
    """
    tests = set(coverage_tests)
    if not tests:
        return {}
    run: dict[str, str] = {}

    def run_all(reason: str) -> None:
        for t in tests:
            run.setdefault(t, reason)

    # The map keys a test by its flattened path and names it back as the first two dot groups
    # as directories and the rest as the file name, so the only form it can hand back is
    # <mod>/tests/<name>. A test nested deeper cannot be named back, and carrying it over would
    # leave the gate short of its coverage with the test itself not run, so every test runs.
    nested = sorted(t for t in tests if t.count("/") != 2)
    if nested:
        run_all(
            f"{nested[0]} is nested under its tests directory, which the map's flattened "
            f"key cannot name back, so every coverage test runs"
        )
        return run

    # The names are written as one comma-separated value and as one file line each, so a path
    # holding a separator or a quote cannot be carried over without corrupting the list.
    unrepresentable = sorted(t for t in tests if _UNREPRESENTABLE.search(t))
    if unrepresentable:
        run_all(
            f"{unrepresentable[0]} holds a comma, a quote or a newline, which the "
            f"comma-separated carry-over value cannot represent, so every coverage test runs"
        )
        return run

    if not changed:
        run_all("the diff is empty, which is more likely a mistake than a no-op")
        return run

    # Coarse triggers: anything the whole gate observes that cannot be scoped to a test.
    for p in sorted(changed):
        if p.startswith("mods/"):
            continue
        if _is_no_game_input(p):
            continue
        run_all(f"{p} is outside mods/ and a game run observes it, so every coverage test runs")
        break
    if any(_is_coverage_mod_file(p) for p in changed):
        run_all("a file of the coverage mod itself changed")
    if any(is_block_or_item_file(p) for p in changed):
        run_all("a block or item file changed, which moves the ids the tests read")

    # Fine triggers, per test: the test's own file, or a new test.
    for t in sorted(tests):
        if t in run:
            continue
        reasons = []
        if "mods/" + t in changed:
            reasons.append("its own test file changed")
        if t not in base_coverage_tests:
            reasons.append("it is new and has no recorded coverage to carry")
        if reasons:
            run[t] = "; ".join(reasons)

    return run


def coverage_test_facts(repo: Path, base: str, head: str):
    """(the coverage tests at head, and the coverage tests at base).

    A coverage test is a *-Test.grug under mods/coverage/. A new test is in the head set and
    not the base set, which runs it regardless of anything else.
    """

    def tests_at(ref: str) -> frozenset[str]:
        files = aff.tree_files(repo, ref)
        return frozenset(
            COUNTED_MOD + "/" + path
            for path, _ in files.get(COUNTED_MOD, [])
            if path.endswith("-Test.grug")
        )

    return tests_at(head), tests_at(base)


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("base", help="the commit the pull request is compared with")
    parser.add_argument("head", help="the commit that is built")
    parser.add_argument("--repo", default=".", help="the git repository (default: the current one)")
    args = parser.parse_args(argv)
    repo = Path(args.repo)

    try:
        changed = frozenset(aff.changed_paths(repo, args.base, args.head))
        head_tests, base_tests = coverage_test_facts(repo, args.base, args.head)
        selection = select_coverage_tests(
            changed=changed,
            coverage_tests=head_tests,
            base_coverage_tests=base_tests,
        )
    except Exception as e:  # noqa: BLE001 - whatever went wrong, the answer is to run everything
        print(
            f"select-coverage-tests: carrying over no coverage test, which runs every one: "
            f"{type(e).__name__}: {e}",
            file=sys.stderr,
        )
        return 0

    carried_over = head_tests - set(selection)
    for test in sorted(head_tests):
        if test in selection:
            print(f"select-coverage-tests: running {test}: {selection[test]}", file=sys.stderr)
        else:
            print(f"select-coverage-tests: carrying over {test}", file=sys.stderr)
    print(",".join(sorted(carried_over)))
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
