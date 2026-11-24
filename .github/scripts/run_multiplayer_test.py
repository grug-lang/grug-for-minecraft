#!/usr/bin/env python3
"""Tests the Ornithe dedicated server install of .github/scripts/run-multiplayer.sh.

ornithe-installer-rs writes fabric-server-launch.jar before it downloads server.jar, and exits 0
when that download failed. So neither the exit status nor the launch jar says the install is
complete, and the marker the script writes for the next run must not be written over a directory
that has no server.jar (#1251). The script runs as a process of its own against a fake repository:
the real script and jar assembler, a Gradle that writes the two jars the install reads, a curl that
hands over a fake installer, and a java that exits at once. The last two are the only places the
install touches the network or launches a game.

What the tests can reach ends where the server launch begins: the run then waits for a game that
never starts, which is why a case that installs successfully still ends non-zero.

The second player of the 1.20.6 leg (#1027) is tested the same way, from the other side: a fake
Gradle stands in for the build.gradle hook that makes runClient the wrapper, the real wrapper starts
a fake java as the second client in its own game directory, and the test reads where each client was
launched. The wrapper and the run script are the real files; only the game and its Gradle are fakes.
"""

import fnmatch
import os
import re
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import parallel_test_runner  # noqa: E402

SCRIPTS = Path(__file__).resolve().parent
LOADER = "b1.7.3-ornithe"
SERVER_VERSION = "b1.7.3"
SECOND_LOADER = "1.20.6-forge"


def stage_scripts(scripts: Path, *names: str) -> None:
    """Copies the real scripts a fake repository runs into its `.github/scripts`.

    run-multiplayer.sh sources pin-llvmpipe.sh and retryable-failure.sh at start, so a fixture that
    copies it stages those too. A missing one puts a "No such file or directory" line in every fake
    run's stderr, which is the stream a failing assertion here prints, so it hid this file's own
    cause once (#1715). Sourcing pin-llvmpipe.sh only prints the CPU and exports two Mesa variables
    in the run-multiplayer.sh process the fixture starts, so it cannot touch the runner's own flags.
    """
    for name in names:
        shutil.copy(SCRIPTS / name, scripts / name)


# The eleven overlay and JEI tests every leg but a1.1.2_01 requires twice: its PASS line and the line
# only a test whose overlay was drawn on the joined client writes. a1.1.2_01 does not require
# overlay and jei/jei, because its server does not apply the container click the joined client
# presses (#1393). The forced-normalization item list is left out here.
OVERLAY_DRAWN_TESTS = [
    "coverage/tests/give_requires_operator",
    "coverage/tests/overlay",
    "coverage/tests/overlay_click_hook",
    "coverage/tests/overlay_key",
    "coverage/tests/overlay_probe_gives_nothing",
    "coverage/tests/overlay_screen_open",
    "coverage/tests/overlay_scroll",
    "jei/tests/jei",
    "jei/tests/hotkey",
    "jei/tests/paging",
    "jei/tests/scroll",
]

# The fake installer reproduces the installer's failure shape: the launch jar is written before the
# download that fails, and the exit status is 0 either way. FAKE_INSTALL_SERVER_JAR is `none` for a
# download that never wrote the jar and `empty` for a write that failed partway, and the sentinel
# says the installer ran at all.
INSTALLER = """\
#!/usr/bin/env bash
dir=""
while [ $# -gt 0 ]; do
  if [ "$1" = "-d" ]; then dir="$2"; shift 2; continue; fi
  shift
done
[ -n "$dir" ] || exit 1
[ -n "${FAKE_INSTALL_SENTINEL:-}" ] && printf 'ran\\n' > "$FAKE_INSTALL_SENTINEL"
mkdir -p "$dir"
printf 'launch\\n' > "$dir/fabric-server-launch.jar"
case "${FAKE_INSTALL_SERVER_JAR:-full}" in
  none) ;;
  # A write that failed partway leaves the jar the installer opened before it exited 0.
  empty) : > "$dir/server.jar" ;;
  *) printf 'server\\n' > "$dir/server.jar" ;;
esac
exit 0
"""

# Writes the jars the install reads and exits, so the case spends no Gradle time.
GRADLEW = """\
#!/usr/bin/env bash
mkdir -p core/build/libs
"$FAKE_REAL_PYTHON" -c 'import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("grug/Grug.class", b"")' core/build/libs/core-0.1.0-release.jar
"""

# The agent jar gradle/jacoco-loader.gradle's printJacocoAgentPath prints, which the script reads
# back to put the agent on the server's own java line (#1027).
AGENT = "/fake/jacoco-agent.jar"

LOADER_GRADLEW = """\
#!/usr/bin/env bash
mkdir -p build/layer
"$FAKE_REAL_PYTHON" -c 'import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("fabric.mod.json", b"{}")' build/layer/grug-@LOADER@.jar
printf 'JACOCO_AGENT=@AGENT@\\n'
""".replace("@LOADER@", LOADER).replace("@AGENT@", AGENT)

# The same build without the line, which is what a task that never ran, or a drop of the print,
# would leave: the script has to fail rather than launch an uninstrumented server.
LOADER_GRADLEW_WITHOUT_AGENT = LOADER_GRADLEW.split("printf 'JACOCO_AGENT=")[0]

CURL = """\
#!/usr/bin/env bash
out=""
while [ $# -gt 0 ]; do
  if [ "$1" = "-o" ]; then out="$2"; shift 2; continue; fi
  shift
done
[ -n "$out" ] || exit 1
cp "$FAKE_CURL_SOURCE" "$out"
"""

# python3 as the script finds it: the OSL jar the install fetches from Modrinth is the one network
# call left, so that request is answered with an empty file. Everything else is the real python3.
PYTHON = """\
#!/usr/bin/env bash
if [ "$1" != "-" ]; then exec "$FAKE_REAL_PYTHON" "$@"; fi
program=$(mktemp) || exit 1
trap 'rm -f "$program"' EXIT
cat > "$program"
if grep -q 'api.modrinth.com' "$program"; then
  : > "$3"
  exit 0
fi
exec "$FAKE_REAL_PYTHON" "$@" < "$program"
"""

# Exits at once, and records the arguments it was given so a case can read what the script launched
# with, which is where the agent and the coverage directory are.
JAVA = """\
#!/usr/bin/env bash
[ -n "${FAKE_JAVA_ARGV:-}" ] && printf '%s\\n' "$@" > "$FAKE_JAVA_ARGV"
exit 0
"""

