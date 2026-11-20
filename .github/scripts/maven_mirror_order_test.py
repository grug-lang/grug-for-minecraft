#!/usr/bin/env python3
"""Check that the Google Maven Central mirror is first, filtered, and precedes mavenCentral."""

import pathlib
import re
import unittest

REPO_ROOT = pathlib.Path(__file__).resolve().parents[2]

# Files that contain the Google Maven Central mirror.
MIRROR_FILES = [
    REPO_ROOT / "core" / "build.gradle.kts",
    REPO_ROOT / "gradle" / "jacoco-loader.gradle",
    REPO_ROOT / "loaders" / "1.2.5-forge" / "build.gradle",
    REPO_ROOT / "loaders" / "1.20.6-forge" / "build.gradle",
    REPO_ROOT / "loaders" / "b1.7.3-stationapi" / "build.gradle.kts",
]

MIRROR_URL = "https://maven-central.storage-download.googleapis.com/maven2/"
INCLUDE_GROUPS = (
    "org.jacoco",
    "org.ow2.asm",
    "org.junit",
    "org.junit.jupiter",
    "org.junit.platform",
    "org.opentest4j",
    "org.apiguardian",
)


def _find_block(text: str, start: int) -> tuple[int, int]:
    """Return the (start, end) indices of the brace-delimited block starting at start."""
    open_brace = text.find("{", start)
    depth = 1
    index = open_brace + 1
    while index < len(text) and depth > 0:
        char = text[index]
        if char == "{":
            depth += 1
        elif char == "}":
            depth -= 1
        index += 1
    return open_brace, index


def _find_repositories_block(text: str) -> tuple[int, int]:
    """Return the (start, end) indices of the top-level repositories block."""
    match = re.search(r"\brepositories\s*\{", text)
    if not match:
        raise ValueError("no repositories block found")
    return _find_block(text, match.start())


def _find_mirror_block(text: str, repo_start: int, repo_end: int) -> tuple[int, int]:
    """Return the (start, end) indices of the mirror's maven block inside the repositories block."""
    repo_text = text[repo_start:repo_end]
    url_match = re.search(re.escape(MIRROR_URL), repo_text)
    if not url_match:
        raise ValueError("no mirror URL found")
    url_index = url_match.start()
    # Find the nearest "maven {" or "maven(...) {" before the URL.
    maven_match = None
    for match in re.finditer(r"\bmaven\b", repo_text[:url_index]):
        maven_match = match
    if maven_match is None:
        raise ValueError("no mirror maven block found")
    start, end = _find_block(repo_text, maven_match.start())
    return repo_start + start, repo_start + end


class MavenMirrorOrderTest(unittest.TestCase):
    def test_mirror_before_central_and_filtered_to_org_jacoco(self):
        for path in MIRROR_FILES:
            with self.subTest(path=path):
                text = path.read_text()
                self.assertIn(MIRROR_URL, text, f"{path} does not declare the mirror")

                repo_start, repo_end = _find_repositories_block(text)
                mirror_start, mirror_end = _find_mirror_block(text, repo_start, repo_end)
                mirror_text = text[mirror_start:mirror_end]

                for group in INCLUDE_GROUPS:
                    pattern = re.compile(
                        rf"includeGroup\s*\(?\s*[\"\']{re.escape(group)}[\"\']\s*\)?"
                    )
                    self.assertRegex(
                        mirror_text,
                        pattern,
                        f"{path} does not restrict the mirror to {group}",
                    )

                central_match = re.search(r"\bmavenCentral\s*(?:\(\)|\{)", text)
                self.assertIsNotNone(central_match, f"{path} does not declare mavenCentral")
                self.assertLess(
                    mirror_start,
                    central_match.start(),
                    f"{path} places the mirror after mavenCentral",
                )


if __name__ == "__main__":
    unittest.main()
