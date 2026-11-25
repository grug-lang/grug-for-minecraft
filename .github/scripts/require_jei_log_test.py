#!/usr/bin/env python3
"""Tests .github/scripts/require-jei-log.sh.

The job that installs JEI beside grug fails unless JEI logged "Starting JEI GUI" and logged no
mezz.jei error, which is how an item grug registers that JEI cannot read is caught. The game rolls
latest.log when the date changes, so a run that crosses midnight UTC writes that line into the
rotated file: a check of latest.log alone then fails a run whose tests all passed, which is what
#1775 fixed. This file pins the set the script reads, and the two failure messages a reader needs
told apart, since only the script's tests can prove it before a midnight-crossing run of the job
does.
"""

import gzip
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "require-jei-log.sh"

# The line JEI logs once its ingredient list is built, as it appears in a real 1.20.6-forge log.
STARTED = """\
[23:59:12] [Render thread/INFO] [mezz.jei.common.Internal]: Starting JEI
[23:59:13] [Render thread/INFO] [mezz.jei.gui.startup.JeiGuiStarter]: Starting JEI GUI
[23:59:13] [Render thread/INFO] [mezz.jei.gui.startup.JeiGuiStarter]: JEI started
"""

# A real log is far bigger than the 64 KB a pipe buffers, and the startup line comes early with
# thousands of lines after it, so a reader that stops at the first match kills the writer with
# SIGPIPE and `set -o pipefail` turns that into the pipeline's status: the check would call the line
# missing on a file that holds it. The passes below carry this padding after the marker for that
# reason. Both files of the failed run of #1775 were larger than the buffer, and a full run's
# latest.log reaches about 900 KB.
PADDING = "".join(
    f"[23:59:{i % 60:02d}] [Render thread/INFO] [GRUG CI]: line {i} of the run's log\n"
    for i in range(6000)
)

# What latest.log held after the roll: the tests' own lines and nothing from before midnight.
AFTER_ROLL = """\
[00:00:00] [Render thread/INFO] [GRUG CI]: PASS coverage/code/placement-Test.grug
[00:01:02] [Render thread/INFO] [GRUG CI]: all tests passed
"""

# JEI refusing an item it cannot read, which is the failure this check exists to catch. The line is
# the shape the job's own grep matches: a level, then mezz.jei as the logger.
JEI_ERROR = (
    "[23:59:14] [Render thread/ERROR] [mezz.jei.library.load.registration.RecipeRegistration]:"
    " Failed to register grug's item\n"
)


def run_check(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([str(SCRIPT), *args], capture_output=True, text=True, check=False)


class RequireJeiLogTest(unittest.TestCase):
    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)
        self.logs = self.tmp / "logs"
        self.logs.mkdir()

    def write_latest(self, contents: str) -> Path:
        path = self.logs / "latest.log"
        path.write_text(contents)
        return path

    def write_rotated(self, name: str, contents: str) -> Path:
        path = self.logs / name
        # The game's roll compresses the file it closes, so a rotated log is a real gzip stream.
        with gzip.open(path, "wt") as f:
            f.write(contents)
        return path

    def test_a_line_in_a_rotated_file_passes(self):
        # The failing run of #1775 in miniature: latest.log is the part after midnight and holds no
        # JEI line, and the rotated file holds the startup. This is the case the old check failed.
        self.write_latest(AFTER_ROLL)
        self.write_rotated("2026-11-24-1.log.gz", STARTED + PADDING)

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "")

    def test_a_line_in_latest_log_alone_passes(self):
        # The ordinary run, which never crosses midnight and so never rolls: no rotated file at all.
        self.write_latest(STARTED)

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "")

    def test_a_line_in_a_large_latest_log_passes(self):
        # The same run with a log the size of a real one, which is where a check that stops reading
        # at its first match reports the line missing rather than finding it.
        self.write_latest(STARTED + PADDING)

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
        self.assertEqual(result.stdout, "")

    def test_a_missing_line_names_the_files_it_read(self):
        self.write_latest(AFTER_ROLL)
        self.write_rotated(
            "2026-11-24-1.log.gz", "[23:59:12] [Render thread/INFO] [GRUG CI]: PASS\n"
        )

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 1)
        self.assertIn("never logged 'Starting JEI GUI'", result.stdout)
        # Which files it read is what tells the reader whether the set was the one it expected, so a
        # message naming only latest.log would hide the rotated file the line could have been in.
        self.assertIn("latest.log", result.stdout)
        self.assertIn("2026-11-24-1.log.gz", result.stdout)

    def test_a_mezz_error_in_a_rotated_file_fails(self):
        # The error is before midnight too, so the same roll hides it from latest.log: a check that
        # passed on the marker alone in latest.log would have reported this run green.
        self.write_latest(AFTER_ROLL)
        self.write_rotated("2026-11-24-1.log.gz", STARTED + JEI_ERROR)

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 1)
        self.assertIn("JEI logged the errors above", result.stdout)
        self.assertIn("Failed to register grug's item", result.stdout)
        # The line came out of a rotated file, and the message has to say which one.
        self.assertIn("2026-11-24-1.log.gz:", result.stdout)

    def test_a_mezz_error_in_latest_log_fails(self):
        self.write_latest(STARTED + JEI_ERROR)

        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 1)
        self.assertIn("JEI logged the errors above", result.stdout)
        self.assertIn("latest.log:", result.stdout)

    def test_a_missing_directory_says_the_log_set_is_missing(self):
        # A production launch that never wrote a log is a different defect from JEI refusing an item,
        # and reporting it as "JEI never logged" would send the reader looking in the wrong place.
        result = run_check(str(self.tmp / "no-such-logs"))

        self.assertEqual(result.returncode, 1)
        self.assertIn("the log set is missing", result.stdout)
        self.assertNotIn("never logged", result.stdout)

    def test_an_empty_directory_says_the_log_set_is_missing(self):
        result = run_check(str(self.logs))

        self.assertEqual(result.returncode, 1)
        self.assertIn("the log set is missing", result.stdout)
        self.assertNotIn("never logged", result.stdout)

    def test_no_argument_is_a_usage_error(self):
        result = run_check()

        self.assertEqual(result.returncode, 2)
        self.assertIn("Usage:", result.stderr)


if __name__ == "__main__":
    if not SCRIPT.exists():
        print(f"missing {SCRIPT}", file=sys.stderr)
        sys.exit(1)
    unittest.main()