# The fake Gradle of the 1.20.6 fixture: the server prints the ready line and stays up, and the
# client run is handed to the wrapper the build.gradle hook would have made runClient, with the args
# a real runClient would carry.
SECOND_GRADLEW = """\
#!/usr/bin/env bash
printf '%s\\n' "$*" >> "${FAKE_GRADLE_LOG:?}"
case "$*" in
  *runServer*)
    [ -n "${FAKE_JOIN_FILE:-}" ] && printf 'join timeout %s\\n' "$GRUG_CI_JOIN_TIMEOUT_MILLIS" > "$FAKE_JOIN_FILE"
    printf 'Done (1.2s)! For help, type "help"\\n'
    if [ "${FAKE_RUN_PASSES:-0}" = "1" ]; then
      # A run that passes, which is what require_second_player reads: the suite is declared passed
      # after both clients have had time to start, so their logs exist when the script checks them.
      mkdir -p loaders/1.20.6-forge/build/jacoco-server
      printf 'segment\\n' > loaders/1.20.6-forge/build/jacoco-server/run.exec
      sleep 6
      cat "${FAKE_SERVER_LINES_FILE:?}"
      # A server that printed the marker and then never halted.
      [ "${FAKE_SERVER_STAYS:-0}" = "1" ] && exec sleep 600
      exit 0
    fi
    if [ "${FAKE_STARTER_LEAVES:-0}" = "1" ]; then
      # The run has begun, then the lines a server writes after its starter's client was killed.
      printf '[GRUG CI] CI mode active & player loaded after 1 ms. Firing tests!\\n'
      printf '[GRUG CI] Executing test: coverage/tests/starter_left_window-Test.grug\\n'
      printf '%s\\n' "${GRUG_CI_TESTS:-}" > "${FAKE_TESTS_ENV_FILE:?}"
      sleep 6
      head -n 1 "${FAKE_SERVER_LINES_FILE:?}"
      sleep "${FAKE_COUNT_GAP:-0}"
      tail -n +2 "${FAKE_SERVER_LINES_FILE:?}"
      # A real server halts right after its marker, so the process is gone when the script looks.
      [ "${FAKE_SERVER_EXITS:-0}" = "1" ] && exit 0
      exec sleep 600
    fi
    exec sleep 600
    ;;
  *runClient*)
    # Gradle's runClient runs in the loader's run directory; the wrapper then starts the second
    # client in run-second and runs this one where Gradle put it.
    mkdir -p loaders/1.20.6-forge/run
    cd loaders/1.20.6-forge/run
    exec env -u JAVA_HOME "${GRUG_CI_CLIENT_WRAPPER:?}" -Dfake.client=true FakeMain
    ;;
esac
exit 0
"""

# A client that records the directory it was launched in. The second one says it is in the world, so
# the wrapper starts the client Gradle asked for; the first one only stays up. In a passing run both
# say they sent their START, which the client cannot know the server accepted, and only the first
# receives a control request.
SECOND_JAVA = """\
#!/usr/bin/env bash
printf 'cwd=%s\\n' "$PWD" >> "${FAKE_JAVA_LOG:?}"
if [ "${PWD##*/}" = "run-second" ]; then
  if [ "${FAKE_SECOND_JOINS:-1}" = "1" ]; then
    printf '[GRUG MULTIPLAYER] the second client is in the world and waits for its start file\\n'
  fi
  if [ "${FAKE_RUN_PASSES:-0}" = "1" ]; then
    printf '[GRUG CONTROL] this client started a run, and sent the start\\n'
  fi
else
  if [ "${FAKE_RUN_PASSES:-0}" = "1" ]; then
    printf '[GRUG CONTROL] this client started a run, and sent the start\\n'
    printf '[GRUG CONTROL] request 7\\n'
  fi
fi
exec sleep 600
"""

# The job's xvfb-run server provides the first display, so the script starts one of its own for the
# second client. The fixture only needs it to stay up and to record the display it was given.
SECOND_XVFB = """\
#!/usr/bin/env bash
printf '%s\\n' "$*" >> "${FAKE_XVFB_LOG:?}"
exec sleep 600
"""

# The readiness probe the script runs before starting the second client. The fake Xvfb is up as
# soon as it is started, so the first probe is the one that passes.
SECOND_XSET = """\
#!/usr/bin/env bash
exit 0
"""


