#!/usr/bin/env python3
"""Tests the dead url check.

The classification is the part that decides whether a build fails, so it is worth pinning that a 404
fails, that a 5xx and a 403 do not, and that the recreation artifacts are left to recreation.py. None
of these tests touch the network: every check goes through an injected fetch.
"""

import json
import tempfile
import unittest
from pathlib import Path

import dead_links


class FakeFetch:
    """Stands in for the network, returning a canned status per url."""

    def __init__(self, statuses: dict):
        self.statuses = statuses
        self.requested: list = []

    def __call__(self, url: str) -> tuple:
        self.requested.append(url)
        if url not in self.statuses:
            raise AssertionError(f"unexpected url checked: {url}")
        status = self.statuses[url]
        if status is None:
            return None, "URLError: <urlopen error [Errno -2] Name or service not known>"
        return status, f"HTTP {status} via HEAD"


class Clock:
    """A monotonic clock and sleep pair that never really sleeps."""

    def __init__(self):
        self.now = 0.0
        self.slept: list = []

    def monotonic(self) -> float:
        return self.now

    def sleep(self, seconds: float) -> None:
        self.slept.append(seconds)
        self.now += seconds

    @property
    def total_slept(self) -> float:
        return sum(self.slept)


def write_mod(directory: Path, name: str, data: dict) -> Path:
    mods = directory / name
    mods.mkdir(parents=True)
    about = mods / "about.json"
    about.write_text(json.dumps(data), encoding="utf-8")
    return about


class CollectUrlsTest(unittest.TestCase):
    def test_finds_urls_in_every_location_the_metadata_uses(self):
        data = {
            "repository": {"url": "https://example.com/repo"},
            "licenses": [{"url": "https://example.com/license"}],
            "recreation": {"artifacts": [{"url": "https://example.com/jar"}]},
            "derived_from": [
                {
                    "repository": {"url": "https://example.com/upstream"},
                    "licenses": [{"url": "https://example.com/upstream-license"}],
                    "sources": ["https://example.com/a", "https://example.com/b"],
                }
            ],
        }
        found = dict(dead_links.collect_urls(data))
        self.assertEqual(
            found,
            {
                ".repository.url": "https://example.com/repo",
                ".licenses[0].url": "https://example.com/license",
                ".recreation.artifacts[0].url": "https://example.com/jar",
                ".derived_from[0].repository.url": "https://example.com/upstream",
                ".derived_from[0].licenses[0].url": "https://example.com/upstream-license",
                ".derived_from[0].sources[0]": "https://example.com/a",
                ".derived_from[0].sources[1]": "https://example.com/b",
            },
        )

    def test_ignores_strings_that_are_not_urls(self):
        data = {
            "version": "1.0.0",
            "minecraft": "b1.7.3",
            "block": "grug:block/wooden_pipe",
            "sha256": "0" * 64,
            "empty": None,
        }
        self.assertEqual(dead_links.collect_urls(data), [])

    def test_only_collects_a_url_that_is_the_whole_string(self):
        # A url embedded in prose is left alone on purpose. Pulling one out of a sentence needs
        # trailing-punctuation rules to be trustworthy, and a link in a deviation note is worth less
        # than one in a sources list, so collecting url fields is the predictable half.
        data = {"note": "see https://example.com/in-prose for context"}
        self.assertEqual(dead_links.collect_urls(data), [])

    def test_surrounding_whitespace_is_tolerated(self):
        self.assertEqual(
            dead_links.collect_urls({"a": "  https://example.com/x\n"}),
            [(".a", "https://example.com/x")],
        )

    def test_accepts_http_as_well_as_https(self):
        self.assertEqual(
            dead_links.collect_urls({"a": "http://example.com/x"}),
            [(".a", "http://example.com/x")],
        )


