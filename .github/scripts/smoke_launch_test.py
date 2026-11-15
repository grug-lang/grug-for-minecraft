#!/usr/bin/env python3
"""Tests how .github/scripts/smoke-launch.sh decides, against a fake launcher and a fake game.

#1335: a NeoForge 21.1 client wrote grug's error to its own logs/latest.log and crash report, then
stopped writing to the launcher's pipe and never exited, so launch.log never held the error, the
script waited for its 15-minute default deadline and the job's 10 minutes cancelled it with nothing
printed. The cases pin the three things that fix it: the error is found in the game's own logs, a
game that never decides fails with the log's tail before the job's timeout, and a heartbeat says
how far the launch got.

The harness copies the real script next to a fake launcher.sh whose `start` runs a canned game, and
puts a fake `import` on the path, so no Minecraft or X server is involved.
"""

import os
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

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
echo "[18:31:07] [_stdout_] [INFO] Backend library: LWJGL version 3.3.3+5"
mkdir -p "$GAME_DIR/logs" "$GAME_DIR/crash-reports"
echo "net.grug.GrugUnsupportedGameException: grug does not run on this game: remove it" >"$GAME_DIR/logs/latest.log"
echo "Description: Mod loading failures" >"$GAME_DIR/crash-reports/crash-fml.txt"
exec sleep 600
"""

GAME_WITH_STDOUT_ERROR = """\
echo "Exception: grug does not run on this game: remove it"
exec sleep 600
"""

HUNG_GAME_WITHOUT_ERROR = """\
for i in 1 2 3; do echo "loading step $i"; done
exec sleep 600
"""


class SmokeLaunchTest(unittest.TestCase):
    def run_script(self, game, timeout="6", heartbeat="30"):
        tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, tmp, True)
        scripts = tmp / "repo" / ".github" / "scripts"
        scripts.mkdir(parents=True)
        shutil.copy(SCRIPT, scripts / "smoke-launch.sh")
        (scripts / "launcher.sh").write_text(FAKE_LAUNCHER)
        (tmp / "game.sh").write_text(game)
        (tmp / "jar").mkdir()
        (tmp / "jar" / "grug.jar").write_text("jar")
        bin_dir = tmp / "bin"
        bin_dir.mkdir()
        fake_import = bin_dir / "import"
        fake_import.write_text('#!/usr/bin/env bash\nshift 2\necho png >"$1"\n')
        fake_import.chmod(0o755)
        env = dict(
            os.environ,
            PATH=f"{bin_dir}:{os.environ['PATH']}",
            FAKE_CACHE=str(tmp / "cache"),
            FAKE_GAME=str(tmp / "game.sh"),
            GRUG_SMOKE_TIMEOUT=timeout,
            GRUG_SMOKE_HEARTBEAT=heartbeat,
        )
        result = subprocess.run(
            [
                str(scripts / "smoke-launch.sh"),
                "neoforge-21.1",
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
        match = [
            line
            for line in SCRIPT.read_text().splitlines()
            if line.startswith("deadline=$((SECONDS +")
        ]
        self.assertEqual(len(match), 1)
        default = int(match[0].split(":-")[1].split("}")[0])
        self.assertLess(default, JOB_TIMEOUT_SECONDS - 60)

    def test_a_heartbeat_names_the_elapsed_time_and_the_log(self):
        result, _ = self.run_script(HUNG_GAME_WITHOUT_ERROR, timeout="4", heartbeat="1")
        self.assertEqual(result.returncode, 1)
        self.assertRegex(
            result.stdout,
            r"neoforge-21\.1: \d+s, game running, log \d+ bytes, last line: loading step 3",
        )


if __name__ == "__main__":
    unittest.main()