class RunMultiplayerTest(unittest.TestCase):
    """Runs the real install against a fake repository root."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

        scripts = self.tmp / ".github" / "scripts"
        scripts.mkdir(parents=True)
        stage_scripts(
            scripts,
            "run-multiplayer.sh",
            "pin-llvmpipe.sh",
            "retryable-failure.sh",
            "universal_jar.py",
        )

        self.loader = self.tmp / "loaders" / LOADER
        self.loader.mkdir(parents=True)
        (self.loader / "server-version.txt").write_text(SERVER_VERSION + "\n")
        (self.loader / "test-save.txt").write_text("fake.zip\n")
        (self.loader / "gradle.properties").write_text(
            "loader_version=0.17.1\nosl_version=0.20.3\n"
        )
        self.set_executable(self.tmp / "gradlew", GRADLEW)
        self.set_executable(self.loader / "gradlew", LOADER_GRADLEW)

        # The mods the run copies beside the install, one of them coverage's.
        for mod in ("coverage", "fake"):
            (self.tmp / "mods" / mod).mkdir(parents=True)
        saves = self.tmp / "test-saves"
        saves.mkdir()
        with zipfile.ZipFile(saves / "fake.zip", "w") as archive:
            archive.writestr("world/level.dat", b"")

        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.set_executable(self.bin / "curl", CURL)
        self.set_executable(self.bin / "python3", PYTHON)
        self.set_executable(self.bin / "java", JAVA)
        self.installer = self.tmp / "fake-installer"
        self.set_executable(self.installer, INSTALLER)
        self.sentinel = self.tmp / "installer-ran"
        self.java_argv = self.tmp / "java-argv"

    def set_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, server_jar="full", agent_line=True):
        self.set_executable(
            self.loader / "gradlew",
            LOADER_GRADLEW if agent_line else LOADER_GRADLEW_WITHOUT_AGENT,
        )
        env = dict(os.environ)
        env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{env['PATH']}",
                "DISPLAY": ":0",
                "FAKE_REAL_PYTHON": sys.executable,
                "FAKE_CURL_SOURCE": str(self.installer),
                "FAKE_INSTALL_SENTINEL": str(self.sentinel),
                "FAKE_INSTALL_SERVER_JAR": server_jar,
                "FAKE_JAVA_ARGV": str(self.java_argv),
                # The fake server exits at once, so the loop ends the case when it first looks. The
                # deadline is far off so a slow runner cannot reach it before that look and print
                # "not ready" in place of "exited before it was ready" (#1302 flaked on that).
                "GRUG_CI_BUILD_TIMEOUT": "30",
            }
        )
        env.pop("GRUG_ORNITHE_SERVER_HASH", None)
        return subprocess.run(
            [str(self.tmp / ".github" / "scripts" / "run-multiplayer.sh"), LOADER],
            cwd=self.tmp,
            env=env,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=120,
        )

    def installed(self):
        return self.loader / "ornithe-server"

    def marker(self):
        return self.installed() / ".grug-server-install"

    def test_an_installer_that_exits_zero_without_server_jar_writes_no_marker(self):
        result = self.run_script(server_jar="none")

        self.assertNotEqual(result.returncode, 0)
        self.assertTrue(self.sentinel.is_file(), "the fake installer did not run")
        self.assertFalse(self.marker().exists(), "an incomplete install was marked complete")
        # The directory is gone rather than left with a launch jar, so the next run has nothing to
        # trust and installs again.
        self.assertFalse(self.installed().exists())
        self.assertIn("left no usable server.jar", result.stderr)

    def test_an_installer_that_leaves_an_empty_server_jar_writes_no_marker(self):
        # A write that failed partway (ENOSPC, say) leaves the jar the installer opened, and the
        # installer still exits 0. A jar that cannot launch must not be marked complete either.
        result = self.run_script(server_jar="empty")

        self.assertNotEqual(result.returncode, 0)
        self.assertFalse(self.marker().exists(), "an install was marked complete with an empty jar")
        self.assertFalse(self.installed().exists())
        self.assertIn("left no usable server.jar", result.stderr)

    def test_a_complete_install_writes_the_marker(self):
        result = self.run_script()

        self.assertTrue(self.marker().is_file())
        self.assertEqual(self.marker().read_text().strip(), SERVER_VERSION)
        self.assertTrue((self.installed() / "server.jar").is_file())
        # The run reached the launch, so the install returned success with the marker written.
        self.assertIn("exited before it was ready", result.stderr)

    def test_the_server_is_launched_with_the_agent_and_its_own_directory(self):
        result = self.run_script()

        self.assertIn("exited before it was ready", result.stderr)
        argv = self.java_argv.read_text().splitlines()
        self.assertIn(
            f"-javaagent:{AGENT}=output=none,dumponexit=false,"
            "includes=net.grug.*:net.minecraft.crafting.*",
            argv,
        )
        # Beside the client's build/jacoco rather than over it, so two JVMs cutting segments into
        # NNN-named files cannot overwrite each other's (#1027).
        self.assertIn(f"-Dgrug.coverage.dir={self.loader}/build/jacoco-server", argv)

    def test_a_build_that_prints_no_agent_path_fails_before_the_launch(self):
        result = self.run_script(agent_line=False)

        self.assertNotEqual(result.returncode, 0)
        self.assertIn("printed no JACOCO_AGENT= line", result.stderr)
        # An uninstrumented launch would look exactly like a leg that recorded nothing, so it must
        # not happen at all.
        self.assertFalse(self.java_argv.exists(), "the server launched without the agent")

    def test_a_restored_install_without_server_jar_is_reinstalled(self):
        # What a run of the buggy marker leaves behind: the marker and the launch jar, and no
        # server.jar. The acceptance check must not trust it.
        self.installed().mkdir(parents=True)
        self.marker().write_text(SERVER_VERSION + "\n")
        (self.installed() / "fabric-server-launch.jar").write_text("launch\n")

        result = self.run_script()

        self.assertTrue(self.sentinel.is_file(), "the installer was skipped")
        self.assertTrue((self.installed() / "server.jar").is_file())
        self.assertEqual(self.marker().read_text().strip(), SERVER_VERSION)
        log = (self.loader / "run-server-ci.log").read_text()
        self.assertNotIn("Using the installed Ornithe server", log)
        self.assertNotEqual(result.returncode, 0)

    def test_a_restored_install_with_an_empty_server_jar_is_reinstalled(self):
        # The acceptance check reads the jar's size, not only its name, so a truncated jar does not
        # count as an install.
        self.installed().mkdir(parents=True)
        self.marker().write_text(SERVER_VERSION + "\n")
        (self.installed() / "fabric-server-launch.jar").write_text("launch\n")
        (self.installed() / "server.jar").write_text("")

        self.run_script()

        self.assertTrue(self.sentinel.is_file(), "an install with an empty server.jar was used")
        self.assertGreater((self.installed() / "server.jar").stat().st_size, 0)

    def test_a_complete_restored_install_skips_the_installer(self):
        self.installed().mkdir(parents=True)
        self.marker().write_text(SERVER_VERSION + "\n")
        (self.installed() / "fabric-server-launch.jar").write_text("launch\n")
        (self.installed() / "server.jar").write_text("server\n")

        result = self.run_script()

        self.assertFalse(self.sentinel.exists(), "the installer ran over a complete install")
        log = (self.loader / "run-server-ci.log").read_text()
        self.assertIn("Using the installed Ornithe server", log)
        self.assertTrue((self.loader / "run-server" / "fabric-server-launch.jar").is_file())
        self.assertNotEqual(result.returncode, 0)

    def test_a_restored_install_for_another_key_is_reinstalled(self):
        # A prefix fallback restores an entry built for a different key, and the marker is what
        # says so. The jars are complete, so only the marker decides.
        self.installed().mkdir(parents=True)
        self.marker().write_text("another-key\n")
        (self.installed() / "fabric-server-launch.jar").write_text("launch\n")
        (self.installed() / "server.jar").write_text("server\n")

        self.run_script()

        self.assertTrue(self.sentinel.is_file(), "a stale install was used")
        self.assertEqual(self.marker().read_text().strip(), SERVER_VERSION)


class SecondPlayerTest(unittest.TestCase):
    """The 1.20.6 leg's second client, from the wrapper the run script hands to Gradle."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        scripts = self.tmp / ".github" / "scripts"
        scripts.mkdir(parents=True)
        stage_scripts(
            scripts,
            "run-multiplayer.sh",
            "pin-llvmpipe.sh",
            "retryable-failure.sh",
            "run-client-with-second.sh",
        )

        self.loader = self.tmp / "loaders" / SECOND_LOADER
        self.loader.mkdir(parents=True)
        (self.loader / "test-save.txt").write_text("fake.zip\n")
        saves = self.tmp / "test-saves"
        saves.mkdir()
        with zipfile.ZipFile(saves / "fake.zip", "w") as archive:
            archive.writestr("world/level.dat", b"")

        for mod in ("coverage", "fake"):
            (self.tmp / "mods" / mod).mkdir(parents=True)

        self.set_executable(self.tmp / "gradlew", SECOND_GRADLEW)
        self.bin = self.tmp / "bin"
        self.bin.mkdir()
        self.set_executable(self.bin / "java", SECOND_JAVA)
        self.set_executable(self.bin / "Xvfb", SECOND_XVFB)
        self.set_executable(self.bin / "xset", SECOND_XSET)
        self.java_log = self.tmp / "java-launches.log"
        self.gradle_log = self.tmp / "gradle-invocations.log"
        self.xvfb_log = self.tmp / "xvfb.log"

    def set_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, **env_overrides):
        env = dict(os.environ)
        env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{env['PATH']}",
                "DISPLAY": ":0",
                "FAKE_JAVA_LOG": str(self.java_log),
                "FAKE_GRADLE_LOG": str(self.gradle_log),
                "FAKE_XVFB_LOG": str(self.xvfb_log),
                # The run is stopped by its own deadline shortly after both clients are up, which is
                # what this fixture is about; the game itself never passes anything. A case that
                # needs the client to die first sets a wider deadline.
                "GRUG_CI_BUILD_TIMEOUT": "30",
                # 5 s and not 1: the fixture's second client needs the margin to join (#1514).
                "GRUG_CI_RUN_TIMEOUT": "5",
                "GRUG_CI_SECOND_JOIN_TIMEOUT": "2",
            }
        )
        # The wrapper prefers JAVA_HOME's java, which on a developer's machine is the real one.
        env.pop("JAVA_HOME", None)
        env.pop("GRUG_ORNITHE_SERVER_HASH", None)
        env.update(env_overrides)
        return subprocess.run(
            [str(self.tmp / ".github" / "scripts" / "run-multiplayer.sh"), SECOND_LOADER],
            cwd=self.tmp,
            env=env,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=120,
        )

    def second_log(self):
        return self.loader / "run-second" / "second-client-launch.log"

    # The PASS lines the success branch of run-multiplayer.sh requires of the 1.20.6 server before
    # it reaches require_second_player, so a fake passing run gets to the checks this case is about.
    REQUIRED_PASSES = [
        "coverage/tests/forwarded_capture",
        "coverage/tests/forwarded_capture_dropped",
        "coverage/tests/forwarded_capture_late",
        "coverage/tests/forwarded_capture_late_then",
        "coverage/tests/forwarded_capture_same_tick",
        "coverage/tests/forwarded_world_settled",
        "coverage/tests/forwarded_screen_open",
        "coverage/tests/forwarded_screen_width",
        "coverage/tests/forwarded_gui_left",
        "coverage/tests/forwarded_gui_top",
        "coverage/tests/forwarded_gui_width",
        "coverage/tests/forwarded_gui_height",
        "coverage/tests/forwarded_close_screen",
        "coverage/tests/forwarded_open_inventory",
        "coverage/tests/client_saved_number",
        "coverage/tests/coverage_frame_saved_number",
        "coverage/tests/coverage_frame_saved_list_energy",
        "coverage/tests/coverage_mesh_saved_number",
        "coverage/tests/coverage_mesh_saved_reinit",
        "coverage/tests/coverage_mesh_frame_saved_number",
        "coverage/tests/use_block_effects",
        "coverage/tests/use_block_faces",
        "buildcraft/tests/redstone_engine_wrench",
        "buildcraft/tests/wooden_pipe_wrench",
        "coverage/tests/decline_use_places",
        "coverage/tests/decline_use",
        "coverage/tests/sound",
        # The four wall-backed captures #1161 promoted, which this leg requires by name because it
        # is the one whose joined client matches their flat goldens.
        "buildcraft/tests/cobblestone_pipe_shapes",
        "buildcraft/tests/redstone_engine",
        "buildcraft/tests/redstone_engine_animation",
        "buildcraft/tests/redstone_engine_facings",
        # The two wooden pipe captures #1096 promotes, which this leg requires.
        "buildcraft/tests/wooden_pipe_items",
        "buildcraft/tests/wooden_pipe_shapes",
        "coverage/tests/camera",
        # The snap's own server-side proof (#1161), required on every dedicated leg.
        "coverage/tests/test_origin_follows_the_test_player",
        "coverage/tests/remote_gui",
        "coverage/tests/item_icon",
        "coverage/tests/gui_builder",
        "coverage/tests/gui_click",
        "coverage/tests/gui_click_empty",
        "coverage/tests/gui_click_twice",
        "buildcraft/tests/autocrafting_table",
        "buildcraft/tests/redstone_engine_item",
        "coverage/tests/forwarded_press_screen_key",
        "coverage/tests/forwarded_scroll_screen",
        "coverage/tests/give_requires_operator",
        "coverage/tests/overlay_click_hook",
        "coverage/tests/overlay_key",
        "coverage/tests/overlay_probe_gives_nothing",
        "coverage/tests/overlay_screen_open",
        "coverage/tests/overlay_scroll",
        "jei/tests/hotkey",
        "jei/tests/paging",
        "jei/tests/scroll",
        "coverage/tests/second_player",
        # The tests #1390, #1425 and #1430 unlisted, which this leg requires by name (#1040).
        "coverage/tests/click_screen_release",
        "coverage/tests/hook_forced_normalization",
        "coverage/tests/overlay",
        "jei/tests/jei",
        "jei/tests/item_list_forced_normalization",
    ]

    # The overlay tests are required twice: the PASS line and the line only a test whose overlay
    # was drawn on the joined client writes.
    OVERLAY_PASSES = OVERLAY_DRAWN_TESTS

    def passing_server_lines(self, ignored_start=True, moved_stranger=True, omit=()):
        lines = ["[GRUG CONTROL] Dev started the run"]
        if ignored_start:
            lines.append("[GRUG CONTROL] SecondPlayer sent a start, which was ignored")
        if moved_stranger:
            lines.append(
                "[GRUG CI] Moved a second player 8 blocks from the starter, so a run that"
                " picked it would read another player's eye."
            )
        for path in self.REQUIRED_PASSES:
            if path in omit:
                continue
            lines.append(f"[GRUG CI] PASS {path}-Test.grug")
        for path in self.OVERLAY_PASSES:
            lines.append(f"[GRUG MULTIPLAYER] {path}-Test.grug drew the overlay")
        lines.append("[GRUG CI] The run ended with 2 player(s) connected.")
        lines.append("[GRUG CI] ALL 42 TESTS PASSED")
        return "\n".join(lines) + "\n"

    def test_a_passing_two_client_run_proves_the_second_player_property(self):
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines())
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn(
            "Two real clients: exactly one starter, its answers, and both connections survived",
            result.stdout,
        )

    def test_a_leg_that_skips_an_unlisted_test_fails_the_run(self):
        # A suite that passed can still leave a test #1390, #1425 or #1430 unlisted, so a leg that
        # skipped one must fail rather than pass quietly (#1040).
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(
            self.passing_server_lines(omit={"coverage/tests/click_screen_release"})
        )
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn(
            "==> The run passed, but coverage/tests/click_screen_release-Test.grug did not run"
            " on the dedicated server.",
            result.stderr,
        )

    def test_a_leg_that_skips_the_unlisted_jei_test_fails_the_run(self):
        # The JEI list is required on the 1.20.6 leg, so an arm that dropped 1.20.6-forge from the
        # shared case would let this omission through and pass quietly.
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines(omit={"jei/tests/jei"}))
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn(
            "==> The run passed, but jei/tests/jei-Test.grug did not run on the dedicated server.",
            result.stderr,
        )

    def test_a_server_that_does_not_halt_after_the_marker_fails_the_run(self):
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines())
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_STAYS="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
            GRUG_CI_HALT_TIMEOUT="2",
        )

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("did not halt", result.stderr)

    def test_the_server_is_told_four_fifths_of_the_runs_deadline_to_wait_for_a_player(self):
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines())
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            FAKE_JOIN_FILE=str(self.tmp / "join-timeout.txt"),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("join timeout 48000", (self.tmp / "join-timeout.txt").read_text())

    def starter_leaves(self, lines, **env):
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(lines)
        return self.run_script(
            FAKE_STARTER_LEAVES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_STARTER_LEAVES="1",
            GRUG_CI_RUN_TIMEOUT="60",
            FAKE_TESTS_ENV_FILE=str(self.tmp / "tests-env.txt"),
            **env,
        )

    def test_the_starter_leaving_with_a_second_player_still_connected_passes_the_arm(self):
        result = self.starter_leaves(
            "[GRUG CI] FAIL the test player left\n"
            "[GRUG CI] The player who started the run disconnected while 1 player(s) were still"
            " on the dedicated server.\n"
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("Killing the starter's client", result.stdout)
        self.assertIn("The run stopped when its starter left", result.stdout)
        # The runner matches GRUG_CI_TESTS exactly against loaded paths, which end in -Test.grug.
        self.assertEqual(
            "coverage/tests/starter_left_window-Test.grug",
            (self.tmp / "tests-env.txt").read_text().strip(),
        )

    def test_a_count_line_written_after_the_marker_is_still_read(self):
        # The server prints the marker and the count in two calls; the fake writes the count late.
        result = self.starter_leaves(
            "[GRUG CI] FAIL the test player left\n"
            "[GRUG CI] The player who started the run disconnected while 1 player(s) were still"
            " on the dedicated server.\n",
            FAKE_COUNT_GAP="2",
        )

        self.assertEqual(0, result.returncode, result.stderr)

    def test_a_server_that_exits_right_after_the_marker_still_passes_the_arm(self):
        # The server halts after printing the marker and the count, so a poll can find the process
        # gone; the marker is judged before the exit is reported.
        result = self.starter_leaves(
            "[GRUG CI] FAIL the test player left\n"
            "[GRUG CI] The player who started the run disconnected while 1 player(s) were still"
            " on the dedicated server.\n",
            FAKE_COUNT_GAP="1",
            FAKE_SERVER_EXITS="1",
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("exited without", result.stderr)

    def test_a_run_that_follows_the_second_player_fails_the_arm(self):
        # The old players.isEmpty() rule: the starter is gone, a second player is not, so the suite
        # carries on to the end instead of stopping.
        result = self.starter_leaves("[GRUG CI] ALL 254 TESTS PASSED\n")

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("the run followed another player", result.stderr)

    def test_a_stop_with_no_second_player_left_fails_the_arm(self):
        result = self.starter_leaves(
            "[GRUG CI] FAIL the test player left\n"
            "[GRUG CI] The player who started the run disconnected while 0 player(s) were still"
            " on the dedicated server.\n"
        )

        self.assertEqual(1, result.returncode, result.stdout)
        self.assertIn("not with the second player still connected", result.stderr)

    def test_a_run_without_an_ignored_second_start_fails_closed(self):
        # The two clients say they sent a START whether or not the server saw the second one, so the
        # server's ignored line is the only proof it arrived and was refused. A leg missing it must
        # fail rather than pass on the one accepted start.
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines(ignored_start=False))
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "The server logged no second START to ignore",
            result.stderr,
        )

    def test_a_run_without_the_second_player_moved_away_fails_closed(self):
        # Both players join at the world spawn, so without the move a wrong pick reads an origin
        # inside the camera's tolerance and the test cannot fail for it. The line proves the
        # discriminator was armed for this leg.
        lines_file = self.tmp / "server-lines.log"
        lines_file.write_text(self.passing_server_lines(moved_stranger=False))
        result = self.run_script(
            FAKE_RUN_PASSES="1",
            FAKE_SERVER_LINES_FILE=str(lines_file),
            GRUG_CI_RUN_TIMEOUT="60",
        )

        self.assertNotEqual(0, result.returncode)
        self.assertIn(
            "The server did not move the second player away",
            result.stderr,
        )

    def test_the_second_client_runs_before_the_client_gradle_asked_for(self):
        result = self.run_script()

        # The run ends on its own deadline rather than on a passing suite, which this fixture cannot
        # reach; what matters is that both clients were launched where the test says.
        self.assertIn("Timed out after", result.stderr)
        launches = self.java_log.read_text().splitlines()
        self.assertEqual(
            [f"cwd={self.loader}/run-second", f"cwd={self.loader}/run"],
            launches,
            "the second client must start first, in its own game directory, then the runClient one",
        )
        # The second client's own log is the one the run script reads and the job uploads, and the
        # join line the wrapper waits for is in it.
        self.assertIn(
            "[GRUG MULTIPLAYER] the second client is in the world",
            self.second_log().read_text(),
        )
        # The wrapper was launched as runClient's executable, with the command Gradle would have run.
        self.assertIn(":loaders:1.20.6-forge:runClient", self.gradle_log.read_text())
        self.assertTrue(self.xvfb_log.is_file(), "the second client got no display of its own")
        self.assertIn(":97", self.xvfb_log.read_text())

    def test_a_second_client_that_never_joins_does_not_launch_the_first(self):
        result = self.run_script(FAKE_SECOND_JOINS="0", GRUG_CI_RUN_TIMEOUT="10")

        self.assertNotEqual(result.returncode, 0)
        # The wrapper's own message goes to the client log the run script writes, and the run
        # script's verdict is on its stderr.
        self.assertIn(
            "The second client was not in the world after 2s",
            (self.loader / "run-client-ci.log").read_text(),
        )
        self.assertIn("The client exited before the server started the tests", result.stderr)
        # Only the second client ran: the client Gradle asked for must not start without the player
        # the two-player test is built around, and the leg must fail rather than pass with one.
        self.assertEqual([f"cwd={self.loader}/run-second"], self.java_log.read_text().splitlines())


