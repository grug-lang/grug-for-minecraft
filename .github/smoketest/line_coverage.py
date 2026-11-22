#!/usr/bin/env python3
"""Line and branch coverage of smoketest.c from the mods/coverage/ tests, for the smoketest job (#1589).

    line_coverage.py <gcov-json-file> <smoketest.c> [--assert]

The first argument is the output of ``gcov --json-format --stdout`` (``-`` reads stdin). A line is
covered when its count is above 0, and a branch (gcov's per-line ``branches``) when its count is
above 0. A marker excludes its line and that line's branches alike. A line may be excluded only with a written reason, so that a missing
test is never hidden by a bare marker:

    code();  // SMOKETEST_EXCL: why no committed test can reach this line
    // SMOKETEST_EXCL_START: why no committed test can reach these lines
    ...
    // SMOKETEST_EXCL_STOP

A marker with no reason, a START with no STOP, a STOP with no START and a nested START are errors.
It prints both percentages and the uncovered ``line`` numbers and branch lines, and exits 0, unless ``--assert`` is given, which
exits 1 below 100% of either or on a bad marker. The job turns the assertion on with SMOKETEST_ASSERT_COVERAGE.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
from pathlib import Path

# The marker has to start a // comment: the text outside a comment, such as a string, is no marker.
MARKER = re.compile(
    r"^(?:[^\"\n]|\"(?:[^\"\\\n]|\\.)*\")*?//\s*SMOKETEST_EXCL(_START|_STOP)?\b(?:\s*:\s*(\S.*))?"
)


def excluded_lines(source: str) -> tuple[set[int], list[str]]:
    """The 1-based lines excluded by a marker with a reason, and the problems with the markers."""
    excluded: set[int] = set()
    problems: list[str] = []
    start: int | None = None
    for number, line in enumerate(source.splitlines(), 1):
        match = MARKER.search(line)
        if match is None:
            if start is not None:
                excluded.add(number)
            continue
        kind, reason = match.group(1), match.group(2)
        if kind != "_STOP" and not reason:
            problems.append(f"line {number}: an exclusion needs a reason after a colon")
        if kind == "_START":
            if start is not None:
                problems.append(
                    f"line {number}: SMOKETEST_EXCL_START inside the one at line {start}"
                )
            start = number
            excluded.add(number)
        elif kind == "_STOP":
            if start is None:
                problems.append(f"line {number}: SMOKETEST_EXCL_STOP with no START")
            start = None
            excluded.add(number)
        else:
            excluded.add(number)
    if start is not None:
        problems.append(f"line {start}: SMOKETEST_EXCL_START is never stopped")
    return excluded, problems


def measure(gcov: dict, source: str, name: str = "smoketest.c") -> tuple:
    """Counted lines, covered lines, uncovered lines, counted branches, covered branches, the
    uncovered branches as (line, index) pairs, and the marker problems."""
    excluded, problems = excluded_lines(source)
    counts: dict[int, int] = {}
    branches: dict[tuple[int, int], int] = {}
    for file in gcov.get("files", []):
        if Path(file["file"]).name != name:
            continue
        for line in file["lines"]:
            number = line["line_number"]
            counts[number] = counts.get(number, 0) + line["count"]
            for index, branch in enumerate(line.get("branches", [])):
                key = (number, index)
                branches[key] = branches.get(key, 0) + branch["count"]
    counted = {n: c for n, c in counts.items() if n not in excluded}
    missed = sorted(n for n, c in counted.items() if c == 0)
    kept = {k: c for k, c in branches.items() if k[0] not in excluded}
    missed_branches = sorted(k for k, c in kept.items() if c == 0)
    return (
        len(counted),
        len(counted) - len(missed),
        missed,
        len(kept),
        len(kept) - len(missed_branches),
        missed_branches,
        problems,
    )


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=(__doc__ or "").split("\n\n")[0])
    parser.add_argument("gcov_json")
    parser.add_argument("source")
    parser.add_argument("--assert", dest="check", action="store_true")
    args = parser.parse_args(argv)
    raw = sys.stdin.read() if args.gcov_json == "-" else Path(args.gcov_json).read_text()
    total, covered, missed, btotal, bcovered, bmissed, problems = measure(
        json.loads(raw), Path(args.source).read_text()
    )
    percent = 100.0 * covered / total if total else 0.0
    bpercent = 100.0 * bcovered / btotal if btotal else 0.0
    print(f"smoketest.c line coverage: {covered}/{total} lines, {percent:.1f}%")
    if missed:
        print("uncovered lines: " + ", ".join(map(str, missed)))
    print(f"smoketest.c branch coverage: {bcovered}/{btotal} branches, {bpercent:.1f}%")
    if bmissed:
        # One entry per line, with how many of its branches are uncovered.
        per_line: dict[int, int] = {}
        for number, _ in bmissed:
            per_line[number] = per_line.get(number, 0) + 1
        print("uncovered branches: " + ", ".join(f"{n} ({c})" for n, c in per_line.items()))
    for problem in problems:
        print(f"::warning::smoketest.c exclusion problem, {problem}")
        print(f"EXCLUSION PROBLEM: {problem}")
    if args.check and (missed or bmissed or problems or total == 0):
        print("coverage assertion: FAILED")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
