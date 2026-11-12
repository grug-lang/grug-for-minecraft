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
"""

import os
import shutil
import subprocess
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
LOADER = "b1.7.3-ornithe"
SERVER_VERSION = "b1.7.3"

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

LOADER_GRADLEW = """\
#!/usr/bin/env bash
mkdir -p build/layer
"$FAKE_REAL_PYTHON" -c 'import sys, zipfile
with zipfile.ZipFile(sys.argv[1], "w") as archive:
    archive.writestr("fabric.mod.json", b"{}")' build/layer/grug-@LOADER@.jar
""".replace("@LOADER@", LOADER)

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

JAVA = """\
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
        shutil.copy(SCRIPTS / "run-multiplayer.sh", scripts / "run-multiplayer.sh")
        shutil.copy(SCRIPTS / "universal_jar.py", scripts / "universal_jar.py")

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

    def set_executable(self, path, content):
        path.write_text(content)
        path.chmod(0o755)

    def run_script(self, server_jar="full"):
        env = dict(os.environ)
        env.update(
            {
                "PATH": f"{self.bin}{os.pathsep}{env['PATH']}",
                "DISPLAY": ":0",
                "FAKE_REAL_PYTHON": sys.executable,
                "FAKE_CURL_SOURCE": str(self.installer),
                "FAKE_INSTALL_SENTINEL": str(self.sentinel),
                "FAKE_INSTALL_SERVER_JAR": server_jar,
                # A launch that never becomes ready ends the case once the loop looks.
                "GRUG_CI_BUILD_TIMEOUT": "1",
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


if __name__ == "__main__":
    unittest.main()
