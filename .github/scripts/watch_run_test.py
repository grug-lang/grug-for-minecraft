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
if [ "$3" = "--job" ]; then
  seq 1 100 | sed 's/^/log line /'
  exit 0
fi
dir="$FAKE_GH_DIR"
n=$(cat "$dir/count" 2>/dev/null || echo 0)
echo $((n + 1)) > "$dir/count"
f="$dir/state$n.json"
[ -f "$f" ] || f="$dir/state$((n - 1)).json"
cat "$f"
"""


def job(job_id, name, status, conclusion):
    return {"databaseId": job_id, "name": name, "status": status, "conclusion": conclusion}


class WatchRunTest(unittest.TestCase):
    def run_script(self, states):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            gh = tmp / "gh"
            gh.write_text(FAKE_GH)
            gh.chmod(0o755)
            for i, state in enumerate(states):
                (tmp / f"state{i}.json").write_text(json.dumps(state))
            env = dict(
                os.environ,
                PATH=f"{tmp}:{os.environ['PATH']}",
                FAKE_GH_DIR=str(tmp),
                WATCH_RUN_INTERVAL="0",
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
        self.assertIn("log line 21", result.stdout)
        self.assertNotIn("log line 20\n", result.stdout)

    def test_green_run_exits_zero_and_skipped_counts_as_pass(self):
        result = self.run_script(
            [
                {
                    "status": "completed",
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
                {"status": "completed", "jobs": [job(1, "a", "completed", "success")]},
            ]
        )
        self.assertEqual(result.returncode, 0, result.stdout)
        self.assertIn("1 job(s) not finished", result.stdout)


if __name__ == "__main__":
    unittest.main()