# The fake Gradle of the standalone fixture: the repository root's publish exits without output,
# and the loader's own runServer prints a passing suite after the client could have joined. A
# standalone loader has no Ornithe install, no jacoco agent and no second client, which is what
# makes it the cheap leg to stand a whole passing run up for.
#
# A case about the joined client's build (#1702) turns on two switches. FAKE_CLIENT_STARTED names a
# file the server holds its tests until, so the client that does start is up before the server says
# its tests began and a failed client build is what the script sees first. FAKE_CLIENT_FAILURES names
# a directory whose <n>.log is the nth client build's output and whose absence means the build
# succeeded, so a client build can be made to fail once and pass on the retry, and FAKE_CLIENT_SLEEP
# holds that failing build before it prints, so a case can make it die after the run's deadline.
STANDALONE_GRADLEW = """\
#!/usr/bin/env bash
case "$*" in
  *runServer*)
    printf 'Done (1.2s)! For help, type "help"\\n'
    while [ -n "${FAKE_CLIENT_STARTED:-}" ] && [ ! -e "$FAKE_CLIENT_STARTED" ]; do sleep 0.1; done
    printf '[GRUG CI] CI mode active & player loaded after 1 ms. Firing tests!\\n'
    mkdir -p build/jacoco-server
    printf 'segment\\n' > build/jacoco-server/run.exec
    if [ -n "${FAKE_SERVER_READY_FILE:-}" ]; then
      # One case holds the server at a known point in a poll pass instead of for a fixed time.
      : > "$FAKE_SERVER_READY_FILE"
      while [ ! -e "${FAKE_SERVER_SIGNAL_FILE:?}" ]; do sleep 0.05; done
    else
      sleep 6
    fi
    cat "${FAKE_SERVER_LINES_FILE:?}"
    exit 0
    ;;
  *runClient*)
    if [ -n "${FAKE_CLIENT_FAILURES:-}" ]; then
      count=$(cat "${FAKE_CLIENT_FAILURES}/count" 2>/dev/null || echo 0)
      echo $((count + 1)) > "${FAKE_CLIENT_FAILURES}/count"
      if [ -f "${FAKE_CLIENT_FAILURES}/$((count + 1)).log" ]; then
        sleep "${FAKE_CLIENT_SLEEP:-0}"
        cat "${FAKE_CLIENT_FAILURES}/$((count + 1)).log"
        exit 1
      fi
      : > "${FAKE_CLIENT_STARTED:?}"
    fi
    exec sleep 600
    ;;
esac
exit 0
"""


