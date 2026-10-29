#!/usr/bin/env python3
"""Tests how .github/scripts/run-loader.sh classifies the ways a run can end.

A Rust panic in grug-rs aborts the JVM, which takes the runClient process down with it, so the
game process exiting is not on its own a diagnosis: the same "the process exited" line covers a
grug-rs abort, an infrastructure crash and a plain test failure. run-loader.sh therefore reads the
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

# A normal test failure whose run then died inside the VM during shutdown. The loop sees the FAIL
# line first, so the classification stays a test failure, but the panic still has to be surfaced.
FAIL_THEN_PANIC_LOG = (
    FAILING_LOG
    + """\
thread '<unnamed>' (4275) panicked at gruggers/src/backend/bytecode.rs:1162:21:
RefCell already borrowed
thread caused non-unwinding panic. aborting.
"""
)

# The log is written after a short delay and the process lingers, because the run script polls once
# a second and classifies on the first poll that matches. A write that landed between a poll's own
# greps could show the panic to the abort check while the FAIL check still saw an empty log, which
# is a race the classification must not depend on; the delay keeps every write between two polls.
# The process lingers because a fake that exited instantly could win the poll race, which is the
# classification under test.
# Every invocation appends its arguments to FAKE_GRADLE_ARGS, so a test can assert what the script
# told Gradle to build with.
GRADLE = """\
#!/usr/bin/env bash
printf '%s\\n' "$*" >> "$FAKE_GRADLE_ARGS"
sleep "${FAKE_GRADLE_START_DELAY:-0.5}"
# A test that needs the log written at an exact point in the script's poll names a file here, and the
# fake game writes nothing until that file exists.
while [ -n "${FAKE_GRADLE_WAIT_FOR:-}" ] && [ ! -e "$FAKE_GRADLE_WAIT_FOR" ]; do sleep 0.05; done
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

    def run_script(
        self, log: str, exit_code: int, extra_env: dict[str, str] | None = None
    ) -> subprocess.CompletedProcess:
        log_file = self.tmp / "gradle.log"
        log_file.write_text(log)

        env = {
            # The fake gradlew replays a log instead of launching a game, and DISPLAY only has to
            # be non-empty: nothing here reaches a window.
            "FAKE_GRADLE_LOG": str(log_file),
            "FAKE_GRADLE_EXIT": str(exit_code),
            "FAKE_GRADLE_ARGS": str(self.tmp / "gradle-args.txt"),
            "DISPLAY": ":99",
            "HOME": str(self.tmp),
            "PATH": "/usr/bin:/bin:/usr/local/bin",
            "GRUG_CI_RUN_TIMEOUT": "30",
        }
        env.update(extra_env or {})
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
        # Absent rather than empty when the script never got as far as Gradle, which is itself
        # something a test asserts.
        args_file = self.tmp / "gradle-args.txt"
        result.gradle_args = args_file.read_text() if args_file.exists() else ""
        return result

    def test_abort_is_reported_as_a_grug_panic_not_a_plain_exit(self):
        # Gradle exits 1 when the game aborts, so 1 is what the script waits on. The game's own 134
        # is in the log, and that is the code the report has to show.
        result = self.run_script(PANIC_LOG, 1)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("aborted inside grug-rs", result.stderr)
        self.assertIn("panicked at gruggers/src/backend/bytecode.rs:1162:21", result.stderr)
        self.assertIn("RefCell already borrowed", result.stderr)
        # An abort report with no code in it reads like any other dead process, and one with
        # Gradle's 1 would name the wrong process.
        self.assertIn("exited with code 134", result.stderr)
        # The exit-status line is what an unclassified abort degrades into, so its absence is the
        # point: the run must not be reported as a bare exit.
        self.assertNotIn("build/game process exited", result.stderr)
        # The panic belongs in the job summary, not only in the raw log.
        self.assertIn("aborted inside grug-rs", result.summary)
        self.assertIn("RefCell already borrowed", result.summary)

    def test_plain_exit_still_reports_the_exit_code(self):
        result = self.run_script("[GRUG CI] Running 1 test...\n", 3)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build/game process exited (exit code 3)", result.stderr)
        # No panic in the log, so nothing should claim there was one.
        self.assertNotIn("inside grug-rs", result.stderr)
        self.assertEqual(result.summary, "")

    def test_boot_only_succeeds_once_grug_has_loaded_without_the_tests(self):
        boot = "[GRUG CI] GRUG LOADED\n"

        result = self.run_script(boot, 1, {"GRUG_CI_BOOT_ONLY": "1"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("loaded grug. The tests were not run", result.stdout)
        self.assertNotIn("passed all tests", result.stdout)
        # Without the setting the same log is a game that exited before the tests passed.
        self.assertNotEqual(self.run_script(boot, 1).returncode, 0)

    def test_boot_only_game_that_exits_between_the_log_reads_and_the_liveness_check_succeeds(self):
        # A boot-only game prints GRUG LOADED and exits at once, so it can do both between the
        # poll's last read of the log and its liveness test (#597). The grep shim creates a file
        # once the poll has made its last read (the panic marker's) and then holds the poll for
        # 1.5s, and the fake game writes its line and exits only after that file exists. The
        # script must not report the exit it then sees as a bare exit.
        signal = self.tmp / "last-read-done"
        shim = self.tmp / "shim"
        shim.mkdir()
        grep = shim / "grep"
        grep.write_text(
            "#!/usr/bin/env bash\n"
            '/usr/bin/grep "$@"; status=$?\n'
            f'case "$*" in *"panicked at"*) touch "{signal}"; sleep 1.5 ;; esac\n'
            "exit $status\n"
        )
        grep.chmod(0o755)

        result = self.run_script(
            "[GRUG CI] GRUG LOADED\n",
            0,
            {
                "GRUG_CI_BOOT_ONLY": "1",
                "FAKE_GRADLE_SLEEP": "0",
                "FAKE_GRADLE_WAIT_FOR": str(signal),
                "PATH": f"{shim}:/usr/bin:/bin:/usr/local/bin",
            },
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("build/game process exited", result.stderr)

    def test_boot_only_still_fails_a_crash_before_grug_has_loaded(self):
        crash = "Broken grug invariant: no native\n"

        result = self.run_script(crash, 1, {"GRUG_CI_BOOT_ONLY": "1"})

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("crashed BEFORE reaching the title screen", result.stderr)

    def test_a_failing_test_is_reported_with_its_own_output(self):
        result = self.run_script(FAILING_LOG, 1)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tests failed", result.stderr)
        self.assertIn("FAIL coverage/code/block_pos-Test.grug", result.stderr)
        self.assertNotIn("inside grug-rs", result.stderr)

    def test_a_panic_after_a_failed_test_is_still_reported(self):
        result = self.run_script(FAIL_THEN_PANIC_LOG, 1)

        self.assertNotEqual(result.returncode, 0)
        # The classification stays a test failure, which is what the FAIL line deserves...
        self.assertIn("tests failed", result.stderr)
        self.assertIn("FAIL coverage/code/block_pos-Test.grug", result.stderr)
        # ...but the panic must not go unread just because the FAIL line came first.
        self.assertIn("panicked at gruggers/src/backend/bytecode.rs:1162:21", result.stderr)
        self.assertIn("RefCell already borrowed", result.stderr)
        self.assertIn("aborted inside grug-rs", result.summary)

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

    def test_a_prebuilt_grug_rs_library_is_handed_to_gradle(self):
        # CI builds the static library once and passes it down, which is the only way the loader jobs
        # skip cloneGrugRs and buildGrugRs. Gradle has to be told, so the property is what the script
        # is checked for.
        prebuilt = self.tmp / "libgruggers.a"
        prebuilt.write_bytes(b"not a real static library")

        result = self.run_script(PASSING_LOG, 0, {"GRUG_PREBUILT_GRUG_RS": str(prebuilt)})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(f"-Pgrug.prebuiltGrugRs={prebuilt}", result.gradle_args)

    def test_no_prebuilt_library_means_gradle_builds_it(self):
        # Left unset, which is every local run: the property must be absent rather than empty, or
        # Gradle would stage nothing and link against a missing file.
        result = self.run_script(PASSING_LOG, 0)

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("grug.prebuiltGrugRs", result.gradle_args)

    def test_a_prebuilt_library_that_is_not_there_fails_the_run(self):
        # An artifact download that silently produced nothing would otherwise fall back to building
        # grug-rs from scratch, which costs every job the time the sharing was meant to save and says
        # nothing about why.
        result = self.run_script(
            PASSING_LOG,
            0,
            {"GRUG_PREBUILT_GRUG_RS": str(self.tmp / "nowhere" / "libgruggers.a")},
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("GRUG_PREBUILT_GRUG_RS", result.stderr)
        self.assertIn("which is not a file", result.stderr)
        # It has to fail before the game is launched, or the run would report a pass that never used
        # the library it was handed.
        self.assertEqual(result.gradle_args, "")

    def add_mod(self, name: str, tests: int) -> None:
        code = self.tmp / "mods" / name / "code"
        code.mkdir(parents=True)
        for index in range(tests):
            (code / f"t{index}-Test.grug").write_text("export run() {}\n")

    def selected_log(self, count: int) -> str:
        return f"[GRUG CI] Running {count} tests...\n[GRUG CI] ALL {count} TESTS PASSED\n"

    def test_selecting_mods_counts_only_their_tests(self):
        # fake has 1 test, other 2 and empty none: the full run is 3 and the run of other is 2, which
        # is the number the game prints, so a count that ignored the selection would time out.
        self.add_mod("other", 2)
        self.add_mod("empty", 0)

        result = self.run_script(self.selected_log(2), 0, {"GRUG_CI_MODS": "other"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        self.assertIn("(2 tests)", result.stdout)

    def test_an_unset_empty_or_blank_selection_counts_every_test(self):
        self.add_mod("other", 2)

        for extra in ({}, {"GRUG_CI_MODS": ""}, {"GRUG_CI_MODS": " \t "}):
            result = self.run_script(self.selected_log(3), 0, extra)

            self.assertEqual(result.returncode, 0, (extra, result.stderr))
            self.assertNotIn("GRUG_CI_MODS selects", result.stdout)

    def test_a_selection_is_trimmed_and_counts_a_repeated_mod_once(self):
        self.add_mod("other", 2)

        result = self.run_script(self.selected_log(3), 0, {"GRUG_CI_MODS": " other , fake,other"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("(3 tests)", result.stdout)

    def test_a_selected_mod_without_tests_expects_none(self):
        self.add_mod("empty", 0)

        result = self.run_script(self.selected_log(0), 0, {"GRUG_CI_MODS": "empty"})

        self.assertEqual(result.returncode, 0, result.stderr)

    def test_the_selection_reaches_the_game_through_the_environment(self):
        # The game is a child of the script, so an exported variable is all it takes; a fake gradlew
        # that prints what it sees stands in for the game.
        self.add_mod("other", 2)
        gradlew = self.tmp / "gradlew"
        gradlew.write_text(
            GRADLE.replace(
                'cat "$FAKE_GRADLE_LOG"',
                'cat "$FAKE_GRADLE_LOG"; echo "mods=[${GRUG_CI_MODS-unset}]"',
            )
        )

        result = self.run_script(self.selected_log(2), 0, {"GRUG_CI_MODS": "other"})

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("mods=[other]", result.stdout)

    def test_a_selected_name_that_is_no_mod_fails_before_the_game_starts(self):
        # The full count is 1 and the log below would pass it: only the early refusal says the typo
        # ran nothing.
        for value in ("typo", "fake,typo", "Fake", "fak"):
            result = self.run_script(PASSING_LOG, 0, {"GRUG_CI_MODS": value})

            self.assertEqual(result.returncode, 2, (value, result.stderr))
            self.assertIn("GRUG_CI_MODS names the mod", result.stderr)
            self.assertIn("The mods are: fake", result.stderr)
            self.assertEqual(result.gradle_args, "")
            (self.tmp / "gradle-args.txt").unlink(missing_ok=True)

    def test_an_empty_or_path_like_entry_fails_before_the_game_starts(self):
        for value, message in (
            ("fake,", "empty entry"),
            (",fake", "empty entry"),
            ("fake,,fake", "empty entry"),
            (",", "empty entry"),
            ("fake/code", "not a mod directory name"),
            ("..", "not a mod directory name"),
            (".", "not a mod directory name"),
        ):
            result = self.run_script(PASSING_LOG, 0, {"GRUG_CI_MODS": value})

            self.assertEqual(result.returncode, 2, (value, result.stderr))
            self.assertIn(message, result.stderr)
            self.assertEqual(result.gradle_args, "")


if __name__ == "__main__":
    unittest.main()
