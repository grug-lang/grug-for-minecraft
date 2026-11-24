#!/usr/bin/env python3
"""Runs test scripts side by side, one process each, and names the one that failed.

    parallel_test_runner.py [--jobs N] <script|directory> [<script|directory> ...]

A directory stands for every ``*_test.py`` under it, subdirectories included, sorted, so a new test
file runs without anyone adding it to a list: a list that must be remembered is a list that goes
stale, and a test missing from it is a test that silently never runs. A test that has to run in its
own step (its own ``if:``, artifacts or budget) says so with a module-level ``RUNS_ALONE`` reason in
its own file; the directory mode prints that reason and skips it, so not running is a decision
written beside the test rather than a path somebody forgot. A declaration alone cannot silence a
test: ``parallel_test_runner_test.py`` fails unless a ``run:`` step of ``build.yml`` names every
declared file, so a file skipped here still runs where its reason says it does.

The Python suites of the core tests job are waits, not work: run_loader_test.py's fake Gradle
sleeps two seconds in every case, so 45 cases took 112 s of a 119 s step while the runner's four
CPUs sat idle. Run side by side, the step costs about a quarter of what the waits add up to.

A script is a process of its own, and so is every case of run_loader_test.py (it runs its own cases
through this module), because a process is the only boundary that cannot leak a path, a port, an
environment variable or a module-level patch from one suite or case into another. Each suite already
builds a fake repository and a temporary directory per case, so that boundary costs nothing.

A script's output is held back until it finishes, so a passing run is one line per script and the
job's log stays readable. A failing script's whole output is printed under its name as soon as it
fails, and no further script starts after that: the step fails on the first failure rather than on
whichever worker happened to finish last, and what is already running still finishes so its output
is reported too.

GRUG_TEST_JOBS, or --jobs N, sets the number of processes; the default is one per CPU.
"""

from __future__ import annotations

import ast
import os
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import IO, Iterable, Sequence

# A test module's declaration that its own workflow step runs it, with the reason as the value.
RUNS_ALONE = "RUNS_ALONE"


def positive(text: str, what: str) -> int:
    """The whole number in text, or a usage error naming what it was."""
    try:
        value = int(text)
    except ValueError:
        raise SystemExit(f"{what} must be a whole number, got {text!r}") from None
    if value < 1:
        raise SystemExit(f"{what} must be at least 1, got {value}")
    return value


def jobs_from_environment() -> int:
    """One process per CPU unless GRUG_TEST_JOBS says otherwise."""
    value = os.environ.get("GRUG_TEST_JOBS", "").strip()
    if not value:
        return os.cpu_count() or 1
    return positive(value, "GRUG_TEST_JOBS")


def parse_jobs(argv: Sequence[str]) -> tuple[int, list[str]]:
    """(the number of processes, the arguments after a leading --jobs N)."""
    args = list(argv)
    if args[:1] == ["--jobs"]:
        if len(args) < 2:
            raise SystemExit("--jobs needs a number")
        return positive(args[1], "--jobs"), args[2:]
    if args and args[0].startswith("--jobs="):
        return positive(args[0][len("--jobs=") :], "--jobs"), args[1:]
    return jobs_from_environment(), args