class ServerUnlistedTest(unittest.TestCase):
    """A standalone leg the script does not require the forced-normalization item list of (#1040)."""

    LOADER = "b1.7.3-stationapi"

    # The paths the success branch of run-multiplayer.sh requires of this leg.
    # jei/tests/item_list_forced_normalization is absent on purpose: the script does not require it
    # on this leg, so a leg that skipped it must pass. Every other required block of the branch is satisfied, so a check that required
    # the item list here would fail this case and nothing else.
    REQUIRED_PASSES = (
        """
        coverage/tests/forwarded_capture
        coverage/tests/forwarded_capture_dropped
        coverage/tests/forwarded_capture_late
        coverage/tests/forwarded_capture_late_then
        coverage/tests/forwarded_capture_same_tick
        coverage/tests/forwarded_world_settled
        coverage/tests/forwarded_screen_open
        coverage/tests/forwarded_screen_width
        coverage/tests/forwarded_gui_left
        coverage/tests/forwarded_gui_top
        coverage/tests/forwarded_gui_width
        coverage/tests/forwarded_gui_height
        coverage/tests/forwarded_close_screen
        coverage/tests/forwarded_open_inventory
        coverage/tests/forwarded_press_screen_key
        coverage/tests/forwarded_scroll_screen
        coverage/tests/client_saved_number
        coverage/tests/coverage_frame_saved_number
        coverage/tests/coverage_frame_saved_list_energy
        coverage/tests/coverage_mesh_saved_number
        coverage/tests/coverage_mesh_saved_reinit
        coverage/tests/coverage_mesh_frame_saved_number
        coverage/tests/use_block_effects
        coverage/tests/use_block_faces
        buildcraft/tests/redstone_engine_wrench
        buildcraft/tests/wooden_pipe_wrench
        coverage/tests/decline_use_places
        coverage/tests/decline_use
        coverage/tests/sound
        coverage/tests/click_screen_release
        coverage/tests/hook_forced_normalization
        buildcraft/tests/cobblestone_pipe_shapes
        buildcraft/tests/wooden_pipe_items
        buildcraft/tests/wooden_pipe_shapes
        coverage/tests/camera
        coverage/tests/test_origin_follows_the_test_player
        coverage/tests/remote_gui
        coverage/tests/item_icon
        coverage/tests/gui_builder
        coverage/tests/gui_click
        coverage/tests/gui_click_empty
        coverage/tests/gui_click_twice
        buildcraft/tests/autocrafting_table
        buildcraft/tests/redstone_engine_item
    """.split()
        + OVERLAY_DRAWN_TESTS
    )

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        scripts = self.tmp / ".github" / "scripts"
        scripts.mkdir(parents=True)
        stage_scripts(scripts, "run-multiplayer.sh", "pin-llvmpipe.sh", "retryable-failure.sh")

        self.loader = self.tmp / "loaders" / self.LOADER
        self.loader.mkdir(parents=True)
        (self.loader / "test-save.txt").write_text("fake.zip\n")
        # A standalone loader: root.gradle sends the script down the runServer path.
        (self.loader / "root.gradle").write_text("")
        self.set_executable(self.tmp / "gradlew", "#!/usr/bin/env bash\nexit 0\n")
        self.set_executable(self.loader / "gradlew", STANDALONE_GRADLEW)

        saves = self.tmp / "test-saves"
        saves.mkdir()
        with zipfile.ZipFile(saves / "fake.zip", "w") as archive:
            archive.writestr("world/level.dat", b"")

        self.lines_file = self.tmp / "server-lines.log"

    def set_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def server_lines(self):
        lines = [f"[GRUG CI] PASS {path}-Test.grug" for path in self.REQUIRED_PASSES]
        lines += [
            f"[GRUG MULTIPLAYER] {path}-Test.grug drew the overlay" for path in OVERLAY_DRAWN_TESTS
        ]
        lines.append("[GRUG CI] ALL 50 TESTS PASSED")
        return "\n".join(lines) + "\n"

    def run_script(self, extra_env=None):
        env = dict(os.environ)
        env.update(
            {
                "DISPLAY": ":0",
                "FAKE_SERVER_LINES_FILE": str(self.lines_file),
                "GRUG_CI_BUILD_TIMEOUT": "30",
                "GRUG_CI_RUN_TIMEOUT": "60",
            }
        )
        env.update(extra_env or {})
        env.pop("JAVA_HOME", None)
        env.pop("GRUG_ORNITHE_SERVER_HASH", None)
        return subprocess.run(
            [str(self.tmp / ".github" / "scripts" / "run-multiplayer.sh"), self.LOADER],
            cwd=self.tmp,
            env=env,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=120,
        )

    def test_a_leg_whose_list_names_the_item_list_passes_without_it(self):
        self.lines_file.write_text(self.server_lines())
        result = self.run_script()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("run on the dedicated server with a client connected.", result.stdout)
        log = (self.loader / "run-server-ci.log").read_text()
        self.assertNotIn("jei/tests/item_list_forced_normalization-Test.grug", log)
        # The overlay pair is required on every leg now, so this one must have run them.
        self.assertIn("coverage/tests/overlay-Test.grug", log)
        self.assertIn("jei/tests/jei-Test.grug", log)

    def test_a_server_that_writes_its_last_line_and_exits_inside_a_pass_passes(self):
        # The poll read the log and then tested the server, so a line that landed between the two was
        # reported as "The dedicated server exited without an ALL n TESTS PASSED line": main's retry
        # leg died that way once (#1715). A grep shim holds the pass just after its success read, and
        # the fake server writes every line and exits during the hold, which is that window made
        # deterministic. The pass samples the server before it reads the log for that reason, as
        # run-loader.sh does since #597, so the line it could not see yet is read on the next pass.
        ready = self.tmp / "server-ready"
        signal = self.tmp / "server-signal"
        shim = self.tmp / "shim"
        shim.mkdir()
        grep = shim / "grep"
        grep.write_text(
            "#!/usr/bin/env bash\n"
            '/usr/bin/grep "$@"; status=$?\n'
            'case "$*" in\n'
            '  *"panicked at"*)\n'
            f'    if [ -e "{ready}" ] && [ ! -e "{signal}" ]; then\n'
            f'      : > "{signal}"\n'
            "      sleep 1.5\n"
            "    fi\n"
            "    ;;\n"
            "esac\n"
            "exit $status\n"
        )
        grep.chmod(0o755)
        self.lines_file.write_text(self.server_lines())

        result = self.run_script(
            {
                "FAKE_SERVER_READY_FILE": str(ready),
                "FAKE_SERVER_SIGNAL_FILE": str(signal),
                "PATH": f"{shim}:/usr/bin:/bin:/usr/local/bin",
            }
        )

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("run on the dedicated server with a client connected.", result.stdout)

    def test_the_fake_run_reports_no_missing_file_error(self):
        # run-multiplayer.sh sources pin-llvmpipe.sh at start. A fixture that stages the script
        # without that file prints a "No such file or directory" line into every fake run's stderr,
        # which is the stream a failing assertion here prints, so it hid this file's own cause once
        # (#1715). Sourcing the pin only exports two Mesa variables in the script's own process.
        self.lines_file.write_text(self.server_lines())
        result = self.run_script()

        self.assertEqual(0, result.returncode, result.stderr)
        self.assertNotIn("No such file or directory", result.stderr)
        self.assertIn("llvmpipe pinned: GALLIUM_OVERRIDE_CPU_CAPS=sse4.1", result.stdout)


