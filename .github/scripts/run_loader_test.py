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

import shlex
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

# The downloadAssets failure of #700, as the b1.7.3-ornithe job's log held it. Loom retries three
# times inside a second, so a transient hiccup outlives every one of them.
LOOM_DOWNLOAD_FAILURE_LOG = """\
> Task :downloadAssets FAILED
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':downloadAssets' (registered by plugin 'fabric-loom').
> net.fabricmc.loom.util.download.DownloadException: Failed to download

* Exception is:
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':downloadAssets' (registered by plugin 'fabric-loom').
	Suppressed: net.fabricmc.loom.util.download.DownloadException: Failed download after 3 attempts
"""

# The same task under ForgeGradle, which is the second occurrence recorded on #700: its advice is
# literally to run the task again.
FORGE_DOWNLOAD_FAILURE_LOG = """\
> Task :loaders:fake-loader:downloadAssets FAILED
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':loaders:fake-loader:downloadAssets'.
> Failed to get asset: minecraft/sounds/block/smoker/smoker4.ogg
  Some assets failed to download or validate, try running the task again.
"""

# A Gradle failure that is neither a download nor the code failing in a way the script knows: it has
# to fail the job rather than earn a retry.
UNRECOGNISED_FAILURE_LOG = """\
FAILURE: Build failed with an exception.

* What went wrong:
A problem occurred configuring project ':loaders:fake-loader'.
> Could not create task ':loaders:fake-loader:runClient'.
"""

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
# A test that needs the invocations to differ, such as the retry tests, names a directory here whose
# <n>.log and <n>.exit hold the nth invocation's log and exit code. The count file holds the number
# of the invocation this is, so the first one reads 1.
if [ -n "${FAKE_GRADLE_SEQUENCE:-}" ]; then
  attempt=$(cat "$FAKE_GRADLE_SEQUENCE/count" 2>/dev/null || echo 1)
  echo $((attempt + 1)) > "$FAKE_GRADLE_SEQUENCE/count"
  cat "$FAKE_GRADLE_SEQUENCE/$attempt.log"
  sleep "${FAKE_GRADLE_SLEEP:-2}"
  exit "$(cat "$FAKE_GRADLE_SEQUENCE/$attempt.exit")"
