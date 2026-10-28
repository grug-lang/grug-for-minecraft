#!/usr/bin/env python3
"""Tests ci_timing.py: the critical path, the queue and step sums, and runs with jobs that never ran."""

import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from ci_timing import critical_path, flatten, fmt, parse_jobs, report  # noqa: E402


def at(seconds: int) -> str:
    return f"2026-10-27T21:{seconds // 60:02d}:{seconds % 60:02d}Z"


def job(name, created, start, end, steps=()):
    return {
        "name": name,
        "created_at": at(created),
        "started_at": at(start),
        "completed_at": at(end),
        "steps": [{"name": n, "started_at": at(a), "completed_at": at(b)} for n, a, b in steps],
    }


class CiTimingTest(unittest.TestCase):
    def test_critical_path_follows_the_job_that_ended_just_before_the_next_started(self):
        jobs = parse_jobs(
            [
                job("grug-rs", 0, 0, 10),
                job("fast build", 0, 10, 30),
                job("slow build", 0, 10, 60),
                job("installed", 0, 60, 90),
            ]
        )
        self.assertEqual(
            [j.name for j in critical_path(jobs)], ["grug-rs", "slow build", "installed"]
        )

    def test_a_single_job_is_its_own_path(self):
        jobs = parse_jobs([job("only", 0, 0, 5)])
        self.assertEqual([j.name for j in critical_path(jobs)], ["only"])

    def test_jobs_that_never_started_are_dropped(self):
        skipped = {"name": "skipped", "created_at": at(0), "started_at": None, "completed_at": None}
        self.assertEqual([j.name for j in parse_jobs([skipped, job("ran", 0, 0, 5)])], ["ran"])

    def test_a_skipped_step_is_dropped(self):
        raw = job("a", 0, 0, 20, [("setup", 0, 5)])
        raw["steps"].append({"name": "skipped", "started_at": None, "completed_at": None})
        self.assertEqual([s.name for s in parse_jobs([raw])[0].steps], ["setup"])

    def test_queue_time_is_start_minus_creation(self):
        self.assertEqual(parse_jobs([job("a", 3, 10, 20)])[0].queued, 7)

    def test_a_job_without_a_creation_time_was_not_queued(self):
        raw = job("a", 0, 10, 20)
        del raw["created_at"]
        self.assertEqual(parse_jobs([raw])[0].queued, 0)

    def test_report_sums_a_step_over_every_job(self):
        jobs = parse_jobs(
            [
                job("a", 0, 0, 100, [("Set up JDK", 0, 20), ("Build", 20, 100)]),
                job("b", 0, 0, 90, [("Set up JDK", 0, 30), ("Build", 30, 90)]),
            ]
        )
        text = report(jobs)
        self.assertIn("| Set up JDK | 2 | 50s | 30s |", text)
        self.assertIn("| Build | 2 | 2m20s | 1m20s |", text)
        self.assertIn("**1m40s**", text)

    def test_report_names_the_slowest_step_of_each_job(self):
        text = report(
            parse_jobs([job("a", 0, 0, 100, [("Set up JDK", 0, 20), ("Build", 20, 100)])])
        )
        self.assertIn("| a | 1m40s | 0s | Build (1m20s) |", text)

    def test_report_of_nothing_says_so(self):
        self.assertIn("nothing to report", report([]))

    def test_flatten_accepts_a_page_a_list_of_pages_and_a_bare_list(self):
        a, b = job("a", 0, 0, 5), job("b", 0, 0, 5)
        self.assertEqual(flatten({"jobs": [a]}), [a])
        self.assertEqual(flatten([{"jobs": [a]}, {"jobs": [b]}]), [a, b])
        self.assertEqual(flatten([a, b]), [a, b])

    def test_fmt(self):
        self.assertEqual((fmt(9.4), fmt(60), fmt(125)), ("9s", "1m00s", "2m05s"))


if __name__ == "__main__":
    unittest.main()