class ClientBuildRetryTest(unittest.TestCase):
    """The joined client's build gets one retry, and only for a download (#1702).

    A dedicated leg builds its client with Gradle of its own after the server is ready, so a Loom
    downloadAssets failure ends that build and the leg reports "The client exited before the server
    started the tests" on its first attempt. run-loader.sh has always retried exactly that failure
    for the server's build and its own poll loop, so the client's build gets the same one retry.
    """

    LOADER = ServerUnlistedTest.LOADER
    REQUIRED_PASSES = ServerUnlistedTest.REQUIRED_PASSES

    # The downloadAssets failure of #700, as the b1.7.3-stationapi leg's client log held it (#1702).
    DOWNLOAD_FAILURE = """\
> Task :downloadAssets FAILED
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':downloadAssets' (registered by plugin 'fabric-loom').
> net.fabricmc.loom.util.download.DownloadException: Failed to download file from (https://resources.download.minecraft.net/99/991b421dfd401f115241601b2b373140a8d78572) to (.../run/resources/icons/minecraft.icns)

* Exception is:
org.gradle.api.tasks.TaskExecutionException: Execution failed for task ':downloadAssets'.
\tSuppressed: net.fabricmc.loom.util.download.DownloadException: Failed download after 3 attempts
"""

    # The same failure on the retry, with a line of its own so a case can tell which attempt's log
    # the leg reported from.
    SECOND_DOWNLOAD_FAILURE = """\
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':downloadAssets'.
> Failed to download the icon of the second attempt
> net.fabricmc.loom.util.download.DownloadException: Failed to download
"""

    # The code failing, which a retry would only delay.
    COMPILE_ERROR = """\
> Task :compileJava
loaders/b1.7.3-stationapi/src/main/java/net/grug/Example.java:12: error: cannot find symbol
    Grug.nope();
        ^
1 error
FAILURE: Build failed with an exception.

* What went wrong:
Execution failed for task ':compileJava'.
> Compilation failed; see the compiler error output for details.
"""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        scripts = self.tmp / ".github" / "scripts"
        scripts.mkdir(parents=True)
        for name in ("run-multiplayer.sh", "pin-llvmpipe.sh", "retryable-failure.sh"):
            shutil.copy(SCRIPTS / name, scripts / name)

        self.loader = self.tmp / "loaders" / self.LOADER
        self.loader.mkdir(parents=True)
        (self.loader / "test-save.txt").write_text("fake.zip\n")
        # A standalone loader: root.gradle sends the script down the runServer path.
        (self.loader / "root.gradle").write_text("")
        self.set_executable(self.tmp / "gradlew", "#!/usr/bin/env bash\nexit 0\n")
        self.set_executable(self.loader / "gradlew", STANDALONE_GRADLEW)

        saves = self.tmp / "test-saves"
        saves.mkdir()
        with zipfile.ZipFile(saves / "fake.zip", "w") as archive:
            archive.writestr("world/level.dat", b"")

        self.lines_file = self.tmp / "server-lines.log"
        self.failures = self.tmp / "client-failures"
        self.failures.mkdir()
        self.client_started = self.tmp / "client-started"

    def set_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def client_failure(self, attempt, log):
        (self.failures / f"{attempt}.log").write_text(log)

    def server_lines(self):
        lines = [f"[GRUG CI] PASS {path}-Test.grug" for path in self.REQUIRED_PASSES]
        lines += [
            f"[GRUG MULTIPLAYER] {path}-Test.grug drew the overlay" for path in OVERLAY_DRAWN_TESTS
        ]
        lines.append("[GRUG CI] ALL 50 TESTS PASSED")
        return "\n".join(lines) + "\n"

    def run_script(self, run_timeout=60, client_sleep=0):
        self.lines_file.write_text(self.server_lines())
        env = dict(os.environ)
        env.update(
            {
                "DISPLAY": ":0",
                "FAKE_SERVER_LINES_FILE": str(self.lines_file),
                "FAKE_CLIENT_FAILURES": str(self.failures),
                "FAKE_CLIENT_STARTED": str(self.client_started),
                "FAKE_CLIENT_SLEEP": str(client_sleep),
                "GRUG_CI_BUILD_TIMEOUT": "30",
                "GRUG_CI_RUN_TIMEOUT": str(run_timeout),
            }
        )
        env.pop("JAVA_HOME", None)
        env.pop("GRUG_ORNITHE_SERVER_HASH", None)
        return subprocess.run(
            [str(self.tmp / ".github" / "scripts" / "run-multiplayer.sh"), self.LOADER],
            cwd=self.tmp,
            env=env,
            capture_output=True,
            text=True,
            stdin=subprocess.DEVNULL,
            timeout=120,
        )

    def client_attempts(self):
        return int((self.failures / "count").read_text().strip())

    def client_log(self):
        return (self.loader / "run-client-ci.log").read_text()

    def kept_log(self, attempt):
        return self.loader / f"run-client-ci.log.attempt-{attempt}"

    def test_a_client_download_failure_is_retried_and_the_leg_passes(self):
        self.client_failure(1, self.DOWNLOAD_FAILURE)
        result = self.run_script()

        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("run on the dedicated server with a client connected.", result.stdout)
        self.assertIn("The client failed fetching what it needs, not on the code", result.stderr)
        self.assertIn("Retrying the client, attempt 2 of 2", result.stderr)
        self.assertEqual(2, self.client_attempts())
        # The failed attempt's log is kept beside the one that decided the leg, so the hiccup is
        # still readable after a retry that passed.
        self.assertIn("DownloadException", self.kept_log(1).read_text())

    def test_a_client_compile_error_is_not_retried(self):
        self.client_failure(1, self.COMPILE_ERROR)
        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("The client exited before the server started the tests", result.stderr)
        self.assertNotIn("Retrying the client", result.stderr)
        self.assertEqual(1, self.client_attempts())
        self.assertFalse(self.kept_log(1).exists(), "a compile error kept a retry's log")
        # Gradle's account of the failure reaches the job log, not only the artifact.
        self.assertIn("Compilation failed", result.stderr)

    def test_a_client_that_started_the_game_is_not_repeated(self):
        # A run phase's log is the game's own, so a line in it that reads like a download failure
        # could be the game's: a client that reached runClient is never built again, as
        # run-loader.sh holds for its own run phase. Both shapes Gradle prints for that task keep
        # the guard, the standalone loader's and a nested project's.
        for started in ("> Task :runClient", "> Task :loaders:1.20.6-forge:runClient"):
            with self.subTest(started=started):
                (self.failures / "count").unlink(missing_ok=True)
                self.client_failure(1, started + "\n" + self.DOWNLOAD_FAILURE)
                result = self.run_script()

                self.assertEqual(result.returncode, 1, result.stdout)
                self.assertNotIn("Retrying the client", result.stderr)
                self.assertEqual(1, self.client_attempts())

    def test_a_retry_that_fails_again_fails_the_leg_with_the_second_log(self):
        self.client_failure(1, self.DOWNLOAD_FAILURE)
        self.client_failure(2, self.SECOND_DOWNLOAD_FAILURE)
        result = self.run_script()

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("The client exited before the server started the tests", result.stderr)
        # The job log names the failure of the attempt that decided the leg, which is the second one.
        self.assertIn("Failed to download the icon of the second attempt", result.stderr)
        self.assertEqual(2, self.client_attempts())
        self.assertIn("991b421dfd401f115241601b2b373140a8d78572", self.kept_log(1).read_text())
        self.assertIn("Failed to download the icon of the second attempt", self.client_log())
        # Never a third attempt, and so never a second kept log.
        self.assertFalse(self.kept_log(2).exists(), "the leg made a third attempt")

    def test_a_retry_is_not_started_after_the_run_deadline_has_passed(self):
        # A retry is a whole Gradle build, so one started past the run's deadline is killed by the
        # next pass, and the leg reported "Timed out" instead of the client failure the check had
        # just identified from the log. The client here dies a second after the launch, and the poll
        # after the two-second deadline finds it dead, which is the only way this is reached without
        # a timeout of its own: a client still alive at the deadline times the leg out.
        self.client_failure(1, self.DOWNLOAD_FAILURE)
        result = self.run_script(run_timeout=2, client_sleep=1)

        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("Not retrying the client: the run's 2s deadline has passed", result.stderr)
        self.assertNotIn("Retrying the client, attempt", result.stderr)
        self.assertEqual(1, self.client_attempts())
        self.assertFalse(self.kept_log(1).exists(), "a retry past the deadline kept a log")
        # The leg reports the failure the check identified, not a timeout.
        self.assertIn("The client exited before the server started the tests", result.stderr)
        self.assertIn("DownloadException", result.stderr)
        self.assertNotIn("Timed out after", result.stderr)