class ClassifyTest(unittest.TestCase):
    def test_only_404_and_410_are_dead(self):
        for status in (404, 410):
            self.assertEqual(dead_links.classify(status), "dead", status)

    def test_a_server_error_is_not_dead(self):
        self.assertEqual(dead_links.classify(500), "unreachable")
        self.assertEqual(dead_links.classify(503), "unreachable")

    def test_a_refusal_is_not_dead(self):
        self.assertEqual(dead_links.classify(403), "unreachable")

    def test_success_and_redirects_are_alive(self):
        for status in (200, 204, 301, 302, 307, 308):
            self.assertEqual(dead_links.classify(status), "alive", status)

    def test_no_response_is_unreachable_not_dead(self):
        self.assertEqual(dead_links.classify(None), "unreachable")


class CoveredByRecreationTest(unittest.TestCase):
    def test_recreation_artifact_urls_are_left_to_recreation_py(self):
        self.assertTrue(dead_links.covered_by_recreation(".recreation.artifacts[0].url"))
        self.assertTrue(dead_links.covered_by_recreation(".recreation.artifacts[12].url"))

    def test_other_urls_are_checked_here(self):
        for path in (
            ".repository.url",
            ".licenses[0].url",
            ".derived_from[0].sources[0]",
            ".derived_from[0].repository.url",
        ):
            self.assertFalse(dead_links.covered_by_recreation(path), path)

    def test_a_similarly_shaped_path_is_not_skipped(self):
        # Only the artifact url is covered, so an artifact-adjacent field is still checked.
        self.assertFalse(dead_links.covered_by_recreation(".recreation.artifacts[0].homepage"))
        self.assertFalse(dead_links.covered_by_recreation(".recreation.url"))


class AllUrlsTest(unittest.TestCase):
    def test_splits_checked_from_covered(self):
        with tempfile.TemporaryDirectory() as directory:
            write_mod(
                Path(directory),
                "amod",
                {
                    "repository": {"url": "https://example.com/repo"},
                    "recreation": {"artifacts": [{"url": "https://example.com/a.jar"}]},
                },
            )
            checked, covered = dead_links.all_urls(Path(directory))
            self.assertEqual([url for url, _ in checked], ["https://example.com/repo"])
            self.assertEqual([triple[1] for triple in covered], [".recreation.artifacts[0].url"])

    def test_a_shared_url_is_requested_once_but_keeps_every_declaration(self):
        with tempfile.TemporaryDirectory() as directory:
            url = "https://example.com/shared"
            write_mod(Path(directory), "amod", {"repository": {"url": url}})
            write_mod(Path(directory), "bmod", {"repository": {"url": url}})
            checked, _ = dead_links.all_urls(Path(directory))
            self.assertEqual(len(checked), 1)
            self.assertEqual(
                checked[0][1],
                [("amod/about.json", ".repository.url"), ("bmod/about.json", ".repository.url")],
            )

    def test_reads_every_mod_in_the_directory(self):
        with tempfile.TemporaryDirectory() as directory:
            write_mod(Path(directory), "bmod", {"repository": {"url": "https://example.com/b"}})
            write_mod(Path(directory), "amod", {"repository": {"url": "https://example.com/a"}})
            checked, covered = dead_links.all_urls(Path(directory))
            # Sorted by mod directory name, so the report order is stable.
            self.assertEqual(
                [about for _, about_paths in checked for about, _ in about_paths],
                ["amod/about.json", "bmod/about.json"],
            )
            self.assertEqual(covered, [])

    def test_the_about_entry_is_relative_to_the_mods_directory(self):
        # Otherwise every report line carries the absolute checkout path, which differs per machine
        # and per CI run.
        with tempfile.TemporaryDirectory() as directory:
            nested = Path(directory) / "mods"
            write_mod(nested, "amod", {"repository": {"url": "https://example.com/a"}})
            checked, _ = dead_links.all_urls(nested)
            self.assertEqual([paths[0][0] for _, paths in checked], ["amod/about.json"])


