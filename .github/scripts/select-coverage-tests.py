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
- a test runs when it reads the id a modified code file of another mod declares, which is the
  file's own name: only a -Block.grug or -Item.grug file is scoped like that, and only the
  tests reading it rerun, because a modification moves no id;
- a block or item file added or removed anywhere runs every coverage test: ids are handed out
  in name order, so one moving is not attributable to a test;
- any other changed code file of another mod runs every coverage test: a block entity is
  created by the block that names it and a helper is called by another file, and this selector
  reads names rather than code, so it cannot attribute those to a test;
- a file of another mod under data/ or assets/ outside the grug namespace runs every coverage
  test, because that namespace is shared with vanilla and with every other mod;
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
- a non-test file of the coverage mod itself runs every coverage test, since its blocks,
  entities, textures, recipes and metadata are what the coverage tests observe;
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
# A block or item file, by its name: adding or removing one moves the ids handed out after it,
# which the tests read, while a modification of one leaves every id where it was.
_BLOCK_ITEM = re.compile(r"-Block\.grug$|-Item\.grug$")
# The carried-over names travel in one comma-separated value and one file line each, so a path
# holding a separator or a quote cannot be represented in them.
_UNREPRESENTABLE = re.compile("[\"',\n\r]")
# The literal forms through which one grug file names another's block, item or entity: a
# "grug:<name>" string for an id, and an e"<name>" entity literal. A grug string cannot be built
# from parts, so a name a file reads is a literal in that file.
_SYMBOL = re.compile(r'(?:\be"|"grug:)([^"\\\n]+)"')
# How the diff changed a path: a modification keeps every id where it was, an addition or a
# removal moves the ids of what is named after it, and the selector knows what to do with those
# two and nothing else. Any other status, a typechange say, is left out, which runs more.
MODIFIED = "modified"
ADDED = "added"
GONE = "gone"
_STATUSES = {"A": ADDED, "C": ADDED, "R": ADDED, "D": GONE, "M": MODIFIED}


def is_block_or_item_file(repo_path: str) -> bool:
    return bool(_BLOCK_ITEM.search(repo_path))


def is_another_mod_code_file(repo_path: str) -> bool:
    """Whether repo_path is a code file of a mod other than the counted one."""
    parts = repo_path.split("/")
    return len(parts) >= 4 and parts[0] == "mods" and parts[2] == "code" and parts[1] != COUNTED_MOD


def declared_symbol(repo_path: str) -> str | None:
    """The name a code file declares, which is its stem: ``code/wooden_pipe-Block.grug`` ships
    ``wooden_pipe``, the id the file registers."""
    return aff.shipped_name("/".join(repo_path.split("/")[2:]))


def referenced_symbols(text: str) -> frozenset[str]:
    """The block, item and entity names a grug file's source reads, without its comments."""
    return frozenset(_SYMBOL.findall(aff.strip_grug_comments(text)))


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
    code_status: dict[str, str] | None = None,
    test_symbols: dict[str, frozenset[str]] | None = None,
) -> dict[str, str]:
    """The coverage tests that must run, keyed by runner path, with the reason each ran.

    ``code_status`` holds the diff's status for the paths it can scope, and ``test_symbols`` the
    names each coverage test reads, so the fine rule can tell a test that reads a changed block from
    one that does not. A path or a test either mapping does not hold is a doubt, which runs more.
    Every coverage test not in the result may be carried over: the diff provably cannot change
    what it does or what it records.
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
    # Another mod's code and the namespaces it shares with vanilla: what the selector cannot
    # attribute to a test, it runs every test for.
    statuses = code_status or {}
    for p in sorted(changed):
        parts = p.split("/")
        if len(parts) >= 4 and parts[0] == "mods" and parts[2] in ("data", "assets"):
            # A file with no namespace directory at all is not one the mods share, but the
            # selector cannot say what reads it either, so it counts as shared.
            namespace = parts[3] if len(parts) >= 5 else None
            if parts[1] != COUNTED_MOD and namespace != aff.PRIVATE_NAMESPACE:
                run_all(
                    f"{p} is in the namespace {namespace!r}, which every mod shares with vanilla, "
                    f"so every coverage test runs"
                )
                break
        if not is_another_mod_code_file(p):
            continue
        declared = declared_symbol(p)
        if statuses.get(p) == MODIFIED and is_block_or_item_file(p) and declared is not None:
            continue  # scoped below, to the tests that read the id the file declares
        if statuses.get(p) in (ADDED, GONE) and is_block_or_item_file(p):
            run_all(
                f"{p} is a block or item file that {'appeared' if statuses[p] == ADDED else 'is gone'}"
                f", which moves the ids the tests read, so every coverage test runs"
            )
        else:
            run_all(
                f"{p} is a code file of another mod whose change the selector cannot attribute "
                f"to a test, so every coverage test runs"
            )
        break

    # Fine triggers, per test: the test's own file, a new test, and the id of a modified block or
    # item file of another mod that the test reads. The name a test reads is a literal, and the
    # id of a block or item does not move when its file is only modified, so the tests that read
    # it are exactly the ones that can observe the change.
    symbols = test_symbols or {}
    for p in sorted(changed):
        if statuses.get(p) != MODIFIED or not is_another_mod_code_file(p):
            continue
        if not is_block_or_item_file(p):
            continue
        declared = declared_symbol(p)
        if declared is None:
            run_all(f"the selector cannot tell what id {p} declares, so every coverage test runs")
            continue
        for t in sorted(tests):
            if t in run:
                continue
            if t not in symbols:
                run[t] = (
                    f"its source could not be read, so the selector cannot tell whether it reads {declared}"
                )
            elif declared in symbols[t]:
                run[t] = f"it reads {declared}, which {p} declares"

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


def code_statuses(repo: Path, base: str, head: str) -> dict[str, str]:
    """The diff's status for each path it changes, where the selector distinguishes them.

    A modified block or item file keeps every id where it was and only reruns the tests that read
    it, while an added or a removed one moves the ids after it, so the two cannot share a rule.
    --name-status -z prints the status and then the path, both NUL terminated.
    """
    out = aff.git(repo, "diff", "--name-status", "-z", "--no-renames", "--no-ext-diff", base, head)
    fields = out.split(b"\0")
    statuses: dict[str, str] = {}
    for status, path in zip(fields[0::2], fields[1::2]):
        if not status or not path:
            continue
        known = _STATUSES.get(status.decode(errors="replace")[:1])
        if known is not None:
            statuses[path.decode(errors="surrogateescape")] = known
    return statuses


def test_symbol_facts(
    repo: Path, head: str, coverage_tests: frozenset[str]
) -> dict[str, frozenset[str]]:
    """For each coverage test, the block, item and entity names its source reads.

    A test that reads a modified block's id is the only coverage test that can observe that block
    change, so the names are what scopes the fine rule. A test the selector cannot read is left
    out, which the selector treats as a doubt and runs the test for.
    """
    paths = aff.tree_files(repo, head).get(COUNTED_MOD, [])
    wanted = {
        COUNTED_MOD + "/" + path: blob
        for path, blob in paths
        if COUNTED_MOD + "/" + path in coverage_tests
    }
    contents = aff.read_blobs(repo, set(wanted.values()))
    return {
        test: referenced_symbols(contents[blob].decode(errors="replace"))
        for test, blob in wanted.items()
    }


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
            code_status=code_statuses(repo, args.base, args.head),
            test_symbols=test_symbol_facts(repo, args.head, head_tests),
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
