#!/usr/bin/env python3
"""Tests how .github/scripts/smoke-launch.sh decides, against a fake launcher and a fake game.

#1335: a NeoForge 21.1 client wrote grug's error to its own logs/latest.log and crash report, then
stopped writing to the launcher's pipe and never exited, so launch.log never held the error, the
script waited for its 15-minute default deadline and the job's 10 minutes cancelled it with nothing
printed. The cases pin the three things that fix it: the error is found in the game's own logs, a
game that never decides fails with the log's tail before the job's timeout, and a heartbeat says
how far the launch got.

#1431: portablemc's install of Babric b1.7.3 ended after one of the 493 library downloads answered
404 from Maven Central, so the game never started, and the script reported a game that exited without
grug's error with the exit status unread and the launcher's own error buried under the hundreds of
progress updates one log line holds. The cases pin what the report names now, that such an install is
retried once, and that a game which ran is never repeated. They also pin the two places a failed
install could still be blamed on the game or go unseen: a deadline that expires while the launcher is
still installing, and the ModLoader recipe's dry install, whose output used to be discarded.

The harness copies the real script next to a fake launcher.sh whose `start` runs a canned game, and
puts a fake `import` on the path, so no Minecraft or X server is involved.
"""

import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parallel_test_runner  # noqa: E402

SCRIPT = Path(__file__).resolve().parent / "smoke-launch.sh"
JOB_TIMEOUT_SECONDS = 10 * 60

FAKE_LAUNCHER = """\
CACHE_DIR="$FAKE_CACHE"
MAIN_DIR="$FAKE_CACHE/main"
start() { GAME_DIR="$GAME_DIR" bash "$FAKE_GAME"; }
"""

# The shape of the failing run: the launcher's capture stops short of the error, the game's own logs
# hold it, and the process stays alive.
HUNG_GAME_WITH_LOG_ERROR = """\
echo "Launched"
echo "[18:31:07] [_stdout_] [INFO] Backend library: LWJGL version 3.3.3+5"
mkdir -p "$GAME_DIR/logs" "$GAME_DIR/crash-reports"
echo "net.grug.GrugUnsupportedGameException: grug does not run on this game: remove it" >"$GAME_DIR/logs/latest.log"
echo "Description: Mod loading failures" >"$GAME_DIR/crash-reports/crash-fml.txt"
exec sleep 600
"""

GAME_WITH_STDOUT_ERROR = """\
echo "Launched"
echo "Exception: grug does not run on this game: remove it"
exec sleep 600
"""

HUNG_GAME_WITHOUT_ERROR = """\
echo "Launched"
for i in 1 2 3; do echo "loading step $i"; done
exec sleep 600
"""

# The shape of #1431: portablemc installs the game, one of the libraries it fetches answers 404, and
# the launcher exits before it spawns the game. It rewrites one progress line with carriage returns,
# the way its download bar does, so a naive tail replays every update.
LAUNCHER_FETCH_FAILURE = """\
printf '[  ..  ] Downloading -- 1.0 MB/s 50.0%% (1/2)\\r[  ..  ] Downloading -- 2.0 MB/s 100.0%% (2/2)\\n'
printf '[ ERRO ] Failed to download 1 out of 2 entries...\\n'
printf '[ ERRO ] https://repo1.maven.org/maven2/x/terminalconsoleappender-1.2.0.jar\\n'
printf '         -> /home/runner/.cache/grug-production/main/libraries/x.jar\\n'
printf '            Invalid status: 404\\n'
exit 1
"""

# A launcher that never gets as far as spawning the game, without failing a fetch: a slow install, or
# one whose process is still alive when the script's window ends.
HUNG_INSTALL_WITHOUT_LAUNCH = """\
echo "installing libraries"
exec sleep 600
"""

# The ModLoader recipe's dry install is a `portablemc start --dry` before the patched jar exists. It
# fetches like any install, so a bad response there exits the launcher before the game is spawned.
DRY_INSTALL_FETCH_FAILURE = """\
printf '[ ERRO ] Failed to download 1 out of 2 entries...\\n'
printf '[ ERRO ] https://repo1.maven.org/maven2/x/terminalconsoleappender-1.2.0.jar\\n'
printf '            Invalid status: 404\\n'
exit 1
"""

# The same install, succeeding on the attempt after it: as in a run that passes when only the failed
# job is rerun.
GAME_AFTER_ONE_FETCH_FAILURE = (
    """\
if [ -f "$FAKE_STATE/attempted" ]; then
  echo "Launched"
  echo "Exception: grug does not run on this game: remove it"
  exec sleep 600
fi
touch "$FAKE_STATE/attempted"
"""
    + LAUNCHER_FETCH_FAILURE
)

