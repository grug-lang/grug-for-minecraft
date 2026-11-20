#!/usr/bin/env python3
"""Tests that loader legs keep their unit-test JaCoCo exec data through to upload.

The game run's JaCoCo hook used to delete the whole build/jacoco directory, which also removed
test.exec written by the loader's own :test task. The hook now deletes only the per-test segment
files (NNN-<name>.exec and NNN-<name>.dropped) and leaves test.exec in place, so the loader's
unit-test coverage reaches the combine step alongside its game-run data (#1454).
"""

import re
import unittest
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parent.parent.parent
WORKFLOW = REPO_ROOT / ".github" / "workflows" / "build.yml"
JACOCO_LOADER = REPO_ROOT / "gradle" / "jacoco-loader.gradle"
LOADER_125 = REPO_ROOT / "loaders" / "1.2.5-forge" / "build.gradle"

# The literal exact-match regex a gradle hook must use to delete only per-test segments.
SEGMENT_REGEX_LITERAL = r"[0-9]+-.*\.((exec)|(dropped))"


class JacocoUnitExecTest(unittest.TestCase):
    def test_workflow_uploads_loader_exec_files(self):
        """The loader coverage upload step accepts *.exec and *.dropped, including test.exec."""
        text = WORKFLOW.read_text()
        self.assertIn(
            "coverage-exec-${{ matrix.dir }}",
            text,
            "the loader coverage exec artifact must be uploaded",
        )
        self.assertIn(
            "loaders/${{ matrix.dir }}/build/jacoco/*.exec",
            text,
            "the loader upload must include every .exec file, including test.exec",
        )
        self.assertIn(
            "loaders/${{ matrix.dir }}/build/jacoco/*.dropped",
            text,
            "the loader upload must include every .dropped segment file",
        )

    def test_workflow_uploads_core_exec_file(self):
        """Core's unit-test exec is uploaded as the only coverage-exec-core file."""
        text = WORKFLOW.read_text()
        self.assertIn(
            "coverage-exec-core",
            text,
            "the core coverage exec artifact must be uploaded",
        )
        self.assertIn(
            "core/build/jacoco/test.exec",
            text,
            "the core upload must include its unit-test exec file",
        )

    def _deletion_block(self, path):
        """Return the cleanup block in a gradle file that iterates over execDir.listFiles."""
        text = path.read_text()
        match = re.search(
            r"execDir\.listFiles\(\)\?\.each\s*\{[^}]+project\.delete\([^}]+\}\s*\}",
            text,
            re.DOTALL,
        )
        if match is None:
            self.fail(f"no execDir.listFiles cleanup block found in {path}")
        return match.group(0)

    def test_jacoco_loader_does_not_delete_the_directory(self):
        """gradle/jacoco-loader.gradle never deletes the whole jacoco directory."""
        text = JACOCO_LOADER.read_text()
        self.assertNotIn(
            "project.delete(execDir)",
            text,
            "the shared jacoco hook must not delete the whole execDir",
        )
        self.assertNotIn(
            "execDir.deleteDir()",
            text,
            "the shared jacoco hook must not delete the whole execDir",
        )

    def test_jacoco_loader_only_deletes_segment_files(self):
        """gradle/jacoco-loader.gradle deletes only NNN-*.exec / NNN-*.dropped files."""
        block = self._deletion_block(JACOCO_LOADER)
        self.assertIn(
            SEGMENT_REGEX_LITERAL,
            block,
            "the shared jacoco hook must delete only per-test segment files",
        )
        # The same file must not still contain the old directory-level delete.
        self.assertNotIn("project.delete(execDir)", JACOCO_LOADER.read_text())

    def test_loader_125_does_not_delete_the_directory(self):
        """1.2.5's custom launch hook never deletes the whole jacoco directory."""
        text = LOADER_125.read_text()
        self.assertNotIn(
            "project.delete(execDir)",
            text,
            "the 1.2.5 launch hook must not delete the whole execDir",
        )
        self.assertNotIn(
            "execDir.deleteDir()",
            text,
            "the 1.2.5 launch hook must not delete the whole execDir",
        )

    def test_loader_125_only_deletes_segment_files(self):
        """1.2.5's custom launch hook deletes only NNN-*.exec / NNN-*.dropped files."""
        block = self._deletion_block(LOADER_125)
        self.assertIn(
            SEGMENT_REGEX_LITERAL,
            block,
            "the 1.2.5 hook must delete only per-test segment files",
        )
        self.assertNotIn("project.delete(execDir)", LOADER_125.read_text())

    def test_no_other_gradle_file_deletes_jacoco_directory(self):
        """No gradle file deletes a jacoco directory or execDir variable en masse."""
        for path in REPO_ROOT.rglob("*.gradle*"):
            if not path.is_file():
                continue
            for line in path.read_text().splitlines():
                if "project.delete" not in line and ".deleteDir" not in line:
                    continue
                if "jacoco" in line or "execDir" in line:
                    # The allowed hooks delete individual segment files, not the directory.
                    if path in (JACOCO_LOADER, LOADER_125):
                        continue
                    self.fail(
                        f"{path.relative_to(REPO_ROOT)} deletes a jacoco/execDir directory: {line.strip()}"
                    )


if __name__ == "__main__":
    unittest.main()