class CheckAllTest(unittest.TestCase):
    def test_records_status_and_outcome_per_url(self):
        clock = Clock()
        fetch = FakeFetch(
            {
                "https://example.com/gone": 404,
                "https://example.com/busy": 503,
                "https://example.com/fine": 200,
            }
        )
        findings = dead_links.check_all(
            [
                ("https://example.com/gone", [("amod/about.json", ".a")]),
                ("https://example.com/busy", [("amod/about.json", ".b")]),
                ("https://example.com/fine", [("amod/about.json", ".c")]),
            ],
            fetch,
            delay=1.0,
            throttle=dead_links.HostThrottle(1.0, clock.monotonic, clock.sleep),
        )
        self.assertEqual([f.outcome for f in findings], ["dead", "unreachable", "alive"])
        self.assertEqual([f.status for f in findings], [404, 503, 200])
        self.assertEqual(
            [f.url for f in findings],
            [
                "https://example.com/gone",
                "https://example.com/busy",
                "https://example.com/fine",
            ],
        )
        # All three are the same host, so the check paced them rather than bursting.
        self.assertEqual(clock.slept, [1.0, 1.0])

    def test_a_finding_carries_every_declaration_of_a_shared_url(self):
        fetch = FakeFetch({"https://example.com/shared": 404})
        findings = dead_links.check_all(
            [
                (
                    "https://example.com/shared",
                    [
                        ("amod/about.json", ".repository.url"),
                        ("bmod/about.json", ".repository.url"),
                    ],
                )
            ],
            fetch,
            delay=0.0,
        )
        self.assertEqual(len(findings), 1)
        self.assertEqual(
            findings[0].about_paths,
            [("amod/about.json", ".repository.url"), ("bmod/about.json", ".repository.url")],
        )

    def test_paces_requests_to_the_same_host_only(self):
        clock = Clock()
        fetch = FakeFetch(
            {
                "https://a.example.com/1": 200,
                "https://a.example.com/2": 200,
                "https://b.example.com/1": 200,
            }
        )
        dead_links.check_all(
            [
                ("https://a.example.com/1", [("m/about.json", ".a")]),
                ("https://a.example.com/2", [("m/about.json", ".b")]),
                ("https://b.example.com/1", [("m/about.json", ".c")]),
            ],
            fetch,
            delay=1.0,
            throttle=dead_links.HostThrottle(1.0, clock.monotonic, clock.sleep),
        )
        # Same host twice: one delay between them. The other host is first for that host, so free.
        self.assertEqual(clock.slept, [1.0])
        self.assertEqual(clock.total_slept, 1.0)

    def test_a_zero_delay_never_waits(self):
        clock = Clock()
        fetch = FakeFetch({"https://a.example.com/1": 200, "https://a.example.com/2": 200})
        dead_links.check_all(
            [
                ("https://a.example.com/1", [("m/about.json", ".a")]),
                ("https://a.example.com/2", [("m/about.json", ".b")]),
            ],
            fetch,
            delay=0.0,
            throttle=dead_links.HostThrottle(0.0, clock.monotonic, clock.sleep),
        )
        self.assertEqual(clock.slept, [])


