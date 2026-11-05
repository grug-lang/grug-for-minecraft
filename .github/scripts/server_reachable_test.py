#!/usr/bin/env python3
"""Tests server-reachable.py: a method no dedicated server reaches needs a recorded reason."""

import contextlib
import importlib.util
import io
import json
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SCRIPT = HERE / "server-reachable.py"
SPEC = importlib.util.spec_from_file_location("server_reachable", SCRIPT)
server_reachable = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(server_reachable)

CLIENT_TEST = "coverage/tests/client-Test.grug"
SERVER_TEST = "coverage/tests/server-Test.grug"
SERVER_ONLY_ON_ONE = "coverage/tests/one-loader-Test.grug"
UNRELATED_TEST = "coverage/tests/unrelated-Test.grug"


def map_of(loaders):
    """A coverage map from {loader: {test: {class: [method, ...]}}}."""
    return {
        "version": "2",
        "loaders": {
            loader: {
                "tests": {
                    test: {name: {"methods": methods} for name, methods in classes.items()}
                    for test, classes in tests.items()
                }
            }
            for loader, tests in loaders.items()
        },
    }


class ServerReachableTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.map = self.root / "coverage-map.json"
        self.artifacts = self.root / "artifacts"
        self.artifacts.mkdir()
        self.baseline = self.root / "server-reachable.json"

    def run_script(self, *extra):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = server_reachable.main(
                [
                    "--map",
                    str(self.map),
                    "--server-tests",
                    str(self.artifacts),
                    "--baseline",
                    str(self.baseline),
                    *extra,
                ]
            )
        return code, out.getvalue(), err.getvalue()

    def write_map(self, loaders):
        self.map.write_text(json.dumps(map_of(loaders)))

    def write_tests(self, loader, tests):
        directory = self.artifacts / f"coverage-server-tests-{loader}"
        directory.mkdir(parents=True, exist_ok=True)
        (directory / "tests.txt").write_text("\n".join(tests) + "\n")

    def write_baseline(self, methods=None, why=None):
        self.baseline.write_text(json.dumps({"why": why or {}, "methods": methods or {}}))

    # A method no test the server ran reaches is a failure that names it and its class's state.
    def testAnUnrecordedMethodNoServerReachesFails(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("pkg/Client render:()V", out)
        self.assertIn(CLIENT_TEST, out)
        self.assertIn("pkg/Client", err)
        self.assertIn("A class with no reason yet: pkg/Client", err)

    # The test the server ran reaches its method there, so nothing is missing.
    def testAMethodAServerTestReachesPasses(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)

    # A method one loader's server reaches is server-reachable even where another loader's server
    # skipped the test, because a loader's own skip list is not a second copy of this file.
    def testAMethodOneServerReachesIsServerReachable(self):
        self.write_map(
            {
                "1.20.6-forge": {SERVER_ONLY_ON_ONE: {"pkg/Shared": ["tick:()V"]}},
                "1.2.5-forge": {SERVER_ONLY_ON_ONE: {"pkg/Shared": ["tick:()V"]}},
            }
        )
        self.write_tests("1.20.6-forge", [SERVER_ONLY_ON_ONE])
        self.write_tests("1.2.5-forge", [UNRELATED_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)

    # The gate does not count an excluded class, so the guard does not ask for a reason for one.
    def testAnExcludedClassIsIgnored(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/GrugScreen": ["draw:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)

    # A class initializer belongs to whichever test first used the class, which says nothing about
    # whether a server can reach it.
    def testAClassInitializerIsIgnored(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Faces": ["<clinit>:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)

    # A recorded method passes when its class says why.
    def testARecordedMethodWithAReasonPasses(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/Client": ["render:()V"]}, {"pkg/Client": "draws a frame a server has none of."}
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("1 are recorded", out)

    # The file is only a record if it explains itself, so a class without a reason fails even when
    # the method it names is the one that needed recording.
    def testARecordedClassWithoutAReasonFails(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline({"pkg/Client": ["render:()V"]})
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("without a reason", err)
        self.assertIn("pkg/Client", err)

    # A recorded method a server reaches now is allowed, and the log says it can go: the guard is a
    # floor, never a demand that the record keeps code client-only.
    def testAStaleRecordedMethodIsReportedAndPasses(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        self.write_baseline({"pkg/Server": ["tick:()V"]}, {"pkg/Server": "used to need a client."})
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("can be removed", out)

    # A pull request and a push run no multiplayer job, so there is nothing to compare and the
    # script says so rather than failing a run it cannot judge.
    def testNoServerTestListsPasses(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}})
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("nothing to compare", out)

    # A dispatch holds a list per loader, and a missing one would shrink the server-reachable set
    # silently, so it is a failure.
    def testAMissingLoaderListFails(self):
        self.write_map(
            {
                "1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}},
                "1.2.5-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}},
            }
        )
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("no dedicated-server test list for 1.2.5-forge", err)

    # A leg that ran no test has nothing to say about what its server reached, so an empty list is a
    # failure rather than data that lets every method of that loader look unreached.
    def testAnEmptyServerTestListFails(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("ran no test", err)

    # A loader whose selection ran no coverage test has no list to compare, and the log says which
    # loader that was instead of quietly passing.
    def testALoaderWithNoCoverageTestsIsSkippedWithAMessage(self):
        self.write_map(
            {
                "1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}},
                "1.2.5-forge": {},
            }
        )
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("1.2.5-forge ran no coverage test", out)

    # Recording writes the file that makes the run pass and keeps the reasons already there.
    def testRecordWritesABaselineThatThenPasses(self):
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {"pkg/Client": ["render:()V"], "pkg/Other": ["draw:()V"]}
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/Other": ["draw:()V"]}, {"pkg/Other": "draws a frame a server has none of."}
        )
        code, out, err = self.run_script("--record", "--out", str(self.baseline))
        self.assertEqual(code, 0, err)
        self.assertIn("needs a reason: pkg/Client", err)
        data = json.loads(self.baseline.read_text())
        self.assertEqual(data["methods"]["pkg/Client"], ["render:()V"])
        self.assertEqual(data["methods"]["pkg/Other"], ["draw:()V"])
        self.assertIn("pkg/Other", data["why"])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("pkg/Client", err)

    # A run with no coverage map to read is a run with nothing to compare, and the log says so.
    def testAMissingMapPasses(self):
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("no coverage map", err)


class RepositoryBaselineTest(unittest.TestCase):
    """The committed record has to explain every class it names, or it records nothing."""

    def testEveryRecordedClassHasAReason(self):
        path = HERE / "server-reachable.json"
        data = json.loads(path.read_text())
        reasons = data.get("why", {})
        for name in data.get("methods", {}):
            self.assertTrue(
                reasons.get(name, "").strip(),
                f"{path} records methods of {name} without a reason",
            )
            for method in data["methods"][name]:
                self.assertIn(":", method, f"{path} records {name} {method} without a descriptor")

    def testEveryMethodIsListedOnce(self):
        data = json.loads((HERE / "server-reachable.json").read_text())
        for name, methods in data.get("methods", {}).items():
            self.assertEqual(methods, sorted(set(methods)), f"{name} lists a method twice")


if __name__ == "__main__":
    unittest.main()