class AttemptLogUploadTest(unittest.TestCase):
    """The logs a retry keeps reach the artifact, not only the job log (#1702).

    GitHub's upload-artifact reads each path line as a glob, so a kept log is in the artifact only
    when a line matches its name. Nothing is uploaded here: the workflow is read, and each glob is
    matched against the name the script writes, as the upload would.
    """

    # A token standing in for the job's matrix or step output, so one path serves every job.
    TOKEN = "matrix-value"

    @classmethod
    def setUpClass(cls):
        import yaml

        cls.jobs = yaml.safe_load((SCRIPTS.parent / "workflows" / "build.yml").read_text())["jobs"]

    def upload_globs(self, job, step_name):
        for step in self.jobs[job]["steps"]:
            if step.get("name") == step_name:
                lines = step["with"]["path"].splitlines()
                return [
                    re.sub(r"\$\{\{[^}]*\}\}", self.TOKEN, line.strip())
                    for line in lines
                    if line.strip() and not line.strip().startswith("#")
                ]
        self.fail(f"{job} has no {step_name} step")

    def assert_uploaded(self, job, step_name, name_on_disk):
        target = f"loaders/{self.TOKEN}/{name_on_disk}"
        globs = self.upload_globs(job, step_name)
        matched = [pattern for pattern in globs if fnmatch.fnmatchcase(target, pattern)]
        self.assertTrue(matched, f"{target} matches none of {job}'s upload paths: {globs}")

    def test_the_multiplayer_upload_carries_the_client_attempt_log(self):
        self.assert_uploaded(
            "multiplayer", "Upload logs and crash reports", "run-client-ci.log.attempt-1"
        )

    def test_every_upload_of_a_loader_log_carries_its_build_attempt_log(self):
        # build.yml uploads run-loader.sh's logs from three jobs, and the retry keeps the failed
        # attempt under the same name in each of them.
        for job in ("build", "reference", "windows"):
            with self.subTest(job=job):
                self.assert_uploaded(
                    job, "Upload logs and crash reports", "run/ci-build.log.attempt-1"
                )


def main(argv: list[str]) -> int:
    """Runs every case in a process of its own, side by side, unless argv names cases.

    The fake server's sleeps are the whole cost of the file (184 s one case after another), and
    each case builds its own fake repository and temporary directory, so the cases share nothing.
    GRUG_TEST_JOBS, or --jobs N, sets the number of processes; a case named on the command line,
    or unittest's own options, run in this process one at a time, the way the file used to.
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
