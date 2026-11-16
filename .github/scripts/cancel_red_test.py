#!/usr/bin/env python3
"""Tests cancel-red.py: a proof dispatch is cancelled at its first non-exempt failed job (#1343)."""

import importlib.util
import re
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("cancel_red", HERE / "cancel-red.py")
cancel_red = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(cancel_red)

OWN = "cancel red"


def job(name, status="completed", conclusion="success", done="2026-11-15T10:00:00Z"):
    return {"name": name, "status": status, "conclusion": conclusion, "completed_at": done}


class FailingJobTest(unittest.TestCase):
    def test_no_failure_is_none(self):
        self.assertIsNone(
            cancel_red.failing_job([job("a"), job("b", "in_progress", None)], OWN, [])
        )

    def test_first_failure_wins(self):
        jobs = [
            job("late", conclusion="failure", done="2026-11-15T10:05:00Z"),
            job("early", conclusion="failure", done="2026-11-15T10:01:00Z"),
        ]
        self.assertEqual(cancel_red.failing_job(jobs, OWN, [])["name"], "early")

    def test_own_job_never_counts(self):
        self.assertIsNone(cancel_red.failing_job([job(OWN, conclusion="failure")], OWN, []))

    def test_cancelled_and_skipped_are_not_failures(self):
        jobs = [job("a", conclusion="cancelled"), job("b", conclusion="skipped")]
        self.assertIsNone(cancel_red.failing_job(jobs, OWN, []))

    def test_exempt_flake_is_ignored_but_others_count(self):
        jobs = [
            job("build (1.20.6-forge)", conclusion="failure"),
            job("core", conclusion="failure"),
        ]
        self.assertEqual(cancel_red.failing_job(jobs, OWN, [r"^build \("])["name"], "core")
        self.assertIsNone(cancel_red.failing_job(jobs[:1], OWN, [r"^build \("]))


class WorkflowExemptTest(unittest.TestCase):
    """The exemptions build.yml really passes (#1385)."""

    @staticmethod
    def exempt():
        text = (HERE.parent / "workflows" / "build.yml").read_text()
        line = next(l for l in text.splitlines() if "cancel-red.py" in l and "--exempt" in l)
        return re.findall(r"--exempt '([^']*)'", line)

    def test_windows_boot_flake_does_not_cancel(self):
        for name in ("1.20.6-forge (Windows)", "1.20.6-forge installed (Windows)"):
            jobs = [job(name, conclusion="failure")]
            self.assertIsNone(cancel_red.failing_job(jobs, OWN, self.exempt()), name)

    def test_real_failures_still_cancel(self):
        for name in ("adapter.dll (Windows)", "core", "1.20.6-forge", "1.7.10-forge dedicated"):
            jobs = [job(name, conclusion="failure")]
            self.assertIsNotNone(cancel_red.failing_job(jobs, OWN, self.exempt()), name)

    def test_without_a_layer_stays_exempt(self):
        jobs = [job("1.7.10-forge without a layer", conclusion="failure")]
        self.assertIsNone(cancel_red.failing_job(jobs, OWN, self.exempt()))


class AllDoneTest(unittest.TestCase):
    def test_waits_for_running_jobs(self):
        self.assertFalse(cancel_red.all_done([job("a"), job("b", "queued", None)], OWN))

    def test_own_job_is_not_waited_on(self):
        self.assertTrue(cancel_red.all_done([job("a"), job(OWN, "in_progress", None)], OWN))

    def test_empty_list_is_not_done(self):
        self.assertFalse(cancel_red.all_done([], OWN))
        self.assertFalse(cancel_red.all_done([job(OWN, "in_progress", None)], OWN))


if __name__ == "__main__":
    unittest.main()
