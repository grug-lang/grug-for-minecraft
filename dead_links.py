#!/usr/bin/env python3
"""Dead source url detection for mod metadata.

An about.json pins the urls a port is built from: the upstream repository, the license text, the
recreation artifacts, and derived_from sources. Those rot silently, because a page that moved or was
taken down changes nothing about the build until somebody tries to follow it. This walks every mod's
about.json, collects every http(s) url together with the json path it came from, and reports the ones
that no longer resolve.

Collecting rather than hardcoding matters: the url-bearing fields are spread across repository,
licenses, and derived_from, and derived_from repeats the repository and license fields. Walking the
document for any http(s) string finds all of them today and any field added later, without this file
needing to know the schema.

The recreation artifacts are deliberately not walked. ``recreation.py verify-all`` already downloads
every ``recreation.artifacts[].url`` on every CI run, and it proves more than a status code: it
compares the bytes against a pinned sha256, so a 200 serving the wrong content fails there too.
Checking them again here would re-request several multi-megabyte jars per mod to learn strictly less,
and the per-host pacing would make that five sequential requests to one host for no added coverage.
So the covered urls are skipped and counted, rather than silently ignored.

Four decisions, made here rather than left open:

1. How hard to check. Every url on every run, with a per-host delay. The delay is what makes that
   safe: buildcraft alone names one host five times, and a burst against a small site is how a
   checker gets its host to block it. One second per host by default, configurable with --delay.

2. Fail or warn. A dead url fails the run by default; --warn-only downgrades that to a report.
   Failing is the right default because a dead url is a real metadata defect with a real fix
   (repoint it, or archive it), and it is the only thing that keeps rot from accumulating once
   nobody is reading the log. The failure is deliberately narrow, see (3), so an outage elsewhere
   cannot hold a change hostage.

3. What counts as dead. Only 404 and 410. A 5xx is the host having a bad day and a 403 is the host
   refusing this user agent; neither is evidence the page is gone, so both are reported as
   unreachable and neither fails the run. Redirects are followed and the final status decides, so a
   url that moved to a new page is alive. Unreachable urls are still printed, because "this host is
   down" is worth knowing even though it is not worth failing over.

4. Archive submission. Out of scope. Pushing a dead url to the Internet Archive is a separate action
   with its own failure modes and is tracked as its own issue; this only reports what needs one.
"""

import argparse
import json
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import NamedTuple

USER_AGENT = "grug-for-minecraft dead link check"

# Only these mean the page is gone. Anything else non-2xx is the host's problem, not the url's.
DEAD_STATUSES = frozenset({404, 410})

URL_PATTERN = re.compile(r"^https?://", re.IGNORECASE)

# A json path this module deliberately does not check, because recreation.py already does.
RECREATION_ARTIFACT_URL = re.compile(r"^\.recreation\.artifacts\[\d+\]\.url$")


class Finding(NamedTuple):
    """One url's outcome, with every place it is declared.

    about_paths is a list because a url shared by several mods is one request but several things to
    fix, and a report that names only the first mod would send someone looking in the wrong file.
    """

    url: str
    status: int | None
    outcome: str
    detail: str
    about_paths: list


class HostThrottle:
    """Spaces requests to the same host by delay seconds, leaving different hosts alone.

    monotonic and sleep are injectable so the pacing can be tested without spending real seconds.
    """

    def __init__(self, delay: float, monotonic=time.monotonic, sleep=time.sleep):
        self._delay = delay
        self._monotonic = monotonic
        self._sleep = sleep
        self._last: dict = {}

    def wait(self, url: str) -> None:
        host = urllib.parse.urlsplit(url).netloc
        now = self._monotonic()
        previous = self._last.get(host)
        if previous is not None:
            remaining = self._delay - (now - previous)
            if remaining > 0:
                self._sleep(remaining)
        self._last[host] = self._monotonic()

    @property
    def hosts_seen(self) -> int:
        return len(self._last)


def collect_urls(node, path: str = "") -> list:
    """Every http(s) string in node as (json path, url) pairs, in document order.

    Recursing over the whole document rather than a fixed field list is what covers the url fields
    that repeat inside derived_from, and any field added later.
    """
    found = []
    if isinstance(node, dict):
        for key, value in node.items():
            found.extend(collect_urls(value, f"{path}.{key}"))
    elif isinstance(node, list):
        for index, value in enumerate(node):
            found.extend(collect_urls(value, f"{path}[{index}]"))
    elif isinstance(node, str):
        candidate = node.strip()
        if URL_PATTERN.match(candidate):
            found.append((path, candidate))
    return found


def about_urls(about_path: Path) -> list:
    """The (json path, url) pairs in one about.json."""
    return collect_urls(json.loads(about_path.read_text(encoding="utf-8")))


def covered_by_recreation(path: str) -> bool:
    """True when recreation.py already proves this url resolves, on stronger terms than this does."""
    return RECREATION_ARTIFACT_URL.match(path) is not None


