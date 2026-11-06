#!/usr/bin/env python3
"""Tests dispatch-base.py: a dispatch's base resolves to the full SHA of the commit it names (#1170).

The cases are the issue's four shapes: a full SHA, an abbreviated one the API resolves, a branch
name, and an abbreviated one that names no commit. The API is a fake `gh` first on PATH, as
watch_run_test.py fakes it, so a case says what the API answered without reaching one.
"""

import importlib.util
import os
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "dispatch-base.py"

# affected_mods_test.py already has the throwaway repository the commits are made in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

SLUG = "test/repo"
TREE = {
    "AGENTS.md": "agents\n",
    "mods/coverage/about.json": "{}",
}

# A fake `gh` with the API's answer in FAKE_GH_SHA, or its failure in FAKE_GH_FAILS, and its argv
# recorded in FAKE_GH_ARGS so a case can say which endpoint was read.
FAKE_GH = """#!/usr/bin/env bash
printf '%s\\n' "$@" > "$FAKE_GH_ARGS"
if [ -n "$FAKE_GH_FAILS" ]; then
  echo "$FAKE_GH_FAILS" >&2
  exit 1
fi
echo "$FAKE_GH_SHA"
"""


def git(path: Path, *args: str) -> str:
    """One git command in path, which must succeed, as its stdout."""
    done = subprocess.run(
        ["git", "-C", str(path), *args], capture_output=True, check=True, text=True
    )
    return done.stdout.strip()


class DispatchBaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        work = self.root / "work"
        work.mkdir()
        self.repo = repo_module.Repo(work)
        self.base = self.repo.commit(TREE, "base")
        self.bin = self.root / "bin"
        self.bin.mkdir()
        gh = self.bin / "gh"
        gh.write_text(FAKE_GH)
        gh.chmod(0o755)
        self.gh_args = self.root / "gh-args"

    def add_origin(self) -> Path:
        """A bare origin of this repository, which the checkout fetches a branch from."""
        bare = self.root / "origin.git"
        subprocess.run(
            ["git", "clone", "-q", "--bare", str(self.repo.path), str(bare)],
            capture_output=True,
            check=True,
        )
        self.repo.git("remote", "add", "origin", bare.as_uri())
        return bare

    def run_script(self, base: str, sha: str = "", fails: str = "") -> subprocess.CompletedProcess:
        """The script's result for base, with the API answering sha or failing with fails."""
        env = dict(
            os.environ,
            PATH=f"{self.bin}:{os.environ['PATH']}",
            FAKE_GH_ARGS=str(self.gh_args),
            FAKE_GH_SHA=sha,
            FAKE_GH_FAILS=fails,
            GITHUB_REPOSITORY=SLUG,
        )
        return subprocess.run(
            [sys.executable, str(SCRIPT), base, "--repo", str(self.repo.path)],
            capture_output=True,
            text=True,
            env=env,
        )

    def api_argv(self) -> list[str]:
        return self.gh_args.read_text().splitlines()

    # A full SHA is the commit itself, and nothing is asked about it.

    def test_a_full_sha_is_printed_unchanged(self):
        result = self.run_script(self.base.upper(), sha="f" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.base)
        self.assertIn("a full commit SHA", result.stderr)
        self.assertFalse(self.gh_args.exists(), "a full SHA must not be read through the API")

    # An abbreviated SHA cannot be fetched, so the API resolves it (#1170).

    def test_an_abbreviated_sha_resolves_through_the_api(self):
        result = self.run_script(self.base[:8], sha=self.base)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.base)
        self.assertIn("an abbreviated SHA", result.stderr)
        self.assertEqual(
            self.api_argv(), ["api", f"repos/{SLUG}/commits/{self.base[:8]}", "--jq", ".sha"]
        )

    def test_an_unresolvable_prefix_fails_the_dispatch_and_says_which_prefix(self):
        # A typo must not narrow nothing: the caller fails rather than run every job.
        prefix = "0" * 8
        result = self.run_script(prefix, fails="gh: Not Found (HTTP 404)")
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "")
        self.assertIn(f"'{prefix}' names no commit", result.stderr)
        self.assertIn("Not Found", result.stderr)

    def test_an_api_answer_that_is_no_full_sha_fails(self):
        result = self.run_script(self.base[:8], sha="not-a-sha")
        self.assertEqual(result.returncode, 1)
        self.assertIn("which is no full commit SHA", result.stderr)

    def test_an_api_answer_about_another_commit_fails(self):
        result = self.run_script(self.base[:8], sha="f" * 40)
        self.assertEqual(result.returncode, 1)
        self.assertIn("does not start with it", result.stderr)

    # A branch name is the commit it names, from this checkout or from origin.

    def test_a_branch_name_the_checkout_holds_resolves_without_a_fetch(self):
        self.repo.git("branch", "verification", self.base)
        result = self.run_script("verification", sha="f" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), self.base)
        self.assertFalse(self.gh_args.exists(), "a branch name is no sha, so the API is not read")

    def test_a_branch_name_only_origin_holds_is_fetched_and_resolves(self):
        self.add_origin()
        # A clone of origin makes the commit origin has and this checkout does not.
        peer = self.root / "peer"
        subprocess.run(
            ["git", "clone", "-q", str(self.root / "origin.git"), str(peer)],
            capture_output=True,
            check=True,
        )
        git(peer, "config", "user.email", "t@example.com")
        git(peer, "config", "user.name", "t")
        git(peer, "config", "commit.gpgsign", "false")
        (peer / "AGENTS.md").write_text("agents\n\nmore\n")
        git(peer, "commit", "-q", "-a", "-m", "ahead")
        sha = git(peer, "rev-parse", "HEAD")
        git(peer, "push", "-q", "origin", f"{sha}:refs/heads/feature")
        # The checkout holds no commit of that branch before the fetch.
        before = subprocess.run(
            ["git", "-C", str(self.repo.path), "cat-file", "-e", f"{sha}^{{commit}}"],
            capture_output=True,
        )
        self.assertNotEqual(before.returncode, 0)

        result = self.run_script("origin/feature", sha="f" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), sha)
        self.assertIn("the name 'origin/feature'", result.stderr)
        self.assertFalse(self.gh_args.exists(), "a branch name is no sha, so the API is not read")
        # The fetch the resolution made is what the diff the caller reads is taken from.
        after = subprocess.run(
            ["git", "-C", str(self.repo.path), "cat-file", "-e", f"{sha}^{{commit}}"],
            capture_output=True,
        )
        self.assertEqual(after.returncode, 0)

    def test_a_branch_name_origin_does_not_hold_fails(self):
        self.add_origin()
        result = self.run_script("no-such-branch", sha="f" * 40)
        self.assertEqual(result.returncode, 1)
        self.assertEqual(result.stdout.strip(), "")
        self.assertIn("'no-such-branch' names no commit", result.stderr)

    def test_a_hex_base_too_short_to_be_a_prefix_is_read_as_a_name(self):
        # Three characters cannot name a commit by prefix, and a branch may be called "abc", so it
        # is a name; the message then says the name rather than the prefix.
        self.add_origin()
        result = self.run_script("abc", sha="f" * 40)
        self.assertEqual(result.returncode, 1)
        self.assertIn("'abc' names no commit", result.stderr)
        self.assertFalse(self.gh_args.exists(), "too short to be a prefix, so the API is not read")

    # An empty base is not a failure: it is the full run a release and the four-hourly check send.

    def test_an_empty_base_prints_nothing_and_succeeds(self):
        result = self.run_script("", sha="f" * 40)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), "")
        self.assertIn("the full run", result.stderr)
        self.assertFalse(self.gh_args.exists())


if __name__ == "__main__":
    unittest.main()