# The launcher spawned the game, and the game exited on its own with a status of its own.
GAME_THAT_EXITS_AFTER_LAUNCH = """\
echo "Launched"
echo "loading a world"
exit 3
"""

# A game that ran prints the launcher's words too; only an install that never spawned the game may be
# repeated.
GAME_THAT_EXITS_WITH_A_FETCH_FAILURE = """\
echo "Launched"
echo "[ ERRO ] Failed to download 1 out of 2 entries..."
exit 2
"""

GAME_THAT_PRINTS_THE_ERROR_AND_EXITS = """\
echo "Launched"
echo "net.grug.GrugUnsupportedGameException: grug does not run on this game: remove it"
exit 1
"""

# An inert recipe: the game has to load to its first screen with grug inactive, and this one stops
# right after its loader says loading is over.
GAME_THAT_EXITS_AFTER_LOADING = """\
echo "Launched"
echo "Forge Mod Loader has successfully loaded 3 mods"
exit 4
"""


class SmokeLaunchTest(unittest.TestCase):
    def run_script(
        self, game, recipe="neoforge-21.1", timeout="6", heartbeat="30", portablemc=None
    ):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        scripts = tmp / "repo" / ".github" / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy(SCRIPT, scripts / "smoke-launch.sh")
        (scripts / "launcher.sh").write_text(FAKE_LAUNCHER)
        (tmp / "game.sh").write_text(game)
        (tmp / "state").mkdir()
        (tmp / "jar").mkdir()
        (tmp / "jar" / "grug.jar").write_text("jar")
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        fake_import = bin_dir / "import"
        fake_import.write_text('#!/usr/bin/env bash\nshift 2\necho png >"$1"\n')
        fake_import.chmod(0o755)
        if portablemc is not None:
            stub = bin_dir / "portablemc"
            stub.write_text("#!/usr/bin/env bash\n" + portablemc)
            stub.chmod(0o755)
        env = dict(
            os.environ,
            PATH=f"{bin_dir}:{os.environ['PATH']}",
            FAKE_CACHE=str(tmp / "cache"),
            FAKE_GAME=str(tmp / "game.sh"),
            FAKE_STATE=str(tmp / "state"),
            GRUG_SMOKE_TIMEOUT=timeout,
            GRUG_SMOKE_HEARTBEAT=heartbeat,
        )
        result = subprocess.run(
            [
                str(scripts / "smoke-launch.sh"),
                recipe,
                str(tmp / "jar" / "grug.jar"),
                str(tmp / "work"),
            ],
            env=env,
            capture_output=True,
            text=True,
            timeout=60,
        )
        return result, tmp / "work"

    def test_error_only_in_the_games_own_logs_passes_though_the_game_hangs(self):
        result, work = self.run_script(HUNG_GAME_WITH_LOG_ERROR)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("stopped with grug's error: grug does not run on this game", result.stdout)
        self.assertTrue((work / "title.png").exists())

    def test_error_in_the_launch_log_still_passes(self):
        result, _ = self.run_script(GAME_WITH_STDOUT_ERROR)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)

    def test_a_game_that_never_decides_fails_with_the_log_tail(self):
        result, _ = self.run_script(HUNG_GAME_WITHOUT_ERROR, timeout="3")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("grug's error did not appear within the timeout", result.stderr)
        self.assertIn("loading step 3", result.stderr)

    def test_the_default_deadline_is_under_the_jobs_timeout(self):
        source = SCRIPT.read_text()
        match = re.search(r"^SMOKE_TIMEOUT=\$\{GRUG_SMOKE_TIMEOUT:-(\d+)\}$", source, re.MULTILINE)
        self.assertIsNotNone(match)
        self.assertLess(int(match.group(1)), JOB_TIMEOUT_SECONDS - 60)
        self.assertIn("deadline=$((SECONDS + SMOKE_TIMEOUT))", source)

    def test_a_heartbeat_names_the_elapsed_time_and_the_log(self):
        result, _ = self.run_script(HUNG_GAME_WITHOUT_ERROR, timeout="4", heartbeat="1")
        self.assertEqual(result.returncode, 1)
        self.assertRegex(
            result.stdout,
            r"neoforge-21\.1: \d+s, game running, log \d+ bytes, last line: loading step 3",
        )

    def test_an_install_that_failed_fetching_is_retried_once(self):
        result, work = self.run_script(GAME_AFTER_ONE_FETCH_FAILURE, timeout="10")
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("Retrying the launch, attempt 2 of 2", result.stderr)
        self.assertIn("stopped with grug's error: grug does not run on this game", result.stdout)
        self.assertTrue((work / "title.png").exists())
        failed = work / "launch.log.attempt-1"
        self.assertTrue(failed.exists())
        self.assertIn("Failed to download", failed.read_text())

    def test_a_launcher_that_keeps_failing_fetching_is_reported_as_the_launcher(self):
        result, _ = self.run_script(LAUNCHER_FETCH_FAILURE)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("the launcher exited with status 1 before it started the game", result.stderr)
        self.assertIn("Retrying the launch, attempt 2 of 2", result.stderr)
        # The launcher's own words, and not a game that exited without grug's error.
        self.assertIn("The launcher's own report:", result.stderr)
        self.assertIn("terminalconsoleappender-1.2.0.jar", result.stderr)
        self.assertIn("Invalid status: 404", result.stderr)
        self.assertNotIn("the game exited without grug's error", result.stderr)
        # The progress bar's last update, and none of the updates it drew over.
        self.assertIn("(2/2)", result.stderr)
        self.assertNotIn("(1/2)", result.stderr)

    def test_a_deadline_before_the_launcher_spawned_the_game_blames_the_install(self):
        for recipe in ("neoforge-21.1", "forge-1.6.4"):
            with self.subTest(recipe=recipe):
                result, _ = self.run_script(HUNG_INSTALL_WITHOUT_LAUNCH, recipe=recipe, timeout="3")
                self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
                self.assertIn(
                    "the launcher had not started the game when the 3s window ended", result.stderr
                )
                self.assertNotIn("grug's error did not appear", result.stderr)
                self.assertNotIn("did not end within the timeout", result.stderr)
                self.assertIn("installing libraries", result.stderr)

    def test_a_failed_dry_install_is_reported_and_retried(self):
        result, work = self.run_script(
            GAME_THAT_EXITS_AFTER_LAUNCH,
            recipe="modloader-b1.7.3",
            timeout="10",
            portablemc=DRY_INSTALL_FETCH_FAILURE,
        )
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("Retrying the launch, attempt 2 of 2", result.stderr)
        self.assertIn("the launcher exited with status 1 before it started the game", result.stderr)
        self.assertIn("The launcher's own report:", result.stderr)
        self.assertIn("Invalid status: 404", result.stderr)
        self.assertNotIn("the game exited without grug's error", result.stderr)
        failed = work / "launch.log.attempt-1"
        self.assertTrue(failed.exists())
        self.assertIn("terminalconsoleappender-1.2.0.jar", failed.read_text())

    def test_a_game_that_exits_names_the_launch_status(self):
        result, _ = self.run_script(GAME_THAT_EXITS_AFTER_LAUNCH)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("the game exited without grug's error (status 3)", result.stderr)
        self.assertNotIn("Retrying the launch", result.stderr)

    def test_a_game_that_ran_is_never_retried_for_a_download_line(self):
        result, _ = self.run_script(GAME_THAT_EXITS_WITH_A_FETCH_FAILURE)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("the game exited without grug's error (status 2)", result.stderr)
        self.assertNotIn("Retrying the launch", result.stderr)

    def test_an_inert_game_that_exits_after_loading_names_the_launch_status(self):
        result, _ = self.run_script(GAME_THAT_EXITS_AFTER_LOADING, recipe="forge-1.6.4")
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        self.assertIn("the game exited after loading ended (status 4)", result.stderr)
        self.assertNotIn("Retrying the launch", result.stderr)

    def test_the_error_printed_just_before_the_game_exits_passes(self):
        result, _ = self.run_script(GAME_THAT_PRINTS_THE_ERROR_AND_EXITS)
        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertIn("stopped with grug's error: grug does not run on this game", result.stdout)
        self.assertNotIn("Retrying the launch", result.stderr)


def main(argv: list[str]) -> int:
    """Runs every case in a process of its own, side by side, unless argv names cases.

    The fake game's waits are the whole cost of the file (55 s one case after another), and each
    case builds its own temporary directory, so the cases share nothing. GRUG_TEST_JOBS, or --jobs
    N, sets the number of processes; a case named on the command line, or unittest's own options,
    run in this process one at a time.
    """
    jobs, args = parallel_test_runner.parse_jobs(argv)
    if args:
        unittest.main(argv=[sys.argv[0], *args])
    commands = []
    for group in unittest.defaultTestLoader.loadTestsFromModule(sys.modules[__name__]):
        for case in group:
            label = case.id().split(".", 1)[1]
            commands.append((label, [sys.executable, str(Path(__file__).resolve()), label]))
    return parallel_test_runner.run(commands, jobs)


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
