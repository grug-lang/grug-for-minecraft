#!/usr/bin/env python3
"""Tests parallel_test_runner.py: the pool, the first-failure stop and what a failure shows.

The step this module speeds up is only trustworthy if a failing suite still fails the job and still
says which suite it was, so the failure cases here are the point rather than the parallelism ones.
"""

import contextlib
import io
import shutil
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

import parallel_test_runner  # noqa: E402


def python(code: str, *args: str) -> list[str]:
    return [sys.executable, "-c", code, *args]


class RunTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def run_commands(self, commands, jobs=1):
        out = io.StringIO()
        status = parallel_test_runner.run(commands, jobs, stream=out)
        return status, out.getvalue()

    def test_a_passing_command_is_one_line(self):
        status, out = self.run_commands([("quiet", python("print('noise')"))])

        self.assertEqual(status, 0, out)
        self.assertIn("==> quiet: passed", out)
        # The output of a passing suite is not the job's log: only a result line is.
        self.assertNotIn("noise", out)

    def test_a_failing_command_names_itself_and_shows_its_output(self):
        status, out = self.run_commands(
            [
                (
                    "broken",
                    python(
                        "import sys; print('stdout line'); print('stderr line', file=sys.stderr); sys.exit(3)"
                    ),
                )
            ]
        )

        self.assertEqual(status, 1)
        self.assertIn("==> broken: FAILED", out)
        self.assertIn("exit code 3", out)
        # The whole output, both streams, under the name of the suite that wrote it.
        self.assertIn("stdout line", out)
        self.assertIn("stderr line", out)
        self.assertIn("----- output of broken -----", out)

    def test_nothing_starts_after_the_first_failure(self):
        marker = self.tmp / "second-ran"

        status, out = self.run_commands(
            [
                ("first", python("import sys; sys.exit(1)")),
                ("second", python(f"open({str(marker)!r}, 'w').close()")),
            ],
            jobs=1,
        )

        self.assertEqual(status, 1, out)
        # The runner fails the step rather than running the rest of the queue.
        self.assertFalse(marker.exists())
        self.assertIn("1 did not run because an earlier one failed", out)

    def test_commands_run_side_by_side(self):
        # Each command waits for the other's file, so this only passes when both run at once. Serial,
        # the first waits its whole 5 s and fails.
        first, second = self.tmp / "first", self.tmp / "second"
        wait = (
            "import pathlib, sys, time\n"
            "mine, other = pathlib.Path(sys.argv[1]), pathlib.Path(sys.argv[2])\n"
            "mine.touch()\n"
            "for _ in range(50):\n"
            "    if other.exists(): sys.exit(0)\n"
            "    time.sleep(0.1)\n"
            "sys.exit(1)\n"
        )

        status, out = self.run_commands(
            [
                ("first", python(wait, str(first), str(second))),
                ("second", python(wait, str(second), str(first))),
            ],
            jobs=2,
        )

        self.assertEqual(status, 0, out)

    def test_no_more_than_the_given_number_run_at_once(self):
        trail = self.tmp / "trail"
        step = (
            "import sys, time\n"
            f"path = {str(trail)!r}\n"
            "with open(path, 'a') as f:\n"
            "    f.write('+')\n"
            "time.sleep(0.3)\n"
            "with open(path, 'a') as f:\n"
            "    f.write('-')\n"
        )

        status, out = self.run_commands([(str(n), python(step)) for n in range(4)], jobs=2)

        self.assertEqual(status, 0, out)
        marks = trail.read_text()
        self.assertEqual(sorted(marks), ["+", "+", "+", "+", "-", "-", "-", "-"], marks)
        depth = 0
        for mark in marks:
            depth += 1 if mark == "+" else -1
            self.assertLessEqual(depth, 2, marks)
        self.assertEqual(depth, 0, marks)

    def test_a_command_that_cannot_start_fails_the_step(self):
        status, out = self.run_commands([("missing", [str(self.tmp / "no-such-program")])])

        self.assertEqual(status, 1, out)
        self.assertIn("==> missing: FAILED", out)
        self.assertIn("exit code 127", out)


class MainTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def script(self, name: str, body: str) -> str:
        path = self.tmp / name
        path.write_text(f"#!/usr/bin/env python3\n{body}\n")
        return str(path)

    def run_main(self, argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = parallel_test_runner.main(argv)
        return status, out.getvalue()

    def test_scripts_on_the_command_line_are_run_as_processes(self):
        first = self.script("first.py", "open(%r, 'w').close()" % str(self.tmp / "one"))
        second = self.script("second.py", "open(%r, 'w').close()" % str(self.tmp / "two"))

        status, out = self.run_main(["--jobs", "2", first, second])

        self.assertEqual(status, 0, out)
        self.assertTrue((self.tmp / "one").exists())
        self.assertTrue((self.tmp / "two").exists())
        self.assertIn("all 2 passed", out)

    def test_a_failing_script_fails_main(self):
        status, out = self.run_main([self.script("broken.py", "raise SystemExit(4)")])

        self.assertEqual(status, 1, out)
        self.assertIn("FAILED", out)

    def test_a_script_that_is_not_there_is_a_usage_error(self):
        status, out = self.run_main([str(self.tmp / "nowhere.py")])

        self.assertEqual(status, 2, out)

    def test_no_script_is_a_usage_error(self):
        self.assertEqual(self.run_main([])[0], 2)


class JobsOptionTest(unittest.TestCase):
    def test_jobs_is_taken_off_the_front(self):
        self.assertEqual(parallel_test_runner.parse_jobs(["--jobs", "3", "a.py"]), (3, ["a.py"]))
        self.assertEqual(parallel_test_runner.parse_jobs(["--jobs=3", "a.py"]), (3, ["a.py"]))
        self.assertEqual(
            parallel_test_runner.parse_jobs(["a.py"]),
            (parallel_test_runner.jobs_from_environment(), ["a.py"]),
        )

    def test_a_job_count_that_is_not_a_whole_number_is_refused(self):
        for value in ("0", "-1", "two"):
            with self.subTest(value=value):
                with self.assertRaises(SystemExit):
                    parallel_test_runner.parse_jobs(["--jobs", value, "a.py"])


if __name__ == "__main__":
    unittest.main()