class ReportTest(unittest.TestCase):
    def test_reports_dead_before_unreachable_with_path_and_url(self):
        findings = [
            dead_links.Finding(
                "https://example.com/x",
                503,
                "unreachable",
                "HTTP 503 via HEAD",
                [("amod/about.json", ".x")],
            ),
            dead_links.Finding(
                "https://example.com/y",
                404,
                "dead",
                "HTTP 404 via HEAD",
                [("amod/about.json", ".y")],
            ),
            dead_links.Finding(
                "https://example.com/z",
                200,
                "alive",
                "HTTP 200 via HEAD",
                [("amod/about.json", ".z")],
            ),
        ]
        report = dead_links.format_findings(findings)
        # 1 header per group, plus 3 lines per finding, for two findings.
        self.assertEqual(len(report), 8)
        joined = "\n".join(report)
        self.assertLess(joined.index("1 dead url(s)"), joined.index("1 unreachable url(s)"))
        # The alive url is not reported at all.
        self.assertNotIn("example.com/z", joined)
        # Both reported urls carry the about file and the json path, so they can be found and fixed.
        self.assertIn("declared at amod/about.json .x", joined)
        self.assertIn("declared at amod/about.json .y", joined)

    def test_a_shared_url_names_every_mod_that_declares_it(self):
        findings = [
            dead_links.Finding(
                "https://example.com/shared",
                404,
                "dead",
                "HTTP 404 via HEAD",
                [("amod/about.json", ".repository.url"), ("bmod/about.json", ".repository.url")],
            )
        ]
        joined = "\n".join(dead_links.format_findings(findings))
        self.assertIn("declared at amod/about.json .repository.url", joined)
        self.assertIn("declared at bmod/about.json .repository.url", joined)

    def test_a_clean_run_reports_nothing(self):
        findings = [
            dead_links.Finding(
                "https://example.com/a", 200, "alive", "HTTP 200", [("m/about.json", ".a")]
            )
        ]
        self.assertEqual(dead_links.format_findings(findings), [])

    def test_a_url_with_no_response_says_so(self):
        findings = [
            dead_links.Finding(
                "https://example.com/a",
                None,
                "unreachable",
                "URLError: boom",
                [("m/about.json", ".a")],
            )
        ]
        self.assertIn("no response", "\n".join(dead_links.format_findings(findings)))


class RepositoryUrlsTest(unittest.TestCase):
    """Checks the walk against the metadata this repository actually ships."""

    def setUp(self):
        self.mods = Path(__file__).resolve().parent / "mods"

    def test_every_mod_has_at_least_one_url_to_check(self):
        checked, _ = dead_links.all_urls(self.mods)
        self.assertTrue(checked)

    def test_the_recreation_artifacts_are_the_only_urls_skipped(self):
        checked, covered = dead_links.all_urls(self.mods)
        self.assertTrue(covered, "buildcraft has recreation artifacts, so this should skip some")
        for about, path, url in covered:
            self.assertIn(".recreation.artifacts[", path)

    def test_nothing_checked_is_already_an_artifact(self):
        checked, _ = dead_links.all_urls(self.mods)
        for url, about_paths in checked:
            for about, path in about_paths:
                self.assertFalse(dead_links.covered_by_recreation(path))

    def test_every_url_is_http_or_https(self):
        checked, covered = dead_links.all_urls(self.mods)
        for url, _ in checked:
            self.assertTrue(url.startswith(("http://", "https://")), url)
        for _, _, url in covered:
            self.assertTrue(url.startswith(("http://", "https://")), url)

    def test_the_derived_from_sources_are_checked(self):
        # The point of the issue: a preservation url nobody fetches rots unnoticed.
        checked, _ = dead_links.all_urls(self.mods)
        sources = [
            path
            for _, about_paths in checked
            for _, path in about_paths
            if ".derived_from[0].sources[" in path
        ]
        self.assertTrue(sources, "buildcraft records derived_from sources")

    def test_a_shared_repository_url_is_checked_once(self):
        # All three mods point at this repository, so it must be one request, not three. Getting
        # this wrong is what makes a check look like rate limiting.
        checked, _ = dead_links.all_urls(self.mods)
        repository = "https://github.com/grug-lang/grug-for-minecraft"
        matching = [url for url, _ in checked if url == repository]
        self.assertEqual(len(matching), 1)
        shared = dict(checked)[repository]
        self.assertEqual(len(shared), 3, "all three mods declare the repository url")
        self.assertEqual(
            sorted(about for about, _ in shared),
            ["buildcraft/about.json", "coverage/about.json", "examplemod/about.json"],
        )


if __name__ == "__main__":
    unittest.main()
