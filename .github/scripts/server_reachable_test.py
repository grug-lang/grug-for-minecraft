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


def map_of(loaders, own=None):
    """A coverage map from {loader: {test: {class: [method, ...]}}}, and the classes a loader owns."""
    own = own or {}
    return {
        "version": "2",
        "loaders": {
            loader: {
                "classes": {name: {"crc64": "0", "probes": 1} for name in own.get(loader, [])},
                "tests": {
                    test: {name: {"methods": methods} for name, methods in classes.items()}
                    for test, classes in tests.items()
                },
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

    def run_script(self, *extra, map_path=None):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code = server_reachable.main(
                [
                    "--map",
                    str(map_path or self.map),
                    "--server-tests",
                    str(self.artifacts),
                    "--baseline",
                    str(self.baseline),
                    *extra,
                ]
            )
        return code, out.getvalue(), err.getvalue()

    def write_map(self, loaders, own=None):
        self.map.write_text(json.dumps(map_of(loaders, own)))

    def write_map_attempt(self, directory, attempt, loaders):
        path = directory / f"coverage-map-{attempt}" / "coverage-map.json"
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps(map_of(loaders)))
        return path

    def write_tests(self, loader, tests, attempt=None):
        name = f"coverage-server-tests-{loader}"
        if attempt is not None:
            name = f"{name}-{attempt}"
        directory = self.artifacts / name
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
    # skipped the test, because a loader's own skip list is not a second copy of this file. This is
    # core code, which the map lists under no loader, so every loader runs the same class.
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

    # Two loaders can compile different classes of the same name, so a method of one loader's own
    # class is only rescued by that loader's server: the Alpha and Beta OrnitheAdapter are separate
    # files, and Beta reaching a name says nothing about Alpha's code.
    def testOneLoadersOwnClassIsNotRescuedByAnotherLoader(self):
        self.write_map(
            {
                "a1.1.2_01-ornithe": {SERVER_TEST: {"pkg/OrnitheAdapter": ["alphaOnly:()V"]}},
                "b1.7.3-ornithe": {SERVER_TEST: {"pkg/OrnitheAdapter": ["alphaOnly:()V"]}},
            },
            own={
                "a1.1.2_01-ornithe": ["pkg/OrnitheAdapter"],
                "b1.7.3-ornithe": ["pkg/OrnitheAdapter"],
            },
        )
        self.write_tests("a1.1.2_01-ornithe", [UNRELATED_TEST])
        self.write_tests("b1.7.3-ornithe", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("pkg/OrnitheAdapter alphaOnly:()V", out)
        self.assertIn("on a1.1.2_01-ornithe", out)

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

    # The runner's line names the test with a note when it was meant to fail, and the map keys the
    # test by its path, so the note must not hide the tests a server ran from the comparison.
    def testATestMeantToFailCountsAsRan(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST + " (expected error)"])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)

    # javac numbers a class's lambdas in source order, so a lambda added anywhere in the class
    # renames the ones after it. That is not client-only code losing its reason.
    def testALambdaRenumberedByAnotherInItsClassIsAccepted(self):
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {"pkg/HostFunctions": ["lambda$draw$40:(DD)Ljava/lang/Object;"]}
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/HostFunctions": ["lambda$draw$39:(DD)Ljava/lang/Object;"]},
            {"pkg/HostFunctions": "draws a frame a server has none of."},
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("renumbered", out)
        self.assertNotIn("can be removed", out)

    # Only the same lambda moved by the counter; a lambda of another method is new code.
    def testADifferentLambdaIsNotARename(self):
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {"pkg/HostFunctions": ["lambda$play$40:(DD)Ljava/lang/Object;"]}
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/HostFunctions": ["lambda$draw$39:(DD)Ljava/lang/Object;"]},
            {"pkg/HostFunctions": "draws a frame a server has none of."},
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("lambda$play$40", out)

    # Two lambdas of one method can share a descriptor (the record holds such a pair in a resource
    # pack provider), so the counter is not an identity: both move by the same shift, and neither
    # may be reported as removable or excused as the other.
    def testTwoLambdasOfOneMethodBothSurviveARenumbering(self):
        pair = ":(Lpkg/Provider;Ljava/lang/String;)Lpkg/Pack;"
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {
                        "pkg/Provider": [
                            "lambda$register$2" + pair,
                            "lambda$register$3" + pair,
                        ]
                    }
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {
                "pkg/Provider": [
                    "lambda$register$0" + pair,
                    "lambda$register$1" + pair,
                ]
            },
            {"pkg/Provider": "registers the mods' resources as a pack."},
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertNotIn("can be removed", out)

    # One entry of a shared-descriptor pair does not cover the other: a record that held one of
    # them fails on the second rather than passing on either lambda of that identity.
    def testARenumberedPairDoesNotCoverAMissingEntry(self):
        pair = ":(Lpkg/Provider;Ljava/lang/String;)Lpkg/Pack;"
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {
                        "pkg/Provider": [
                            "lambda$register$2" + pair,
                            "lambda$register$3" + pair,
                        ]
                    }
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/Provider": ["lambda$register$0" + pair]},
            {"pkg/Provider": "registers the mods' resources as a pack."},
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("lambda$register$3", out)

    # A name the record already holds is matched before rank pairing, so a new lambda cannot take
    # a recorded one's place: a record of $1 does not explain a run that needs $0 and $1.
    def testARecordedLambdaDoesNotCoverANewOneOfTheSameMethod(self):
        pair = ":(Lpkg/Provider;Ljava/lang/String;)Lpkg/Pack;"
        self.write_map(
            {
                "1.20.6-forge": {
                    CLIENT_TEST: {
                        "pkg/Provider": [
                            "lambda$register$0" + pair,
                            "lambda$register$1" + pair,
                        ]
                    }
                }
            }
        )
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        self.write_baseline(
            {"pkg/Provider": ["lambda$register$1" + pair]},
            {"pkg/Provider": "registers the mods' resources as a pack."},
        )
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("lambda$register$0" + pair, out)
        self.assertNotIn("can be removed", out)

    # A record says which run it came from, so it cannot claim provenance it was not told.
    def testARecordNeedsTheRunItCameFrom(self):
        self.write_map({"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST])
        code, out, err = self.run_script("--record")
        self.assertEqual(code, 1)
        self.assertIn("--recorded-from", err)

    # A JSON object with a method it still records is a record that would shrink: a dispatch
    # narrowed with -f mods= runs only some tests, and the rest would vanish without a word.
    def testRecordRefusesToDropWithoutPrune(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        self.write_baseline({"pkg/Server": ["tick:()V"]}, {"pkg/Server": "used to need a client."})
        code, out, err = self.run_script("--record", "--recorded-from", "run 1")
        self.assertEqual(code, 1)
        self.assertIn("would drop", err)
        code, out, err = self.run_script(
            "--record", "--recorded-from", "run 1", "--prune", "--out", str(self.baseline)
        )
        self.assertEqual(code, 0, err)
        self.assertIn("the record drops", out)
        data = json.loads(self.baseline.read_text())
        self.assertEqual(data["methods"], {})
        self.assertEqual(data["recorded_from"], "run 1")

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
        code, out, err = self.run_script(
            "--record",
            "--recorded-from",
            "run 1, the dispatch of abc1234",
            "--out",
            str(self.baseline),
        )
        self.assertEqual(code, 0, err)
        self.assertIn("needs a reason: pkg/Client", err)
        data = json.loads(self.baseline.read_text())
        self.assertEqual(data["methods"]["pkg/Client"], ["render:()V"])
        self.assertEqual(data["methods"]["pkg/Other"], ["draw:()V"])
        self.assertEqual(data["recorded_from"], "run 1, the dispatch of abc1234")
        self.assertIn("pkg/Other", data["why"])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("pkg/Client", err)

    # A run with neither a map nor server lists has nothing to compare, and the log says so.
    def testAMissingMapPasses(self):
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("neither a coverage map nor dedicated-server test lists", out)

    # A dispatch holds the lists, so a missing map means its download step found no map artifact
    # (or saved it under another name) and the comparison would be skipped in silence: that is a
    # failure, not a run with nothing to judge.
    def testAMissingMapWithServerListsFails(self):
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("is missing", err)
        self.assertIn("no coverage map to be compared against", err)
        self.assertIn("1.20.6-forge", err)

    # A re-run names its artifact after the run attempt, so the loader is the name without that
    # suffix: read as part of the loader, it would look like a loader the map does not have.
    def testAnAttemptSuffixedArtifactIsReadForItsLoader(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST], attempt=2)
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("0 class method(s)", out)
        self.assertIn("1.20.6-forge ran 1 test(s) from attempt 2", out)

    # The repro of #1142: a leg that stopped at its third test left a short list under attempt 1,
    # and the re-run's full list under attempt 2 has to be the one read. Without the suffix handled,
    # the two look like two loaders and the map's own loader has no list at all.
    def testTheNewestAttemptsListIsTheOneRead(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [UNRELATED_TEST], attempt=1)
        self.write_tests("1.20.6-forge", [SERVER_TEST], attempt=2)
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("from attempt 2", out)

    # A loader name carries hyphens of its own, so the attempt is the last numbered part and
    # everything before it is the loader: a split on the first hyphen would name another loader,
    # which would then have no list and fail.
    def testAnAttemptSuffixAfterALoadersOwnHyphenIsRead(self):
        self.write_map({"b1.7.3-stationapi": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("b1.7.3-stationapi", [SERVER_TEST], attempt=3)
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("b1.7.3-stationapi ran 1 test(s) from attempt 3", out)

    def testAnAttemptSuffixAfterAnUnderscoreAndAHyphenIsRead(self):
        self.write_map({"a1.1.2_01-ornithe": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("a1.1.2_01-ornithe", [SERVER_TEST], attempt=2)
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("a1.1.2_01-ornithe ran 1 test(s) from attempt 2", out)

    # A bare name is attempt 0 and loses to any attempt a run uploaded: a list downloaded by hand
    # must not shadow the run's own list for the same loader.
    def testABareNameLosesToASuffixedOneForTheSameLoader(self):
        self.write_map({"b1.7.3-stationapi": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("b1.7.3-stationapi", [UNRELATED_TEST])
        self.write_tests("b1.7.3-stationapi", [SERVER_TEST], attempt=1)
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertIn("b1.7.3-stationapi ran 1 test(s) from attempt 1", out)

    # The newest attempt wins even when it is the shorter list, and attempts compare as numbers: a
    # re-run that ran fewer tests is a leg that stopped early, and an older, longer list must not
    # paper over that.
    def testANewerShorterListIsNotReplacedByAnOlderLongerOne(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST], attempt=1)
        self.write_tests("1.20.6-forge", [UNRELATED_TEST], attempt=10)
        code, out, err = self.run_script()
        self.assertEqual(code, 1)
        self.assertIn("pkg/Server tick:()V", out)

    # The suffix is the last numbered part, and a version in the loader's name is not one: the
    # loader is what is left of the artifact name after that part comes off.
    def testTheAttemptSuffixIsTheLastNumberedPart(self):
        self.assertEqual(
            server_reachable.artifact_loader("coverage-server-tests-1.20.6-forge-12"),
            ("1.20.6-forge", 12),
        )
        self.assertEqual(
            server_reachable.artifact_loader("coverage-server-tests-b1.7.3-stationapi-3"),
            ("b1.7.3-stationapi", 3),
        )
        self.assertEqual(
            server_reachable.artifact_loader("coverage-server-tests-a1.1.2_01-ornithe-2"),
            ("a1.1.2_01-ornithe", 2),
        )
        self.assertEqual(
            server_reachable.artifact_loader("coverage-server-tests-1.20.6-forge"),
            ("1.20.6-forge", 0),
        )

    # A re-run of the coverage job leaves several map attempts in the directory it downloaded, and
    # the newest is the map the run built last, on the same commit as the older one. Reading the
    # older attempt would judge this run's lists against a map from before the re-run (#1145), so
    # the older attempt here is a map that names client-only code and would fail.
    def testTheNewestMapAttemptIsTheOneRead(self):
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        maps = self.root / "maps"
        self.write_map_attempt(
            maps, 2, {"1.20.6-forge": {CLIENT_TEST: {"pkg/Client": ["render:()V"]}}}
        )
        self.write_map_attempt(
            maps, 10, {"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}}
        )
        code, out, err = self.run_script(map_path=maps)
        self.assertEqual(code, 0, err)
        self.assertIn("read the coverage map of attempt 10", out)

    # The download pattern also matches `coverage-map-tool`, whose directory holds the tool rather
    # than a map: that is not a map attempt, so a run holding lists and only the tool fails instead
    # of reading a jar as the map.
    def testADirectoryWithoutAMapAttemptFailsWhenThereAreLists(self):
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        maps = self.root / "maps"
        tool = maps / "coverage-map-tool"
        tool.mkdir(parents=True)
        (tool / "coverage-map-tool.jar").write_bytes(b"jar")
        code, out, err = self.run_script(map_path=maps)
        self.assertEqual(code, 1)
        self.assertIn("holds no coverage-map-<attempt> artifact", err)

    # A map given as a file is read as it is, which is how a map downloaded by hand is passed and
    # what every other case here uses.
    def testAMapFileIsReadWithoutAnAttempt(self):
        self.write_map({"1.20.6-forge": {SERVER_TEST: {"pkg/Server": ["tick:()V"]}}})
        self.write_tests("1.20.6-forge", [SERVER_TEST])
        code, out, err = self.run_script()
        self.assertEqual(code, 0, err)
        self.assertNotIn("read the coverage map of attempt", out)


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

    # The record is only evidence if it says which run it came from, so a rewrite of it has to state
    # that rather than inherit a line that may no longer be true.
    def testTheRecordSaysWhereItCameFrom(self):
        data = json.loads((HERE / "server-reachable.json").read_text())
        self.assertIn("run ", data.get("recorded_from", ""))


if __name__ == "__main__":
    unittest.main()
