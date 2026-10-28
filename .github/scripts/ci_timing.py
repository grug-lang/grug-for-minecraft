#!/usr/bin/env python3
"""Report where the time of one GitHub Actions run went, as markdown.

    ci_timing.py <run-id> [--repo OWNER/REPO] [--top N]
    ci_timing.py --jobs-json FILE [--top N]

The first form asks `gh api` for the run's jobs and steps. The second reads the same JSON from a
file, a list of job objects or the `{"jobs": [...]}` the API returns, so a saved run can be analysed
again offline.

The report has four parts:

- the wall time of the run, and how much of the summed job time was spent queued for a runner;
- the critical path: from the job that finished last, each step back is the job that finished
  latest before that job started. The `needs` graph is not in the jobs API, so this is the chain of
  jobs whose end gated the next job's start, which is what shortening the run has to shorten;
- the slowest jobs, with queue time, run time and their slowest step;
- steps grouped by name across all jobs, to show setup that every job repeats.
"""

from __future__ import annotations

import argparse
import json
import subprocess
import sys
from collections import defaultdict
from dataclasses import dataclass
from datetime import datetime

DEFAULT_REPO = "grug-lang/grug-for-minecraft"
DEFAULT_TOP = 10


@dataclass(frozen=True)
class Span:
    name: str
    created: datetime | None
    started: datetime
    completed: datetime
    steps: tuple["Span", ...] = ()

    @property
    def seconds(self) -> float:
        return (self.completed - self.started).total_seconds()

    @property
    def queued(self) -> float:
        if self.created is None:
            return 0.0
        return max(0.0, (self.started - self.created).total_seconds())


def parse_time(text: str) -> datetime:
    return datetime.fromisoformat(text.replace("Z", "+00:00"))


def parse_step(step: dict) -> Span | None:
    # A skipped step has no start or end time.
    if not step.get("started_at") or not step.get("completed_at"):
        return None
    return Span(
        step["name"], None, parse_time(step["started_at"]), parse_time(step["completed_at"])
    )


def parse_jobs(raw: list[dict]) -> list[Span]:
    """Turn API job objects into spans, dropping jobs that never ran (skipped or cancelled queued)."""
    jobs = []
    for job in raw:
        if not job.get("started_at") or not job.get("completed_at"):
            continue
        steps = tuple(s for s in map(parse_step, job.get("steps") or []) if s is not None)
        created = parse_time(job["created_at"]) if job.get("created_at") else None
        jobs.append(
            Span(
                job["name"],
                created,
                parse_time(job["started_at"]),
                parse_time(job["completed_at"]),
                steps,
            )
        )
    return jobs


def critical_path(jobs: list[Span]) -> list[Span]:
    """The chain of jobs that gated the end of the run, first job first."""
    path = [max(jobs, key=lambda j: j.completed)]
    while True:
        before = [j for j in jobs if j.completed <= path[-1].started and j not in path]
        if not before:
            break
        path.append(max(before, key=lambda j: j.completed))
    return path[::-1]


def fmt(seconds: float) -> str:
    seconds = round(seconds)
    return f"{seconds // 60}m{seconds % 60:02d}s" if seconds >= 60 else f"{seconds}s"


def table(header: list[str], rows: list[list[str]]) -> list[str]:
    lines = ["| " + " | ".join(header) + " |", "| " + " | ".join(":---" for _ in header) + " |"]
    lines += ["| " + " | ".join(row) + " |" for row in rows]
    return lines


def report(jobs: list[Span], top: int = DEFAULT_TOP) -> str:
    if not jobs:
        return "No job in this run has started and finished, so there is nothing to report.\n"

    first_start = min(j.started for j in jobs)
    last_end = max(j.completed for j in jobs)
    wall = (last_end - first_start).total_seconds()
    run_total = sum(j.seconds for j in jobs)
    queued_total = sum(j.queued for j in jobs)
    out = [
        "## Wall time",
        "",
        f"- First job started to last job finished: **{fmt(wall)}**",
        f"- {len(jobs)} jobs, {fmt(run_total)} of runner time, {fmt(queued_total)} spent queued for a runner",
        "",
        "## Critical path",
        "",
    ]

    rows = []
    for job in critical_path(jobs):
        rows.append(
            [
                job.name,
                fmt((job.started - first_start).total_seconds()),
                fmt(job.seconds),
                fmt(job.queued),
            ]
        )
    out += table(["job", "starts at", "runs for", "queued"], rows)

    out += ["", f"## Slowest {top} jobs", ""]
    rows = []
    for job in sorted(jobs, key=lambda j: j.seconds, reverse=True)[:top]:
        slowest = max(job.steps, key=lambda s: s.seconds, default=None)
        rows.append(
            [
                job.name,
                fmt(job.seconds),
                fmt(job.queued),
                f"{slowest.name} ({fmt(slowest.seconds)})" if slowest else "",
            ]
        )
    out += table(["job", "runs for", "queued", "slowest step"], rows)

    by_step: dict[str, list[float]] = defaultdict(list)
    for job in jobs:
        for step in job.steps:
            by_step[step.name].append(step.seconds)
    out += ["", f"## Slowest {top} steps by name, summed over all jobs", ""]
    rows = []
    for name, times in sorted(by_step.items(), key=lambda kv: sum(kv[1]), reverse=True)[:top]:
        rows.append([name, str(len(times)), fmt(sum(times)), fmt(max(times))])
    out += table(["step", "runs", "total", "longest"], rows)
    return "\n".join(out) + "\n"


def flatten(raw: dict | list) -> list[dict]:
    """The job objects of an API page, of a list of pages (`gh api --slurp`), or of a bare list."""
    if isinstance(raw, dict):
        return raw["jobs"]
    return [job for item in raw for job in (item["jobs"] if "jobs" in item else [item])]


def fetch_jobs(run_id: str, repo: str) -> list[dict]:
    result = subprocess.run(
        [
            "gh",
            "api",
            "--paginate",
            "--slurp",
            f"repos/{repo}/actions/runs/{run_id}/jobs?per_page=100",
        ],
        check=True,
        capture_output=True,
        text=True,
    )
    return flatten(json.loads(result.stdout))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("run_id", nargs="?", help="the id of the run, from `gh run list`")
    parser.add_argument("--repo", default=DEFAULT_REPO)
    parser.add_argument("--top", type=int, default=DEFAULT_TOP)
    parser.add_argument("--jobs-json", help="read the jobs from this file instead of asking GitHub")
    args = parser.parse_args()

    if args.jobs_json:
        with open(args.jobs_json) as f:
            raw = json.load(f)
        raw = flatten(raw)
    elif args.run_id:
        raw = fetch_jobs(args.run_id, args.repo)
    else:
        parser.error("give a run id or --jobs-json")

    sys.stdout.write(report(parse_jobs(raw), args.top))
    return 0


if __name__ == "__main__":
    sys.exit(main())
