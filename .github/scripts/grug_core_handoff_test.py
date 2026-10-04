#!/usr/bin/env python3
"""Tests the hand-off between `core` and the four loaders that run a Gradle wrapper of their own.

Those loaders cannot be projects of the root build, because the root build is on Gradle 8.14 and
they are on 9.5.1, so `core` publishes itself and they resolve it. The change this guards took that
from the shared ~/.m2 to build/maven inside the checkout and stamped the version with the commit,
and the failure it replaces was silent: a loader that resolves a core from another worktree compiles,
runs, and fails later somewhere else, so a branch looks broken when it is not.

So the tests build a miniature of this repository rather than testing Gradle: a producer at core/, a
consumer at loaders/fake/, the real gradle/grug-core.gradle copied in, and a commit for it to stamp
the version with. Both halves read that one script, so what runs here is the wiring and not a
stand-in for it. The last two tests read the build files instead, because a loader put back on
mavenLocal passes every Gradle run in here and nothing else in CI would notice, since CI has one
checkout and a shared slot looks correct to it.

The runs use the root build's Gradle, because that is the wrapper a checkout already has. The
loaders resolve on 9.5.1, and both were measured to re-read a local repository's modules on every
build rather than serve them out of ~/.gradle, which is what the republish test is about.
"""

import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

SCRIPTS = Path(__file__).resolve().parent
REPO_ROOT = SCRIPTS.parent.parent

# The four loaders with a root.gradle, which is to say the four that do not resolve core as a project
# of the root build.
STANDALONE_LOADERS = ("1.2.5-forge", "a1.1.2_01-ornithe", "b1.7.3-ornithe", "b1.7.3-stationapi")

# Enough of an identity for git to commit in a temporary directory, whose owner may have none, and
# enough of a commit to ignore the developer's global config, which may sign and may be configured
# for a different user. Signing is pinned off because a passphrase-prompting key would block the run.
IDENTITY = (
    "-c",
    "user.name=grug",
    "-c",
    "user.email=grug@example.com",
    "-c",
    "commit.gpgsign=false",
)

# git stamps the committer clock into the commit object at one-second resolution, so two checkouts
# meant to share a commit get different ones whenever a second boundary falls between them. That is
# the whole premise of the two-worktree test, so the clock is pinned rather than left to the machine.
CLOCK = {
    "GIT_AUTHOR_DATE": "2020-01-01T00:00:00+00:00",
    "GIT_COMMITTER_DATE": "2020-01-01T00:00:00+00:00",
}

# A miniature of this checkout's core, publishing itself the way core/build.gradle.kts does. The
# marker file stands in for the classes and the mods the real jar carries, so a consumer can read
# back which jar it was given.
CORE_BUILD = """\
plugins {
    id 'java-library'
    id 'maven-publish'
}

apply from: '../gradle/grug-core.gradle'

group = 'net.grug'
version = grugCoreVersion

def markerFile = layout.buildDirectory.file('marker/marker.txt')

tasks.register('marker') {
    def source = file('marker.txt')
    inputs.file(source)
    outputs.file(markerFile)
    doLast {
        def out = markerFile.get().asFile
        out.parentFile.mkdirs()
        out.text = source.text
    }
}

tasks.named('processResources') {
    dependsOn 'marker'
    from(layout.buildDirectory.dir('marker'))
}

publishing {
    publications {
        mavenJava(MavenPublication) {
            from components.java
            artifactId = 'grug-core'
        }
    }

    repositories {
        maven {
            name = 'grugBuild'
            url = grugCoreRepository.toURI()
        }
    }
}
"""

# A miniature of a standalone loader. Its own settings file is what makes it a build of its own rather
# than a project of the one above it, which is what the real loaders have too.
FAKE_LOADER_SETTINGS = "rootProject.name = 'grug-fake-loader'\n"

FAKE_LOADER_BUILD = """\
plugins {
    id 'java'
}

apply from: '../../gradle/grug-core.gradle'

repositories {
    maven {
        name = 'grugBuild'
        url = grugCoreRepository.toURI()
    }
}

dependencies {
    implementation "net.grug:grug-core:$grugCoreVersion"
}

tasks.register('showResolvedCore') {
    def resolved = layout.buildDirectory.file('resolved.txt')
    // Always run, so every check resolves the dependency again rather than reading the last answer.
    outputs.upToDateWhen { false }
    outputs.file(resolved)
    doLast {
        def jar = configurations.compileClasspath.files.find { it.name.endsWith('.jar') }
        if (jar == null) {
            throw new GradleException('Nothing on the classpath: grug-core did not resolve.')
        }
        def zip = new java.util.zip.ZipFile(jar)
        def out = resolved.get().asFile
        out.parentFile.mkdirs()
        out.text = zip.getInputStream(zip.getEntry('marker.txt')).text.trim()
        println "RESOLVED ${jar.name} ${out.text}"
    }
}
"""

# Mirrors the root gradle.properties, which is where the script reads the base version from. Daemons
# off, the way this repository's own gradle.properties has it.
ROOT_GRADLE_PROPERTIES = """\
org.gradle.daemon = false
mod_version = 1.0.0
"""

