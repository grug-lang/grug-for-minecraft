#!/usr/bin/env python3
"""Tests line_coverage.py: line counts and exclusions that need a written reason."""

import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "line_coverage", Path(__file__).resolve().parent / "line_coverage.py"
)
lc = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(lc)


def gcov(counts: dict, name="/x/smoketest.c") -> dict:
    return {
        "files": [
            {"file": name, "lines": [{"line_number": n, "count": c} for n, c in counts.items()]}
        ]
    }


class LineCoverageTest(unittest.TestCase):
    def test_counts_covered_and_missed_lines(self):
        total, covered, missed, problems = lc.measure(gcov({1: 3, 2: 0, 3: 1}), "a\nb\nc\n")
        self.assertEqual((total, covered, missed, problems), (3, 2, [2], []))

    def test_other_files_are_ignored(self):
        total, *_ = lc.measure(gcov({1: 0}, name="/usr/include/x.h"), "a\n")
        self.assertEqual(total, 0)

    def test_a_line_with_a_reason_is_excluded(self):
        src = "a\nb // SMOKETEST_EXCL: no test can reach this\nc\n"
        total, covered, missed, problems = lc.measure(gcov({1: 1, 2: 0, 3: 1}), src)
        self.assertEqual((total, covered, missed, problems), (2, 2, [], []))

    def test_a_range_with_a_reason_is_excluded(self):
        src = "a\n// SMOKETEST_EXCL_START: aborts the process\nb\nc\n// SMOKETEST_EXCL_STOP\nd\n"
        total, covered, missed, problems = lc.measure(gcov({1: 1, 3: 0, 4: 0, 6: 1}), src)
        self.assertEqual((total, covered, missed, problems), (2, 2, [], []))

    def test_a_marker_without_a_reason_is_a_problem_and_excludes_nothing_silently(self):
        for src in (
            "a // SMOKETEST_EXCL\n",
            "a // SMOKETEST_EXCL:   \n",
            "// SMOKETEST_EXCL_START\na\n// SMOKETEST_EXCL_STOP\n",
        ):
            with self.subTest(src=src):
                _, _, _, problems = lc.measure(gcov({1: 1}), src)
                self.assertTrue(problems)

    def test_a_marker_inside_a_string_is_no_marker(self):
        src = 'puts("// SMOKETEST_EXCL: not a comment");\n'
        total, covered, missed, problems = lc.measure(gcov({1: 0}), src)
        self.assertEqual((total, covered, missed, problems), (1, 0, [1], []))
        src = 'puts("x"); // SMOKETEST_EXCL: after a string\n'
        self.assertEqual(lc.measure(gcov({1: 0}), src)[0], 0)

    def test_unbalanced_ranges_are_problems(self):
        self.assertTrue(lc.excluded_lines("// SMOKETEST_EXCL_START: r\na\n")[1])
        self.assertTrue(lc.excluded_lines("a\n// SMOKETEST_EXCL_STOP\n")[1])
        self.assertTrue(
            lc.excluded_lines(
                "// SMOKETEST_EXCL_START: r\n// SMOKETEST_EXCL_START: r\n// SMOKETEST_EXCL_STOP\n"
            )[1]
        )

    def test_assert_flag_fails_below_full_coverage_only_when_asked(self):
        import json
        import subprocess
        import sys
        import tempfile

        with tempfile.TemporaryDirectory() as d:
            j, c = Path(d, "g.json"), Path(d, "smoketest.c")
            j.write_text(json.dumps(gcov({1: 1, 2: 0})))
            c.write_text("a\nb\n")
            script = str(Path(lc.__file__ or SPEC.origin))

            def run(*extra):
                return subprocess.run(
                    [sys.executable, script, str(j), str(c), *extra], capture_output=True, text=True
                )

            self.assertEqual(run().returncode, 0)
            self.assertIn("1/2", run().stdout)
            self.assertEqual(run("--assert").returncode, 1)
            c.write_text("a // SMOKETEST_EXCL\nb\n")
            loud = run()
            self.assertEqual(loud.returncode, 0)
            self.assertIn("EXCLUSION PROBLEM", loud.stdout)
            c.write_text("a\nb\n")
            j.write_text(json.dumps(gcov({1: 1, 2: 4})))
            self.assertEqual(run("--assert").returncode, 0)


if __name__ == "__main__":
    unittest.main()
