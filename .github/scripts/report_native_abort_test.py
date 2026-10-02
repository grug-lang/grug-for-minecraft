#!/usr/bin/env python3
"""Tests .github/scripts/report-native-abort.sh.

A Rust panic in grug-rs aborts the JVM rather than failing a test, so the run reports "finished
with non-zero exit value 134" and nothing else. This script exists to turn that into a diagnosis,
which only works if it reads the panic out of a real log and says the things a reader needs to act
on it: the `panicked at` location, the panic message, which test was running, and the fact that
the panic is in grug-rs rather than here. A log without a panic has to produce no output at all,
or every other failure shape would be reported as an abort.
"""

import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "report-native-abort.sh"

# The panic as it appeared in the 1.20.6-forge job of PR #118, trimmed to the lines the script reads.
# Timestamps are kept, because the raw log carries them and a parser that only works without them
# would pass here and fail in CI.
ABORTED_LOG = """\
2026-10-02T11:01:34.1947470Z [GRUG CI] Executing test: coverage/code/chest_inventory-Test.grug
2026-10-02T11:01:34.2457598Z [GRUG CI] PASS coverage/code/chest_inventory-Test.grug
2026-10-02T11:01:34.3401752Z
2026-10-02T11:01:34.3402549Z thread '<unnamed>' (4275) panicked at gruggers/src/backend/bytecode.rs:1162:21:
2026-10-02T11:01:34.3403223Z RefCell already borrowed
2026-10-02T11:01:34.3403741Z note: run with `RUST_BACKTRACE=1` environment variable to display a backtrace
2026-10-02T11:01:34.3404692Z thread '<unnamed>' (4275) panicked at library/core/src/panicking.rs:225:5:
2026-10-02T11:01:34.3405401Z panic in a function that cannot unwind
2026-10-02T11:01:34.3409281Z thread caused non-unwinding panic. aborting.
2026-10-02T11:02:00.9657164Z > Process 'command '/usr/lib/jvm/temurin-21-jdk-amd64/bin/java'' finished with non-zero exit value 134
"""

# A run that failed an assertion the ordinary way. It has no panic and a different exit code, so
# the script must stay quiet: a test failure is reported by run-loader.sh itself.
FAILED_LOG = """\
[GRUG CI] Executing test: coverage/code/block_pos-Test.grug
[GRUG CI] FAIL coverage/code/block_pos-Test.grug
[GRUG CI] The origin should have been inside the world.
> Task :loaders:1.20.6-forge:runClient FAILED
"""

# An abort with nothing to read it from: the process died with 134 but its output never reached the
# log (a kill, or a log that was never flushed). The exit code alone still identifies the shape.
SILENT_ABORT_LOG = "[GRUG CI] Running 50 tests...\n"

# The same abort with a backtrace, which is the shape run-loader.sh now produces by exporting
# RUST_BACKTRACE. The frames are the ones grug-rs puts in a backtrace: the failing panic's stack
# runs through call_on_function and run, and the abort that follows it stops at the C entry point.
# The observed run had no grug frames at all, which is why the backtrace is exported now. The CI
# console prefixes every line with a timestamp, which Gradle's own log does not, so the frames are
# timestamped here too: a frame that keeps its timestamp is a frame nobody reads.
ABORTED_LOG_WITH_BACKTRACE = """\
2026-10-02T11:01:34.2457598Z [GRUG CI] PASS coverage/code/chest_inventory-Test.grug
2026-10-02T11:01:34.3402549Z thread '<unnamed>' (4275) panicked at gruggers/src/backend/bytecode.rs:1162:21:
2026-10-02T11:01:34.3403223Z RefCell already borrowed
2026-10-02T11:01:34.3405811Z stack backtrace:
2026-10-02T11:01:34.3408231Z    18:     0x7fbe701eb5ed - core::panicking::panic_nounwind_fmt
2026-10-02T11:01:34.3409311Z    19:     0x7fbe7020c62f - grug_call_export_fn
2026-10-02T11:01:34.3409511Z                                at /home/runner/work/grug-rs/gruggers/src/capi.rs:177:1
2026-10-02T11:01:34.3410231Z   120:     0x7fbe701eb000 - gruggers::backend::bytecode::BytecodeBackend::call_on_function
2026-10-02T11:01:34.3410431Z                                at gruggers/src/backend/bytecode.rs:1116:26
2026-10-02T11:01:34.3411131Z   124:     0x7fbe701eb100 - gruggers::backend::bytecode::BytecodeBackend::run
2026-10-02T11:01:34.3411331Z                                at gruggers/src/backend/bytecode.rs:639:9
2026-10-02T11:01:34.4449281Z thread caused non-unwinding panic. aborting.
"""