fi
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
        self,
        log: str = "",
        exit_code: int = 0,
        extra_env: dict[str, str] | None = None,
        phase: str = "run",
        sequence: list[tuple[str, int]] | None = None,
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
        if sequence is not None:
            # One log and exit code per Gradle invocation, in the order the script makes them, which
            # is how a test says that the first attempt fails and the retry passes.
            directory = self.tmp / "gradle-sequence"
            shutil.rmtree(directory, ignore_errors=True)
            directory.mkdir()
            for index, (attempt_log, attempt_exit) in enumerate(sequence, 1):
                (directory / f"{index}.log").write_text(attempt_log)
                (directory / f"{index}.exit").write_text(str(attempt_exit))
            (directory / "count").write_text("1")
            env["FAKE_GRADLE_SEQUENCE"] = str(directory)
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
                phase,
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

    # build-run does the build and the game in one Gradle invocation (#678). The log is the build's
    # output, then the line Gradle prints when it starts the runClient task, then the game's.
    BUILD_LOG = "> Task :loaders:fake-loader:compileJava\n> Task :loaders:fake-loader:jar\n"
    GAME_START = "> Task :loaders:fake-loader:runClient\n"

    def test_build_run_builds_and_runs_in_one_gradle_invocation(self):
        result = self.run_script(
            self.BUILD_LOG + self.GAME_START + PASSING_LOG, 0, phase="build-run"
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        # One call, with the build tasks first and the game last: a failure in the build stops
        # Gradle before runClient. The split phases are two calls (core publish aside).
        calls = result.gradle_args.splitlines()
        self.assertEqual(len([c for c in calls if "runClient" in c]), 1, calls)
        game_call = next(c for c in calls if "runClient" in c)
        self.assertIn(
            ":loaders:fake-loader:build :loaders:fake-loader:layerJar :loaders:fake-loader:runClient -x test",
            game_call,
        )
        self.assertIn("--no-daemon", game_call)

    def test_build_run_in_a_standalone_loader_finds_the_game_start(self):
        # A standalone loader has its own wrapper and its own build, where the task is :runClient
        # and not :loaders:<loader>:runClient. The script runs that wrapper from the loader's
        # directory, after publishing core through the root one.
        loader = self.tmp / "loaders" / "fake-loader"
        (loader / "root.gradle").write_text("// standalone\n")
        shutil.copy(self.tmp / "gradlew", loader / "gradlew")

        result = self.run_script(
            "> Task :compileJava\n> Task :layerJar\n> Task :runClient\n" + PASSING_LOG,
            0,
            phase="build-run",
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        calls = result.gradle_args.splitlines()
        self.assertTrue(calls[0].startswith(":core:publishMavenJava"), calls)
        self.assertEqual(len([c for c in calls if "runClient" in c]), 1, calls)
        self.assertIn("build layerJar runClient -x test", calls[-1])

    def test_the_run_phase_still_runs_only_the_game(self):
        result = self.run_script(PASSING_LOG, 0)

        game_call = next(c for c in result.gradle_args.splitlines() if "runClient" in c)
        self.assertNotIn(":build", game_call)
        self.assertNotIn("layerJar", game_call)

    def test_the_build_phase_still_builds_the_layer_without_a_game(self):
        # The layer job's phase: no display, no game, no log to poll.
        result = self.run_script("BUILD SUCCESSFUL\n", 0, {"DISPLAY": ""}, phase="build")

        self.assertEqual(result.returncode, 0, result.stderr)
        calls = result.gradle_args.splitlines()
        self.assertNotIn("runClient", result.gradle_args)
        self.assertIn(":loaders:fake-loader:build :loaders:fake-loader:layerJar -x test", calls[-1])

    def test_a_compile_error_in_build_run_is_a_failed_build_and_not_a_game_that_exited(self):
        # Gradle ends with 1 and the game never starts. The build output has the words the game
        # checks look for ("FAIL ", a crash line), because a compiler can print anything.
        log = (
            "> Task :loaders:fake-loader:compileJava FAILED\n"
            "src/main/java/Broken.java:3: error: ';' expected\n"
            "FAIL a compiler may print this\n"
            "FAILURE: Build failed with an exception.\n"
            "* What went wrong:\n"
            "Execution failed for task ':loaders:fake-loader:compileJava'.\n"
            "> Compilation failed; see the compiler output below.\n"
        )

        result = self.run_script(log, 1, phase="build-run")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Gradle failed to build fake-loader", result.stderr)
        self.assertIn("the game was never launched", result.stderr)
        self.assertIn("Broken.java:3: error: ';' expected", result.stderr)
        self.assertIn("Execution failed for task", result.stderr)
        self.assertNotIn("exited", result.stderr)
        self.assertNotIn("tests failed", result.stderr)
        self.assertNotIn("crashed", result.stderr)
        # A compile error is not a download failure, so the build runs once and the error is the
        # whole story of the job.
        self.assertEqual(len(result.gradle_args.splitlines()), 1)
        self.assertNotIn("failed on a download", result.stderr)

    def test_build_output_is_not_read_as_the_games(self):
        # A line of the build that looks like a failed test must not fail a run whose game passes,
        # and one that looks like a crash must not either.
        log = (
            self.BUILD_LOG
            + "Warning: FAIL is a word in this dependency\n"
            + "Game crashed! Crash report saved to: in a javadoc\n"
            + self.GAME_START
            + PASSING_LOG
        )

        result = self.run_script(log, 0, phase="build-run")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)

    def test_a_game_failure_after_the_build_is_still_a_test_failure(self):
        result = self.run_script(
            self.BUILD_LOG + self.GAME_START + FAILING_LOG, 1, phase="build-run"
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tests failed", result.stderr)
        self.assertIn("FAIL coverage/code/block_pos-Test.grug", result.stderr)
        self.assertNotIn("Gradle failed to build", result.stderr)

    def test_a_game_that_exits_after_the_build_is_an_exit_and_not_a_failed_build(self):
        result = self.run_script(
            self.BUILD_LOG + self.GAME_START + "[GRUG CI] Running 1 test...\n", 3, phase="build-run"
        )

        self.assertIn("build/game process exited (exit code 3)", result.stderr)
        self.assertNotIn("Gradle failed to build", result.stderr)

    # A download failure gets one more Gradle invocation, because the game never ran and the first
    # attempt's failure is a hiccup on an external host rather than a defect in the code (#700). The
    # cases below are the whole decision: every other way a job can end is not retried.
    def test_a_loom_asset_download_failure_is_retried_once(self):
        result = self.run_script(
            phase="build-run",
            sequence=[
                (LOOM_DOWNLOAD_FAILURE_LOG, 1),
                (self.BUILD_LOG + self.GAME_START + PASSING_LOG, 0),
            ],
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        self.assertIn("failed on a download", result.stderr)
        self.assertIn("DownloadException", result.stderr)
        self.assertEqual(len([c for c in result.gradle_args.splitlines() if "runClient" in c]), 2)
        # The failed attempt's log is kept beside the one the job reports from, so the hiccup is
        # still readable after a retry that passed.
        run_dir = self.tmp / "loaders" / "fake-loader" / "run"
        self.assertIn("DownloadException", (run_dir / "ci-run-loader.log.attempt-1").read_text())
        self.assertIn("ALL 1 TESTS PASSED", (run_dir / "ci-run-loader.log").read_text())

    def test_a_forge_asset_download_failure_is_retried_too(self):
        result = self.run_script(
            phase="build-run",
            sequence=[
                (FORGE_DOWNLOAD_FAILURE_LOG, 1),
                (self.BUILD_LOG + self.GAME_START + PASSING_LOG, 0),
            ],
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn(
            "Failed to get asset: minecraft/sounds/block/smoker/smoker4.ogg", result.stderr
        )
        self.assertEqual(len(result.gradle_args.splitlines()), 2)

    def test_a_download_failure_in_a_run_phase_is_retried(self):
        # A Windows loader job launches only the game, so a downloadAssets failure ends Gradle
        # before runClient and the script sees an exited process rather than a failed build. It is
        # still the same failure and still worth the retry.
        result = self.run_script(sequence=[(LOOM_DOWNLOAD_FAILURE_LOG, 1), (PASSING_LOG, 0)])

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)
        self.assertEqual(len(result.gradle_args.splitlines()), 2)

    def test_a_download_failure_in_the_build_phase_is_retried(self):
        result = self.run_script(
            phase="build",
            extra_env={"DISPLAY": ""},
            sequence=[(LOOM_DOWNLOAD_FAILURE_LOG, 1), ("BUILD SUCCESSFUL\n", 0)],
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(len(result.gradle_args.splitlines()), 2)
        self.assertIn(
            ":loaders:fake-loader:build :loaders:fake-loader:layerJar -x test",
            result.gradle_args.splitlines()[-1],
        )

    def test_a_download_failure_publishing_core_is_retried(self):
        # The four standalone loaders publish core with the root wrapper before their own build, so
        # that invocation is a Gradle path of its own and earns the same retry.
        loader = self.tmp / "loaders" / "fake-loader"
        (loader / "root.gradle").write_text("// standalone\n")
        shutil.copy(self.tmp / "gradlew", loader / "gradlew")

        result = self.run_script(
            phase="build-run",
            sequence=[
                (LOOM_DOWNLOAD_FAILURE_LOG, 1),
                ("BUILD SUCCESSFUL\n", 0),
                ("> Task :runClient\n" + PASSING_LOG, 0),
            ],
        )

        self.assertEqual(result.returncode, 0, result.stderr)
        calls = result.gradle_args.splitlines()
        self.assertTrue(calls[0].startswith(":core:publishMavenJava"), calls)
        self.assertTrue(calls[1].startswith(":core:publishMavenJava"), calls)
        self.assertIn("runClient", calls[2])

    def test_a_test_failure_is_not_retried(self):
        result = self.run_script(FAILING_LOG, 1)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("tests failed", result.stderr)
        self.assertEqual(len(result.gradle_args.splitlines()), 1)
        self.assertNotIn("failed on a download", result.stderr)

    def test_a_game_that_ran_is_not_retried_for_a_download_like_line_in_its_log(self):
        # The run phase reads its whole log as the game's, so a message that looks like the download
        # failure could be the game's own. Gradle's runClient line says the game started, and a game
        # that ran is never repeated.
        log = (
            "> Task :loaders:fake-loader:runClient\n"
            "Failed to get asset: something the game logged\n"
        )

        result = self.run_script(log, 3)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("build/game process exited (exit code 3)", result.stderr)
        self.assertEqual(len(result.gradle_args.splitlines()), 1)
        self.assertNotIn("failed on a download", result.stderr)

    def test_an_unrecognised_gradle_failure_is_not_retried(self):
        result = self.run_script(UNRECOGNISED_FAILURE_LOG, 1, phase="build-run")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Gradle failed to build fake-loader", result.stderr)
        self.assertEqual(len(result.gradle_args.splitlines()), 1)
        self.assertNotIn("failed on a download", result.stderr)

    def test_a_retry_that_fails_again_fails_the_job_with_its_own_output(self):
        compile_log = (
            "> Task :loaders:fake-loader:compileJava FAILED\n"
            "src/main/java/Broken.java:3: error: ';' expected\n"
            "FAILURE: Build failed with an exception.\n"
            "* What went wrong:\n"
            "Execution failed for task ':loaders:fake-loader:compileJava'.\n"
        )

        result = self.run_script(
            phase="build-run",
            sequence=[(LOOM_DOWNLOAD_FAILURE_LOG, 1), (compile_log, 1)],
        )

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Gradle failed to build fake-loader", result.stderr)
        self.assertIn("Broken.java:3: error: ';' expected", result.stderr)
        # Exactly one retry: a second failure is the job's failure, not another attempt.
        self.assertEqual(len([c for c in result.gradle_args.splitlines() if "runClient" in c]), 2)

    def write_slow_build(self, build_seconds: float, game_seconds: float) -> None:
        # A gradlew whose build takes build_seconds before it starts the game, and whose game then
        # prints the passing log after game_seconds.
        gradlew = self.tmp / "gradlew"
        gradlew.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "$FAKE_GRADLE_ARGS"\n'
            'case "$*" in *runClient*) ;; *) exit 0 ;; esac\n'
            f"printf %s {shlex.quote(self.BUILD_LOG)}\n"
            f"sleep {build_seconds}\n"
            f"printf %s {shlex.quote(self.GAME_START)}\n"
            f"sleep {game_seconds}\n"
            f"printf %s {shlex.quote(PASSING_LOG)}\n"
            "sleep 1\n"
        )

    def test_the_run_timeout_starts_when_the_game_does(self):
        # The build takes longer than the 3s limit and the game 1s, so a limit that started at the
        # launch would time out and one that starts at the game's start passes.
        self.write_slow_build(build_seconds=5, game_seconds=1)

        result = self.run_script("", 0, {"GRUG_CI_RUN_TIMEOUT": "3"}, phase="build-run")

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("passed all tests", result.stdout)

    def test_the_run_timeout_still_covers_the_game(self):
        self.write_slow_build(build_seconds=0, game_seconds=10)

        result = self.run_script("", 0, {"GRUG_CI_RUN_TIMEOUT": "3"}, phase="build-run")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "Timed out after 3s waiting for fake-loader to reach the title screen", result.stderr
        )

    def test_a_build_that_never_ends_times_out_as_a_build(self):
        gradlew = self.tmp / "gradlew"
        gradlew.write_text(
            "#!/usr/bin/env bash\n"
            'printf \'%s\\n\' "$*" >> "$FAKE_GRADLE_ARGS"\n'
            'case "$*" in *runClient*) echo "> Task :loaders:fake-loader:compileJava"; sleep 30 ;; esac\n'
        )

        result = self.run_script("", 0, {"GRUG_CI_BUILD_TIMEOUT": "2"}, phase="build-run")

        self.assertNotEqual(result.returncode, 0)
        self.assertIn(
            "Timed out after 2s building fake-loader, before its game started", result.stderr
        )

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
