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

import yaml

sys.path.insert(0, str(Path(__file__).resolve().parent))

import parallel_test_runner  # noqa: E402

SCRIPTS = Path(__file__).resolve().parent
BUILD_YML = SCRIPTS.parent / "workflows" / "build.yml"


def python(code: str, *args: str) -> list[str]:
    return [sys.executable, "-c", code, *args]


def run_steps() -> str:
    """Every run: command of build.yml, as one text."""
    jobs = yaml.safe_load(BUILD_YML.read_text(encoding="utf-8"))["jobs"]
    return "\n".join(
        step["run"] for job in jobs.values() for step in job.get("steps", []) if "run" in step
    )


def runs_alone_files(directory: Path = SCRIPTS) -> list[Path]:
    """Every discovered test under a directory that declares RUNS_ALONE."""
    return [
        path
        for path in parallel_test_runner.tests_in(directory)
        if path.is_file() and parallel_test_runner.runs_alone_reason(path) is not None
    ]


def unwired(directory: Path, workflow: str) -> list[str]:
    """The declaring tests that no workflow run: command names, by their path under .github/scripts."""
    relative = [path.relative_to(directory) for path in runs_alone_files(directory)]
    return [str(path) for path in relative if f".github/scripts/{path.as_posix()}" not in workflow]


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


class DirectoryTest(unittest.TestCase):
    """A directory is expanded to its tests, and only a declaration keeps one out."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def script(self, name: str, body: str) -> str:
        path = self.tmp / name
        path.write_text(f"#!/usr/bin/env python3\n{body}\n")
        return str(path)

    def marker(self, name: str) -> Path:
        return self.tmp / name

    def touched(self, name: str) -> str:
        return f"open({str(self.marker(name))!r}, 'w').close()"

    def run_main(self, argv):
        out = io.StringIO()
        with contextlib.redirect_stdout(out):
            status = parallel_test_runner.main(argv)
        return status, out.getvalue()

    def test_a_directory_runs_its_tests_sorted_and_ignores_other_files(self):
        self.script("b_test.py", self.touched("b"))
        self.script("a_test.py", self.touched("a"))
        self.script("helper.py", self.touched("helper"))

        status, out = self.run_main(["--jobs", "1", str(self.tmp)])

        self.assertEqual(status, 0, out)
        self.assertTrue(self.marker("a").exists())
        self.assertTrue(self.marker("b").exists())
        self.assertFalse(self.marker("helper").exists(), "only *_test.py runs")
        self.assertLess(out.index("a_test.py"), out.index("b_test.py"), out)
        self.assertIn("all 2 passed", out)

    def test_a_test_that_runs_alone_is_skipped_with_its_reason(self):
        reason = "push only: it reads the jar an earlier step builds"
        self.script("alone_test.py", f"RUNS_ALONE = {reason!r}\n{self.touched('alone')}")
        self.script("runs_test.py", self.touched("runs"))

        status, out = self.run_main([str(self.tmp)])

        self.assertEqual(status, 0, out)
        self.assertFalse(self.marker("alone").exists())
        self.assertTrue(self.marker("runs").exists())
        self.assertIn(f"alone_test.py: skipped, runs alone: {reason}", out)
        self.assertIn("all 1 passed", out)

    def test_a_named_script_runs_even_when_it_declares_runs_alone(self):
        alone = self.script("alone_test.py", f'RUNS_ALONE = "push only"\n{self.touched("alone")}')

        status, out = self.run_main([alone])

        self.assertEqual(status, 0, out)
        self.assertTrue(self.marker("alone").exists())

    def test_a_runs_alone_without_a_reason_is_refused(self):
        for value in ('""', "''", "None", "0"):
            with self.subTest(value=value):
                self.script("hollow_test.py", f"RUNS_ALONE = {value}")
                with self.assertRaises(SystemExit) as raised:
                    self.run_main([str(self.tmp)])
                self.assertIn("RUNS_ALONE", str(raised.exception))

    def test_a_directory_with_no_test_is_a_usage_error(self):
        self.script("helper.py", "pass")

        self.assertEqual(self.run_main([str(self.tmp)])[0], 2)

    def test_the_scripts_directory_skips_the_tests_with_their_own_steps(self):
        out = io.StringIO()
        scripts = parallel_test_runner.expand(str(Path(__file__).resolve().parent), stream=out)
        names = {Path(script).name for script in scripts}

        # The two steps of build.yml that a pull request skips; each declares its reason.
        self.assertNotIn("grug_core_handoff_test.py", names)
        self.assertNotIn("universal_jar_test.py", names)
        self.assertIn("grug_core_handoff_test.py", out.getvalue())
        self.assertIn("universal_jar_test.py", out.getvalue())
        # The files this discovery exists for.
        self.assertIn("layer_selection_test.py", names)
        self.assertIn("maven_mirror_order_test.py", names)

    def test_a_test_in_a_subdirectory_runs(self):
        package = self.tmp / "pkg"
        package.mkdir()
        (package / "nested_test.py").write_text(
            f"#!/usr/bin/env python3\n{self.touched('nested')}\n"
        )

        status, out = self.run_main([str(self.tmp)])

        self.assertEqual(status, 0, out)
        self.assertTrue(self.marker("nested").exists())
        self.assertIn("pkg/nested_test.py: started", out)

    def test_a_directory_named_like_a_test_is_skipped_and_the_rest_run(self):
        (self.tmp / "odd_test.py").mkdir()
        self.script("real_test.py", self.touched("real"))

        status, out = self.run_main([str(self.tmp)])

        self.assertEqual(status, 0, out)
        self.assertTrue(self.marker("real").exists())
        self.assertIn("odd_test.py: skipped, not a file", out)
        self.assertIn("all 1 passed", out)


class RunsAloneWiringTest(unittest.TestCase):
    """A declaration must never silence a test: a run: step has to name the file that declares it."""

    def test_every_declared_test_is_named_by_a_workflow_run_step(self):
        missing = unwired(SCRIPTS, run_steps())
        self.assertEqual(
            [],
            missing,
            "these declare RUNS_ALONE but no run: step of build.yml names them, so nothing runs "
            f"them: {', '.join(missing)}",
        )

    def test_the_two_tests_with_their_own_steps_declare_it(self):
        declared = {path.name for path in runs_alone_files()}
        self.assertIn("grug_core_handoff_test.py", declared)
        self.assertIn("universal_jar_test.py", declared)

    def test_a_declaration_no_step_names_is_reported(self):
        scripts = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, scripts)
        (scripts / "orphan_test.py").write_text('RUNS_ALONE = "needs its own step"\n')

        self.assertEqual(["orphan_test.py"], unwired(scripts, run_steps()))


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
