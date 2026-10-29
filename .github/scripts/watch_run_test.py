#!/usr/bin/env python3
"""Tests .github/scripts/watch-run.sh against a fake `gh` that replays canned run states.

The point of the script is to stop at the first failed job while others still run, so the cases are
a failure with a job still in progress, a green run, and a run that turns green on the second poll.
"""

import json
import os
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "watch-run.sh"

FAKE_GH = """#!/usr/bin/env bash
if [ "$1" = "api" ]; then
  seq 1 100 | sed 's/^/log line /'
  echo "##[error]Process completed with exit code 1."
  seq 101 120 | sed 's/^/post step /'
  exit 0
fi
dir="$FAKE_GH_DIR"
n=$(cat "$dir/count" 2>/dev/null || echo 0)
echo $((n + 1)) > "$dir/count"
nf=$(cat "$dir/failures" 2>/dev/null || echo 0)
if [ "$n" -lt "$nf" ]; then
  echo "Get https://api.github.com: net/http: TLS handshake timeout" >&2
  exit 1
fi
n=$((n - nf))
f="$dir/state$n.json"
[ -f "$f" ] || f="$dir/state$((n - 1)).json"
cat "$f"
"""


def job(job_id, name, status, conclusion):
    return {"databaseId": job_id, "name": name, "status": status, "conclusion": conclusion}


class WatchRunTest(unittest.TestCase):
    def run_script(self, states, failed_polls=0, max_failed_polls="10"):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            gh = tmp / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o755)
            for i, state in enumerate(states):
                (tmp / f"state{i}.json").write_text(json.dumps(state))
            if failed_polls:
                (tmp / "failures").write_text(str(failed_polls))
            env = dict(
                os.environ,
                PATH=f"{tmp}:{os.environ['PATH']}",
                FAKE_GH_DIR=str(tmp),
                WATCH_RUN_INTERVAL="0",
                WATCH_RUN_MAX_FAILED_POLLS=max_failed_polls,
            )
            return subprocess.run(
                ["bash", str(SCRIPT), "123"], env=env, capture_output=True, text=True, timeout=30
            )

    def test_stops_at_first_failure_while_other_jobs_run(self):
        result = self.run_script(
            [
                {
                    "status": "in_progress",
                    "jobs": [
                        job(1, "ok", "completed", "success"),
                        job(2, "1.20.6-forge", "completed", "failure"),
                        job(3, "slow", "in_progress", None),
                    ],
                }
            ]
        )
        self.assertEqual(result.returncode, 1)
        self.assertIn("1.20.6-forge", result.stdout)
        self.assertIn("log line 100", result.stdout)
        self.assertIn("##[error]", result.stdout)
        self.assertIn("log line 22", result.stdout)
        self.assertNotIn("log line 21\n", result.stdout)
        self.assertNotIn("post step", result.stdout)

    def test_green_run_exits_zero_and_skipped_counts_as_pass(self):
        result = self.run_script(
            [
                {
                    "status": "completed",
                    "conclusion": "success",
                    "jobs": [
                        job(1, "a", "completed", "success"),
                        job(2, "b", "completed", "skipped"),
                    ],
                }
            ]
        )
        self.assertEqual(result.returncode, 0, result.stdout)

    def test_waits_until_jobs_finish(self):
        result = self.run_script(
            [
                {"status": "in_progress", "jobs": [job(1, "a", "in_progress", None)]},
                {
                    "status": "completed",
                    "conclusion": "success",
                    "jobs": [job(1, "a", "completed", "success")],
                },
            ]
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("1 job(s) not finished", result.stdout)

    def test_a_failed_poll_is_tried_again_and_says_nothing_about_the_run(self):
        green = {
            "status": "completed",
            "conclusion": "success",
            "jobs": [job(1, "a", "completed", "success")],
        }
        result = self.run_script([green], failed_polls=3)
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("poll failed (3 of 10)", result.stdout)

    def test_gives_up_with_its_own_status_when_github_stays_unreachable(self):
        green = {
            "status": "completed",
            "conclusion": "success",
            "jobs": [job(1, "a", "completed", "success")],
        }
        result = self.run_script([green], failed_polls=50, max_failed_polls="4")
        # Not 0 and not the 1 of a failed run: the run was never seen.
        self.assertEqual(result.returncode, 3, result.stdout)
        self.assertIn("poll failed (4 of 4)", result.stdout)
        self.assertIn("says nothing about the run", result.stderr)

    def test_completed_run_without_jobs_is_not_green_unless_it_succeeded(self):
        result = self.run_script([{"status": "completed", "conclusion": "cancelled", "jobs": []}])
        self.assertEqual(result.returncode, 1, result.stdout)
        self.assertIn("cancelled", result.stdout)


if __name__ == "__main__":
    unittest.main()