def all_urls(mods_dir: Path) -> tuple:
    """Every url under mods_dir, grouped so a shared one is requested once.

    Returns (checked, covered). checked is a list of (url, [(about, path), ...]), so a url shared by
    several mods is requested once but reported everywhere it is declared; covered is a list of
    (about, path, url) triples for the recreation artifacts.

    The about entry is relative to mods_dir, so a report reads the same wherever the repository is
    checked out. The recreation artifacts are split out, because recreation.py verifies them by hash
    on every CI run, so the caller can report what it deliberately skipped rather than looking like
    it covered them.
    """
    groups: dict = {}
    covered = []
    for about_path in sorted(mods_dir.glob("*/about.json")):
        relative = about_path.relative_to(mods_dir).as_posix()
        for path, url in about_urls(about_path):
            if covered_by_recreation(path):
                covered.append((relative, path, url))
            else:
                groups.setdefault(url, []).append((relative, path))
    return list(groups.items()), covered


def classify(status: int | None) -> str:
    """alive, dead, or unreachable, from a final status code."""
    if status is None:
        return "unreachable"
    if status in DEAD_STATUSES:
        return "dead"
    if 200 <= status < 400:
        return "alive"
    return "unreachable"


def fetch_status(url: str, timeout: float) -> tuple:
    """The final status for url as (status or None, detail).

    Tries HEAD first and falls back to GET for the statuses that mean "this server does not do
    HEAD", which is cheaper than always downloading a jar just to learn the file is gone.
    """
    for method in ("HEAD", "GET"):
        request = urllib.request.Request(url, method=method, headers={"User-Agent": USER_AGENT})
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                return response.status, f"HTTP {response.status} via {method}"
        except urllib.error.HTTPError as error:
            if method == "HEAD" and error.code in (403, 405, 501):
                continue
            return error.code, f"HTTP {error.code} via {method}"
        except urllib.error.URLError as error:
            return None, f"{type(error.reason).__name__}: {error.reason}"
        except OSError as error:
            return None, f"{type(error).__name__}: {error}"
    return None, "no response"


def check_all(triples: list, fetch, delay: float, throttle=None) -> list:
    """Checks every distinct url with fetch, pacing per host.

    triples are (url, [(about, path), ...]) pairs as built by all_urls. Returns one Finding per url,
    carrying every about path that declared it.

    throttle is injectable so the pacing can be asserted without spending real seconds.
    """
    if throttle is None:
        throttle = HostThrottle(delay)
    findings = []
    for url, about_paths in triples:
        throttle.wait(url)
        status, detail = fetch(url)
        findings.append(Finding(url, status, classify(status), detail, about_paths))
    return findings


def format_findings(findings: list) -> list:
    """Human-readable lines for the findings that are not alive, dead first."""
    lines = []
    for outcome in ("dead", "unreachable"):
        group = [finding for finding in findings if finding.outcome == outcome]
        if not group:
            continue
        lines.append(f"{len(group)} {outcome} url(s):")
        for finding in group:
            status = finding.status if finding.status is not None else "no response"
            lines.append(f"  - {finding.url}")
            lines.append(f"      {status}: {finding.detail}")
            for about, path in finding.about_paths:
                lines.append(f"      declared at {about} {path}")
    return lines


def check_command(mods_dir: Path, delay: float, timeout: float, warn_only: bool) -> int:
    """Checks every mod under mods_dir and returns the exit code."""
    triples, covered = all_urls(mods_dir)
    if not triples:
        print(f"FAILED: no about.json urls found under {mods_dir}.", file=sys.stderr)
        return 1

    declared = sum(len(about_paths) for _, about_paths in triples)
    print(f"Checking {len(triples)} distinct url(s) from {declared} declaration(s) in {mods_dir}")
    print(f"Per-host delay {delay}s")
    if covered:
        print(
            f"Skipping {len(covered)} recreation artifact url(s), already hash-verified by"
            " recreation.py"
        )

    def fetch(url: str) -> tuple:
        return fetch_status(url, timeout)

    findings = check_all(triples, fetch, delay)

    alive = sum(1 for finding in findings if finding.outcome == "alive")
    print(f"{alive}/{len(findings)} distinct url(s) resolved")

    for line in format_findings(findings):
        print(line)

    dead = [finding for finding in findings if finding.outcome == "dead"]
    if not dead:
        return 0
    if warn_only:
        print(
            f"\n{len(dead)} dead url(s) (--warn-only, not failing). Repoint or archive them.",
            file=sys.stderr,
        )
        return 0
    print(f"\nFAILED: {len(dead)} dead url(s).", file=sys.stderr)
    return 1


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "mods",
        nargs="?",
        type=Path,
        default=Path("mods"),
        help="directory whose */about.json files to check",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=1.0,
        help="seconds to wait between requests to the same host (default: 1.0)",
    )
    parser.add_argument(
        "--timeout",
        type=float,
        default=30.0,
        help="per-request timeout in seconds (default: 30)",
    )
    parser.add_argument(
        "--warn-only",
        action="store_true",
        help="report dead urls but exit 0, for when the network cannot be trusted",
    )
    args = parser.parse_args()

    return check_command(args.mods, args.delay, args.timeout, args.warn_only)


if __name__ == "__main__":
    sys.exit(main())
