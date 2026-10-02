#!/usr/bin/env python3
"""Tests how .github/scripts/run-loader.sh classifies the ways a run can end.

A Rust panic in grug-rs aborts the JVM, which takes the runClient process down with it, so the
game process exiting is not on its own a diagnosis: the same "the process exited" line covers a
grug VM abort, an infrastructure crash and a plain test failure. run-loader.sh therefore reads the
log as well as the exit status, and it has to keep doing both, because the failure mode this guards
is one where the classification is the only thing anyone sees.

The harness fakes the repository the script runs from: the real script and reporter, a gradlew that
replays a canned log and exits with a chosen code, and just enough of a loader for the script to
get as far as launching it.
"""

import shutil
import subprocess
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent.parent

# The panic from the 1.20.6-forge job of PR #118, as Gradle's log holds it (no timestamps).
PANIC_LOG = """\
[GRUG CI] Executing test: coverage/code/chest_inventory-Test.grug
[GRUG CI] PASS coverage/code/chest_inventory-Test.grug
thread '<unnamed>' (4275) panicked at gruggers/src/backend/bytecode.rs:1162:21:
RefCell already borrowed
thread '<unnamed>' (4275) panicked at library/core/src/panicking.rs:225:5:
panic in a function that cannot unwind
thread caused non-unwinding panic. aborting.
> Task :loaders:fake-loader:runClient FAILED
> Process 'command '/usr/lib/jvm/temurin-21-jdk-amd64/bin/java'' finished with non-zero exit value 134
"""

PASSING_LOG = """\
[GRUG CI] BOOT TO TITLE SCREEN SUCCESSFUL
[GRUG CI] Running 1 test...
[GRUG CI] ALL 1 TESTS PASSED
"""

FAILING_LOG = """\
[GRUG CI] Running 1 test...
[GRUG CI] FAIL coverage/code/block_pos-Test.grug
[GRUG CI] The origin should have been inside the world.
"""

# The log is written first and the process lingers, because the run script polls once a second and
# classifies on the first poll that matches. A fake that exited instantly could win that race and
# report every case as a bare exit, which is the classification under test.
GRADLE = """\
#!/usr/bin/env bash
cat "$FAKE_GRADLE_LOG"
sleep "${FAKE_GRADLE_SLEEP:-2}"
exit "${FAKE_GRADLE_EXIT:-0}"
"""


class RunLoaderTest(unittest.TestCase):
    """Runs the real script against a fake repository root."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        scripts = self.tmp / ".github" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("run-loader.sh", "report-native-abort.sh"):
            shutil.copy(SCRIPTS / name, scripts / name)
        (scripts / "report-native-abort.sh").chmod(0o755)

        # One test file, so the run script's expected-test count is 1 and the success message in
        # the fake log is the one it actually looks for.
        code = self.tmp / "mods" / "fake" / "code"
        code.mkdir(parents=True)
        (code / "fake-Test.grug").write_text("export run() {}\n")

        saves = self.tmp / "test-saves"
        saves.mkdir()
        with zipfile.ZipFile(saves / "fake.zip", "w") as archive:
            archive.writestr("level.dat", "not a real world")

        loader = self.tmp / "loaders" / "fake-loader"
        loader.mkdir(parents=True)
        (loader / "test-save.txt").write_text("fake.zip\n")

        gradlew = self.tmp / "gradlew"
        gradlew.write_text(GRADLE)
        gradlew.chmod(0o755)

    def run_script(self, log: str, exit_code: int) -> subprocess.CompletedProcess:
        log_file = self.tmp / "gradle.log"
        log_file.write_text(log)

        env = {
            # The fake gradlew replays a log instead of launching a game, and DISPLAY only has to
            # be non-empty: nothing here reaches a window.
            "FAKE_GRADLE_LOG": str(log_file),
            "FAKE_GRADLE_EXIT": str(exit_code),
            "DISPLAY": ":99",
            "HOME": str(self.tmp),
            "PATH": "/usr/bin:/bin:/usr/local/bin",
            "GRUG_CI_RUN_TIMEOUT": "30",
        }
        summary = self.tmp / "summary.md"
        # The script only appends when it has something to report, so start it as an empty file the
        # way GitHub does and read back whatever ended up there.
        summary.write_text("")
        env["GITHUB_STEP_SUMMARY"] = str(summary)

        result = subprocess.run(
            [
                str(self.tmp / ".github" / "scripts" / "run-loader.sh"),
                "fake-loader",
                "run",
            ],
            cwd=self.tmp,
            env=env,
            capture_output=True,
            text=True,
            check=False,
        )
        result.summary = summary.read_text()
        return result

    def test_abort_is_reported_as_a_grug_panic_not_a_plain_exit(self):
        result = self.run_script(PANIC_LOG, 134)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("aborted inside the grug VM", result.stderr)
        self.assertIn("panicked at gruggers/src/backend/bytecode.rs:1162:21", result.stderr)
        self.assertIn("RefCell already borrowed", result.stderr)
        # 134 is how both the game and Gradle report an abort, and the run has to pass it on: an
        # abort report with no code in it reads like any other dead process.
        self.assertIn("exited with code 134", result.stderr)
        # The exit-status line is what an unclassified abort degrades into, so its absence is the
        # point: the run must not be reported as a bare exit.
        self.assertNotIn("build/game process exited", result.stderr)
        # The panic belongs in the job summary, not only in the raw log.
        self.assertIn("aborted inside the grug VM", result.summary)
        self.assertIn("RefCell already borrowed", result.summary)

    def test_plain_exit_still_reports_the_exit_code(self):
        result = self.run_script("[GRUG CI] Running 1 test...\n", 3)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build/game process exited (exit code 3)", result.stderr)
        # No panic in the log, so nothing should claim there was one.
        self.assertNotIn("grug VM", result.stderr)
        self.assertEqual(result.summary, "")

    def test_a_failing_test_is_reported_with_its_own_output(self):
        result = self.run_script(FAILING_LOG, 1)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tests failed", result.stderr)
        self.assertIn("FAIL coverage/code/block_pos-Test.grug", result.stderr)
        self.assertNotIn("grug VM", result.stderr)

    def test_a_passing_run_succeeds_and_writes_no_summary(self):
        result = self.run_script(PASSING_LOG, 0)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        self.assertEqual(result.summary, "")

    def test_a_missing_loader_is_a_usage_error(self):
        result = subprocess.run(
            [str(self.tmp / ".github" / "scripts" / "run-loader.sh"), "no-such-loader", "run"],
            cwd=self.tmp,
            capture_output=True,
            text=True,
            check=False,
        )

        self.assertEqual(result.returncode, 2)
        self.assertIn("No such loader directory", result.stderr)


if __name__ == "__main__":
    unittest.main()