def run_report(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([str(SCRIPT), *args], capture_output=True, text=True, check=False)


class ReportNativeAbortTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    def write_log(self, name: str, contents: str) -> Path:
        path = self.tmp / name
        path.write_text(contents)
        return path

    def test_aborted_log_reports_the_panic(self):
        log = self.write_log("aborted.log", ABORTED_LOG)

        result = run_report(str(log), "134")

        self.assertEqual(result.returncode, 0, result.stderr)
        # The panic location and message are the whole diagnosis, so they are the assertions that
        # matter: the marker the issue asks for, the grug-rs file it points at, and the message.
        self.assertIn("panicked at gruggers/src/backend/bytecode.rs:1162:21", result.stdout)
        self.assertIn("RefCell already borrowed", result.stdout)
        # It has to name this as an abort rather than a test failure, or the next occurrence gets
        # filed as an infrastructure problem.
        self.assertIn("aborted inside the grug VM", result.stdout)
        self.assertIn("exited with code 134", result.stdout)
        # The thread that could not unwind is the line that proves the abort came from the panic.
        self.assertIn("thread caused non-unwinding panic", result.stdout)
        # Which test was running is what makes the occurrence actionable.
        self.assertIn(
            "Last test progress: [GRUG CI] PASS coverage/code/chest_inventory-Test.grug",
            result.stdout,
        )
        self.assertIn("grug-rs", result.stdout)
        self.assertIn("grug-for-minecraft#123", result.stdout)
        self.assertIn(str(log), result.stdout)

    def test_failed_test_log_is_not_reported_as_an_abort(self):
        log = self.write_log("failed.log", FAILED_LOG)

        result = run_report(str(log), "1")

        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")

    def test_exit_134_without_a_panic_is_still_an_abort(self):
        log = self.write_log("silent.log", SILENT_ABORT_LOG)

        result = run_report(str(log), "134")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("printed no Rust panic", result.stdout)
        # It has to say this is not the grug panic, or the next occurrence gets filed under #123.
        self.assertIn("not the", result.stdout)
        self.assertNotIn("panic message:", result.stdout)
        self.assertIn("Last test progress: [GRUG CI] Running 50 tests...", result.stdout)

    def test_log_without_progress_lines(self):
        log = self.write_log(
            "early.log",
            "thread '<unnamed>' panicked at gruggers/src/backend/bytecode.rs:9:1:\nboom\n",
        )

        result = run_report(str(log))

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("boom", result.stdout)
        self.assertIn("predates the test run", result.stdout)
        # No exit code was passed, so the report must not invent one.
        self.assertNotIn("exited with code", result.stdout)

    def test_the_bare_words_without_a_rust_location_are_not_an_abort(self):
        # A mod (or a game crash report quoting one) can print the phrase without a Rust panic
        # location, and that must not be classified as an abort.
        log = self.write_log(
            "prose.log",
            "[GRUG CI] Running 1 test...\nthe log said a mod panicked at the wrong time\n",
        )

        result = run_report(str(log), "1")

        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")

    def test_missing_log_file_is_not_an_abort(self):
        result = run_report(str(self.tmp / "does-not-exist.log"), "134")

        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout, "")

    def test_grug_frames_from_the_backtrace_are_reported(self):
        log = self.write_log("backtrace.log", ABORTED_LOG_WITH_BACKTRACE)

        result = run_report(str(log), "134")

        self.assertEqual(result.returncode, 0, result.stderr)
        # The frames are the part that says which grug code path reached the borrow, which is what
        # the report exists to hand to whoever fixes grug-rs.
        self.assertIn(
            "gruggers::backend::bytecode::BytecodeBackend::call_on_function", result.stdout
        )
        self.assertIn("grug_call_export_fn", result.stdout)
        # The frame index and address are noise once the symbol is known.
        self.assertNotIn("0x7fbe701eb000", result.stdout)
        # The "at <file>:<line>" continuation lines repeat the path, so they are left out too.
        self.assertNotIn("capi.rs:177", result.stdout)
        # A CI console line starts with a timestamp, which has to come off with the rest of the
        # noise or the frame is unreadable.
        self.assertNotIn("2026-10-02T11:01:34", result.stdout)
        self.assertIn("==>   grug_call_export_fn", result.stdout)

    def test_too_many_arguments_is_a_usage_error(self):
        result = run_report("a", "b", "c")

        self.assertEqual(result.returncode, 2)
        self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    if not SCRIPT.exists():
        print(f"missing {SCRIPT}", file=sys.stderr)
        sys.exit(1)
    unittest.main()
