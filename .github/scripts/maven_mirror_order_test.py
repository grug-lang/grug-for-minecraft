#!/usr/bin/env python3
"""Check that every Central mirror is placed before mavenCentral and filtered to org.jacoco."""

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
    REPO_ROOT / "settings.gradle.kts",
]

MIRROR_URL = "https://maven-central.storage-download.googleapis.com/maven2/"
# mavenCentral() or mavenCentral { ... }
CENTRAL_PATTERN = re.compile(r"mavenCentral\s*(?:\(\)|\{)")
# Kotlin/Groovy: includeGroup("org.jacoco") or includeGroup 'org.jacoco'
INCLUDE_GROUP_PATTERN = re.compile(r"includeGroup\s*\(?\s*[\"\']org\.jacoco[\"\']\s*\)?")


class MavenMirrorOrderTest(unittest.TestCase):
    def test_mirror_before_central_and_filtered_to_org_jacoco(self):
        for path in MIRROR_FILES:
            with self.subTest(path=path):
                text = path.read_text()
                self.assertIn(MIRROR_URL, text, f"{path} does not declare the mirror")

                central_match = CENTRAL_PATTERN.search(text)
                self.assertIsNotNone(central_match, f"{path} does not declare mavenCentral")

                mirror_index = text.find(MIRROR_URL)
                central_index = central_match.start()
                self.assertLess(
                    mirror_index,
                    central_index,
                    f"{path} places the mirror after mavenCentral",
                )

                self.assertRegex(
                    text,
                    INCLUDE_GROUP_PATTERN,
                    f"{path} does not restrict the mirror to org.jacoco",
                )


if __name__ == "__main__":
    unittest.main()
