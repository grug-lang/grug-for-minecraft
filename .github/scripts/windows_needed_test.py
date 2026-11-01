#!/usr/bin/env python3
"""Tests windows-needed.py: only a diff that can reach the Windows boot jobs keeps them (#826)."""

import importlib.util
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "windows-needed.py"
SPEC = importlib.util.spec_from_file_location("windows_needed", SCRIPT)
windows_needed = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(windows_needed)

# affected_mods_test.py already has the throwaway repository the diffs are taken in.
REPO_SPEC = importlib.util.spec_from_file_location(
    "affected_mods_test", HERE / "affected_mods_test.py"
)
repo_module = importlib.util.module_from_spec(REPO_SPEC)
REPO_SPEC.loader.exec_module(repo_module)

# The tree the cases are taken from, shaped like the one the reports of the merged pull requests of
# October 2026 touched: the native path, the runner, the jar, the loaders, the mods and the pipeline.
BASE = {
    "AGENTS.md": "agents\n",
    "README.md": "readme\n",
    "mod_api.json": "{}",
    "mod_api.md": "api\n",
    "core/build.gradle.kts": "// build\n",
    "core/generate.py": "# generate\n",
    "core/test_generate.py": "# test\n",
    "core/src/main/native/adapter.c": "int main(void) { return 0; }\n",
    "core/src/main/native/adapter_shared.h": "#define SHARED 1\n",
    "core/src/main/java/net/grug/minecraft/grug/GrugNative.java": "class GrugNative {}\n",
    "core/src/main/java/net/grug/minecraft/grug/GrugBootOnly.java": "class GrugBootOnly {}\n",
    "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "class HostFunctions {}\n",
    "core/src/test/java/net/grug/minecraft/grug/GrugNativeTest.java": "class GrugNativeTest {}\n",
    ".github/workflows/build.yml": "name: Build\n",
    ".github/workflows/weekly-release.yml": "name: Weekly release\n",
    ".github/actions/setup-loader-build/action.yml": "name: setup\n",
    ".github/scripts/run-loader.sh": "# run\n",
    ".github/scripts/launcher.sh": "# launcher\n",
    ".github/scripts/launch-production.sh": "# production\n",
    ".github/scripts/report-native-abort.sh": "# abort\n",
    ".github/scripts/universal_jar.py": "# jar\n",
    ".github/scripts/ci_timing.py": "# timing\n",
    "loaders/1.2.5-forge/build.gradle": "// 1.2.5\n",
    "loaders/1.2.5-forge/src/main/java/net/grug/minecraft/forge125/Grug125Adapter.java": "class A {}\n",
    "loaders/1.20.6-forge/src/main/java/net/grug/minecraft/forge/ForgeAdapter.java": "class B {}\n",
    "mods/coverage/about.json": "{}",
    "mods/buildcraft/code/wrench-Item.grug": "on_use() {}\n",
    "mods/buildcraft/tests/wrench-Test.grug": "tick() {}\n",
    "shared/Shared.java": "class Shared {}\n",
}


class WindowsNeededTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.repo = repo_module.Repo(Path(self.tmp.name))
        self.base = self.repo.commit(BASE, "base")

    def answer(self, changes: dict, base=None, *extra: str) -> str:
        """The script's stdout for a commit with these changes: ``true`` or ``false``."""
        head = self.repo.commit(changes)
        result = subprocess.run(
            [
                sys.executable,
                str(SCRIPT),
                self.base if base is None else base,
                head,
                "--repo",
                str(self.repo.path),
                *extra,
            ],
            capture_output=True,
            text=True,
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        self.reason = result.stderr
        return result.stdout.strip()

    # A dispatch answers without a diff when it is on main or names no base (#826).

    def test_a_dispatch_on_main_keeps_the_jobs_whatever_base_it_names(self):
        # The base below is a docs-only diff, which skips on a branch: the ref alone decides here.
        changes = {"AGENTS.md": "agents2\n"}
        self.assertEqual(
            self.answer(changes, None, "--event", "workflow_dispatch", "--ref", "refs/heads/main"),
            "true",
        )
        self.assertIn("on main", self.reason)
        self.assertEqual(
            self.answer(
                {"AGENTS.md": "agents3\n"},
                None,
                "--event",
                "workflow_dispatch",
                "--ref",
                "refs/heads/g826",
            ),
            "false",
        )

    def test_a_dispatch_naming_no_base_keeps_the_jobs(self):
        self.assertEqual(
            self.answer(
                {"AGENTS.md": "agents4\n"},
                "",
                "--event",
                "workflow_dispatch",
                "--ref",
                "refs/heads/g826",
            ),
            "true",
        )
        self.assertIn("names no base", self.reason)

    # The diffs the replay of the merged pull requests of October 2026 found, which skip.

    def test_a_host_function_and_the_loader_adapters_that_call_it_skip(self):
        # #939's shape: the bridge, the adapters and the test that reads them, all run on Linux.
        changes = {
            "core/src/main/java/net/grug/minecraft/grug/HostFunctions.java": "class H { }\n",
            "loaders/1.2.5-forge/src/main/java/net/grug/minecraft/forge125/Grug125Adapter.java": (
                "class A { }\n"
            ),
            "core/src/test/java/net/grug/minecraft/grug/GrugNativeTest.java": "class T { }\n",
        }
        self.assertEqual(self.answer(changes), "false")
        self.assertIn("none of the 3 changed paths", self.reason)

    def test_a_mod_port_skips(self):
        changes = {
            "mods/buildcraft/code/wrench-Item.grug": "on_use() { }\n",
            "mods/buildcraft/tests/wrench-Test.grug": "tick() { }\n",
            "mod_api.md": "api\n",
        }
        self.assertEqual(self.answer(changes), "false")

    def test_prose_alone_skips(self):
        self.assertEqual(self.answer({"AGENTS.md": "agents\n\nmore\n"}), "false")

    def test_a_change_to_another_workflow_skips(self):
        # #802's shape: the release dispatches build.yml, which is the run whose jobs that workflow
        # does not define.
        self.assertEqual(
            self.answer({".github/workflows/weekly-release.yml": "name: W\n"}), "false"
        )

    def test_the_loader_action_the_windows_legs_do_not_use_skips(self):
        self.assertEqual(
            self.answer({".github/actions/setup-loader-build/action.yml": "name: setup2\n"}),
            "false",
        )

    def test_a_pipeline_script_that_is_not_the_runner_skips(self):
        self.assertEqual(self.answer({".github/scripts/ci_timing.py": "# t\n"}), "false")

    def test_a_file_added_inside_an_existing_loader_skips(self):
        changes = {"loaders/1.20.6-forge/src/main/java/net/grug/minecraft/forge/Patch.java": "c\n"}
        self.assertEqual(self.answer(changes), "false")

    # The diffs that keep the jobs.

    def test_the_adapter_c_keeps_the_jobs(self):
        # #898's shape: the C the MSVC build compiles is the only thing Windows compiles it with.
        self.assertEqual(self.answer({"core/src/main/native/adapter.c": "int x;\n"}), "true")
        self.assertIn("adapter.c", self.reason)

    def test_the_generator_of_the_adapter_c_keeps_the_jobs(self):
        self.assertEqual(self.answer({"core/generate.py": "# g\n"}), "true")

    def test_the_api_the_adapter_generator_reads_keeps_the_jobs(self):
        # core/build.gradle.kts declares mod_api.json an input of generateGrugAdapter, whose C is
        # what MSVC compiles into adapter.dll and what grug-rs reads at boot, so an API-only change
        # can break the Windows native and the boot with no Windows job (the review of #944).
        self.assertEqual(self.answer({"mod_api.json": '{"host": {}}\n'}), "true")
        self.assertIn("mod_api.json", self.reason)

    def test_the_core_build_and_its_grug_rs_revision_keep_the_jobs(self):
        # #592's shape: a grug-rs bump is compiled into adapter.dll by MSVC.
        self.assertEqual(self.answer({"core/build.gradle.kts": "// b\n"}), "true")

    def test_the_class_that_picks_the_native_keeps_the_jobs(self):
        # #534's shape: the OS and architecture pick the file name the DLL is loaded by.
        self.assertEqual(
            self.answer(
                {"core/src/main/java/net/grug/minecraft/grug/GrugNative.java": "class N {}\n"}
            ),
            "true",
        )

    def test_the_boot_only_stop_keeps_the_jobs(self):
        # #535's shape: a boot-only run is the only run that takes this path.
        self.assertEqual(
            self.answer({"core/src/main/java/net/grug/minecraft/grug/GrugBootOnly.java": "c\n"}),
            "true",
        )

    def test_the_runner_keeps_the_jobs(self):
        for script in ("run-loader.sh", "launcher.sh", "launch-production.sh"):
            self.assertEqual(self.answer({f".github/scripts/{script}": "# s\n"}), "true", script)

    def test_the_native_abort_report_keeps_the_jobs(self):
        self.assertEqual(self.answer({".github/scripts/report-native-abort.sh": "# a\n"}), "true")

    def test_the_universal_jar_keeps_the_jobs(self):
        # #500's shape: the installed job boots this jar, natives/ included.
        self.assertEqual(self.answer({".github/scripts/universal_jar.py": "# j\n"}), "true")

    def test_the_workflow_keeps_the_jobs(self):
        # A job's steps and conditions are the jobs, so #826 itself keeps them.
        self.assertEqual(self.answer({".github/workflows/build.yml": "name: Build2\n"}), "true")

    def test_a_new_loader_directory_keeps_the_jobs(self):
        changes = {
            "loaders/b1.7.3-fabric/build.gradle": "// b1.7.3\n",
            "loaders/b1.7.3-fabric/src/main/java/net/grug/minecraft/fabric/FabricAdapter.java": "c\n",
        }
        self.assertEqual(self.answer(changes), "true")
        self.assertIn("b1.7.3-fabric", self.reason)

    # Every doubt runs the jobs.

    def test_an_empty_diff_keeps_the_jobs(self):
        self.assertEqual(self.answer({}), "true")
        self.assertIn("empty", self.reason)

    def test_a_base_that_cannot_be_read_keeps_the_jobs(self):
        self.repo.commit({"AGENTS.md": "agents2\n"})
        self.assertEqual(self.answer({"AGENTS.md": "agents3\n"}, base="0" * 40), "true")


if __name__ == "__main__":
    unittest.main()