# The producer's build holds core alone. The real root build also includes the loaders' root.gradle
# files, which only forward the tasks run-loader.sh calls, and nothing here needs them.
ROOT_SETTINGS = "rootProject.name = 'grug-miniature'\ninclude 'core'\n"


# The version and the repository both come from gradle/grug-core.gradle, which core publishes under
# as well, so neither half holds a copy that can fall out of date. Matched rather than compared
# literally, because "${grugCoreVersion}" and "$grugCoreVersion" are both the coordinate and only a
# literal version is the regression.
STAMPED_COORDINATE = r"net\.grug:grug-core:\$\{?grugCoreVersion\}?"


class GrugCoreHandoffTest(unittest.TestCase):
    """Resolves a miniature repository with real Gradle, then reads the real build files."""

    def setUp(self):
        self.tmp = Path(tempfile.mkdtemp())
        self.addCleanup(shutil.rmtree, self.tmp)

    # -- the miniature checkout ---------------------------------------------------------------------

    def worktree(self, name: str, marker: str) -> Path:
        """A checkout shaped like this one: the script, a producer, a consumer and one commit."""
        root = self.tmp / name

        (root / "gradle").mkdir(parents=True)
        (root / "gradle" / "grug-core.gradle").write_bytes(
            (REPO_ROOT / "gradle" / "grug-core.gradle").read_bytes()
        )
        (root / "gradle.properties").write_text(ROOT_GRADLE_PROPERTIES)
        (root / "settings.gradle").write_text(ROOT_SETTINGS)

        (root / "core").mkdir()
        (root / "core" / "build.gradle").write_text(CORE_BUILD)
        self.set_marker(root, marker)

        (root / "loaders" / "fake").mkdir(parents=True)
        (root / "loaders" / "fake" / "settings.gradle").write_text(FAKE_LOADER_SETTINGS)
        (root / "loaders" / "fake" / "build.gradle").write_text(FAKE_LOADER_BUILD)

        self.git(root, "init", "-q", "-b", "main")
        self.commit(root, "initial")

        return root

    def set_marker(self, root: Path, marker: str) -> None:
        (root / "core" / "marker.txt").write_text(f"{marker}\n")

    def git(self, root: Path, *args: str) -> str:
        result = subprocess.run(
            ["git", "-C", str(root), *args],
            capture_output=True,
            text=True,
            check=False,
            env={**os.environ, **CLOCK},
        )
        self.assertEqual(result.returncode, 0, f"git {' '.join(args)}: {result.stderr}")
        return result.stdout.strip()

    def commit(self, root: Path, message: str) -> str:
        """Commits the miniature's working tree and returns the coordinate that moves with it."""
        self.git(root, "add", "-A")
        self.git(root, *IDENTITY, "commit", "-q", "-m", message)
        return self.coordinate(root)

    def coordinate(self, root: Path) -> str:
        """The version both halves of this checkout's hand-off agree on."""
        return f"1.0.0-{self.git(root, 'rev-parse', '--short=12', 'HEAD')}"

    def publish(self, root: Path, marker: str | None = None) -> None:
        if marker is not None:
            self.set_marker(root, marker)

        result = self.gradle(root, ":core:publishMavenJavaPublicationToGrugBuildRepository")
        self.assertEqual(result.returncode, 0, f"publishing failed:\n{self.output(result)}")

        # The publish has to land in this checkout's own directory, which is the whole of the change:
        # one worktree's core is nowhere near another's loader.
        published = root / "build" / "maven" / "net" / "grug" / "grug-core" / self.coordinate(root)
        self.assertTrue(published.is_dir(), f"{published} does not hold the published core")

    def resolve(self, root: Path) -> subprocess.CompletedProcess:
        return self.gradle(root / "loaders" / "fake", "showResolvedCore")

    def resolved(self, root: Path) -> tuple[str, str]:
        """The coordinate the loader asked for and the jar it got, as (version, marker)."""
        result = self.resolve(root)
        self.assertEqual(result.returncode, 0, f"resolution failed:\n{self.output(result)}")

        reported = next(
            line for line in self.output(result).splitlines() if line.startswith("RESOLVED ")
        )
        _, jar_name, marker = reported.split(maxsplit=2)
        # The jar is named after the coordinate, so its name is what the loader was handed.
        return jar_name[len("grug-core-") : -len(".jar")], marker

    def gradle(self, project: Path, *args: str) -> subprocess.CompletedProcess:
        # --no-daemon on the command line rather than the miniature's gradle.properties, because that
        # file is only read by the producer: the consumer is a build of its own and Gradle does not
        # look upward for one. Without this the consumer starts a daemon with a three-hour idle
        # timeout, in the shared ~/.gradle registry, for a project directory this test deletes.
        return subprocess.run(
            [str(REPO_ROOT / "gradlew"), "-p", str(project), "--no-daemon", *args],
            cwd=project,
            capture_output=True,
            text=True,
            check=False,
        )

    def output(self, result: subprocess.CompletedProcess) -> str:
        """Both streams, because which one Gradle reports in depends on the console it chose."""
        return result.stdout + result.stderr

    def loader_build(self, loader: str) -> str:
        directory = REPO_ROOT / "loaders" / loader
        kotlin = directory / "build.gradle.kts"
        return (kotlin if kotlin.exists() else directory / "build.gradle").read_text()

    # -- the hand-off ------------------------------------------------------------------------------

    def test_a_loader_resolves_the_core_its_own_checkout_published(self):
        root = self.worktree("checkout", "first")

        self.publish(root)

        self.assertEqual(self.resolved(root), (self.coordinate(root), "first"))

    def test_a_republished_core_reaches_a_loader_without_a_new_commit(self):
        # The everyday case: edit core, publish again, run the loader. The commit has not moved, so
        # the coordinate has not either, and this is where a loader that trusted the copy of a fixed
        # 1.0.0 in ~/.gradle would go on compiling against the jar it resolved last time.
        root = self.worktree("checkout", "first")
        self.publish(root)
        self.assertEqual(self.resolved(root)[1], "first")

        self.publish(root, "second")

        self.assertEqual(self.resolved(root)[1], "second")

    def test_a_commit_with_no_publish_fails_rather_than_resolving_the_one_before_it(self):
        # What a loader's own build does when it runs before the root build has published the commit
        # it is on, which is what stops it from quietly compiling against an older core.
        root = self.worktree("checkout", "first")
        self.publish(root)
        published = self.resolved(root)
        self.assertEqual(published[1], "first")

        self.set_marker(root, "second")
        moved = self.commit(root, "second commit")
        self.assertNotEqual(moved, published[0])

        result = self.resolve(root)
        self.assertNotEqual(result.returncode, 0)
        # Naming the coordinate of the commit that was never published is what makes this a diagnosis
        # rather than a dependency that looks missing.
        self.assertIn(moved, self.output(result))
        self.assertNotIn(published[0], self.output(result))

        self.publish(root)

        self.assertEqual(self.resolved(root), (moved, "second"))

    def test_two_worktrees_on_the_same_commit_do_not_resolve_each_others_core(self):
        # The bug this change is about: two checkouts of one commit, each with its own edit in the
        # working tree, both publishing under the same version, sharing ~/.gradle. A loader has to be
        # able to tell which of the two jars is its own.
        first = self.worktree("worktree-a", "first")
        second = self.worktree("worktree-b", "first")
        self.assertEqual(self.coordinate(first), self.coordinate(second))

        self.publish(first, "worktree a")
        self.publish(second, "worktree b")

        self.assertEqual(self.resolved(first), (self.coordinate(first), "worktree a"))
        self.assertEqual(self.resolved(second), (self.coordinate(second), "worktree b"))

    # -- what the real build files say ---------------------------------------------------------------

    def test_every_standalone_loader_asks_for_the_stamped_coordinate_from_the_checkout(self):
        for loader in STANDALONE_LOADERS:
            with self.subTest(loader=loader):
                build = self.loader_build(loader)

                self.assertRegex(build, STAMPED_COORDINATE)
                self.assertIn("grugCoreRepository", build)
                self.assertIn("gradle/grug-core.gradle", build)

                # The literal coordinate and the property that carried it are what this replaced.
                self.assertNotIn("grug-core:1.0.0", build)
                properties = REPO_ROOT / "loaders" / loader / "gradle.properties"
                if properties.exists():
                    self.assertNotIn("grug_core_version", properties.read_text())

                # mavenLocal() in pluginManagement resolved nothing this repository publishes.
                settings = REPO_ROOT / "loaders" / loader / "settings.gradle"
                self.assertNotIn("mavenLocal()", settings.read_text())

    def test_every_entry_point_publishes_into_the_repository_the_loaders_resolve(self):
        # Gradle names the publish task after the repository, so the name the entry points depend on
        # follows from the one core declares. Read it rather than repeat it, so renaming the
        # repository and following it in the entry points does not fail here for the wrong reason.
        core = (REPO_ROOT / "core" / "build.gradle.kts").read_text()
        self.assertIn("version = grugCoreVersion", core)
        self.assertIn("url = grugCoreRepository.toURI()", core)

        # Found by the block that publishes into the shared repository, rather than by how that
        # repository's name is spelled, so a second repository cannot change the answer.
        publishing_block = core.split("url = grugCoreRepository.toURI()")[0].rsplit("maven {", 1)[1]
        repository = re.search(r'name = "([^"]+)"', publishing_block).group(1)
        # Gradle capitalizes the repository's name and appends the repository type to the task name.
        task = (
            "publishMavenJavaPublicationTo" + repository[0].upper() + repository[1:] + "Repository"
        )

        entry_points = [
            SCRIPTS / "run-loader.sh",
            *(REPO_ROOT / "loaders" / name / "root.gradle" for name in STANDALONE_LOADERS),
        ]
        for entry_point in entry_points:
            with self.subTest(entry_point=entry_point.relative_to(REPO_ROOT)):
                text = entry_point.read_text()
                self.assertIn(f":core:{task}", text)
                self.assertNotIn("mavenLocal", text)


if __name__ == "__main__":
    unittest.main()
