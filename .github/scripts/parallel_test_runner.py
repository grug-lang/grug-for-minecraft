#!/usr/bin/env python3
"""Runs test scripts side by side, one process each, and names the one that failed.

    parallel_test_runner.py [--jobs N] <script> [<script> ...]

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

import os
import subprocess
import sys
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import IO, Iterable, Sequence


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


def main(argv: Sequence[str]) -> int:
    jobs, args = parse_jobs(argv)
    if not args:
        print("usage: parallel_test_runner.py [--jobs N] <script> [<script> ...]", file=sys.stderr)
        return 2
    commands = []
    for script in args:
        if not Path(script).is_file():
            print(f"no such test script: {script}", file=sys.stderr)
            return 2
        commands.append((script, [sys.executable, script]))
    return run(commands, jobs)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