def run(
    commands: Iterable[tuple[str, Sequence[str]]],
    jobs: int,
    stream: IO[str] | None = None,
) -> int:
    """Runs each (label, argv) command once, up to jobs at a time; returns 1 if any failed.

    The labels have to be unique: they are what the log and the summary call each command.
    """
    stream = stream or sys.stdout
    commands = list(commands)
    lock = threading.Lock()
    failed = threading.Event()
    started: list[str] = []
    results: list[tuple[str, int]] = []

    def say(text: str) -> None:
        with lock:
            print(text, file=stream, flush=True)

    def one(command: tuple[str, Sequence[str]]) -> None:
        label, argv = command
        # Checked when the worker picks the command up, not when the queue was filled, so a failure
        # stops everything that has not started yet while the commands already running finish.
        if failed.is_set():
            return
        say(f"==> {label}: started")
        with lock:
            started.append(label)
        began = time.monotonic()
        try:
            finished = subprocess.run(list(argv), capture_output=True, text=True)
            output, code = finished.stdout + finished.stderr, finished.returncode
        except OSError as error:
            output, code = f"{error}\n", 127
        elapsed = time.monotonic() - began
        with lock:
            results.append((label, code))
        if code == 0:
            say(f"==> {label}: passed in {elapsed:.1f}s")
            return
        failed.set()
        with lock:
            print(f"==> {label}: FAILED in {elapsed:.1f}s with exit code {code}", file=stream)
            print(f"----- output of {label} -----", file=stream)
            stream.write(output)
            if output and not output.endswith("\n"):
                print(file=stream)
            print(f"----- end of {label} -----", file=stream)
            stream.flush()

    with ThreadPoolExecutor(max_workers=jobs) as pool:
        list(pool.map(one, commands))

    failures = [label for label, code in results if code != 0]
    if failures:
        say(f"==> {len(failures)} of {len(commands)} failed: {', '.join(failures)}")
        # Kept to a count: after a failure early in the queue this list would be most of the suite.
        not_started = len(commands) - len(started)
        if not_started:
            say(f"==> {not_started} did not run because an earlier one failed")
        return 1
    say(f"==> all {len(commands)} passed")
    return 0


def runs_alone_reason(path: Path) -> str | None:
    """The module-level RUNS_ALONE reason in a test file, or None when it declares none.

    The file is parsed rather than imported, because a test module does work when imported and
    expanding a directory must not run a test just to read one name. A declaration without a reason
    is refused: an empty one is how a test would get skipped by accident.
    """
    try:
        tree = ast.parse(path.read_text(encoding="utf-8"))
    except (OSError, SyntaxError, UnicodeDecodeError):
        return None
    for node in tree.body:
        if isinstance(node, ast.Assign):
            targets = [target for target in node.targets if isinstance(target, ast.Name)]
        elif isinstance(node, ast.AnnAssign) and isinstance(node.target, ast.Name):
            targets = [node.target]
        else:
            continue
        if not any(target.id == RUNS_ALONE for target in targets):
            continue
        try:
            reason = ast.literal_eval(node.value)
        except ValueError:
            reason = None
        if not isinstance(reason, str) or not reason.strip():
            raise SystemExit(
                f"{path}: {RUNS_ALONE} needs a reason saying why it runs in its own step"
            )
        return reason
    return None


def tests_in(directory: Path) -> list[Path]:
    """Every ``*_test.py`` under a directory, sorted, subdirectories included.

    Recursive so a test moved into a package still runs, and a match that is a directory is left
    for the caller to report rather than parsed as a test.
    """
    return sorted(path for path in directory.rglob("*_test.py") if "__pycache__" not in path.parts)


def expand(argument: str, stream: IO[str] | None = None) -> list[str]:
    """The scripts an argument names: a directory's tests, or the path itself.

    A directory's paths are printed and returned as given, so a log line names the file the same
    way the workflow does.
    """
    stream = stream or sys.stdout
    if not Path(argument).is_dir():
        return [argument]
    scripts = []
    for candidate in tests_in(Path(argument)):
        if not candidate.is_file():
            print(f"==> {candidate}: skipped, not a file", file=stream, flush=True)
            continue
        reason = runs_alone_reason(candidate)
        if reason is not None:
            print(f"==> {candidate}: skipped, runs alone: {reason}", file=stream, flush=True)
            continue
        scripts.append(str(candidate))
    return scripts


def main(argv: Sequence[str]) -> int:
    jobs, args = parse_jobs(argv)
    if not args:
        print(
            "usage: parallel_test_runner.py [--jobs N] <script|directory> [<script|directory> ...]",
            file=sys.stderr,
        )
        return 2
    commands = []
    for argument in args:
        scripts = expand(argument)
        if not scripts:
            print(f"no test script in: {argument}", file=sys.stderr)
            return 2
        for script in scripts:
            if not Path(script).is_file():
                print(f"no such test script: {script}", file=sys.stderr)
                return 2
            commands.append((script, [sys.executable, script]))
    return run(commands, jobs)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
