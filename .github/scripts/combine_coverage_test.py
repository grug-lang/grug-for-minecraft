#!/usr/bin/env python3
"""Tests combine-coverage.py: a class initializer that only ran in a dropped segment counts, and
the recorded coverage of a carried-over test counts for the runner whose classes it was recorded
against and for core, which unions every runner's exec data (#786). A carried-over test also brings
the base run's other-mod segments, which reach only the everything reports, where they rescue the
initializers that ran in them (#897)."""

import contextlib
import csv
import importlib.util
import io
import sys
import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "combine_coverage", Path(__file__).resolve().parent / "combine-coverage.py"
)
combine = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(combine)

HEADER = (
    "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED,"
    "LINE_MISSED,LINE_COVERED,COMPLEXITY_MISSED,COMPLEXITY_COVERED,METHOD_MISSED,"
    "METHOD_COVERED\n"
)


def counter_elements(counters):
    """The <counter> elements JaCoCo writes, from {type: (missed, covered)}."""
    return "".join(
        f'<counter type="{kind}" missed="{missed}" covered="{covered}"/>'
        for kind, (missed, covered) in counters.items()
    )


def report_xml(clinits):
    """A JaCoCo XML report shaped like its own, with the given <clinit> counters.

    Each entry is (class name suffix, initializer instruction misses, initializer branch misses),
    where a branch count of None means the initializer has no BRANCH counter, as JaCoCo leaves out
    for an initializer without a branch. The class's run() method is always missed, so the fixture
    shows what the rescue moves and what it must leave alone.
    """
    classes = ""
    package_totals = {}
    for name, instructions, branches in clinits:
        branch = {"BRANCH": (branches, 2 - branches)} if branches is not None else {}
        covered = instructions == 0
        initializer = {
            "INSTRUCTION": (instructions, 9 - instructions),
            **branch,
            "LINE": (0, 1) if covered else (1, 0),
            "COMPLEXITY": (0, 1) if covered else (1, 0),
            "METHOD": (0, 1) if covered else (1, 0),
        }
        method = {"INSTRUCTION": (4, 0), "LINE": (1, 0), "COMPLEXITY": (1, 0), "METHOD": (1, 0)}
        class_totals = {
            kind: (
                initializer.get(kind, (0, 0))[0] + method.get(kind, (0, 0))[0],
                initializer.get(kind, (0, 0))[1] + method.get(kind, (0, 0))[1],
            )
            for kind in ("INSTRUCTION", "BRANCH", "LINE", "COMPLEXITY", "METHOD")
            if kind in initializer or kind in method
        }
        class_totals["CLASS"] = (1, 0)
        for kind, (missed, covered) in class_totals.items():
            previous = package_totals.get(kind, (0, 0))
            package_totals[kind] = (previous[0] + missed, previous[1] + covered)
        classes += (
            f'<class name="net/grug/Outer{name}" sourcefilename="Outer{name[1:]}.java">'
            f'<method name="&lt;clinit&gt;" desc="()V" line="1">'
            f"{counter_elements(initializer)}</method>"
            f'<method name="run" desc="()V" line="2">{counter_elements(method)}</method>'
            f"{counter_elements(class_totals)}</class>"
        )
        classes += (
            f'<sourcefile name="Outer{name[1:]}.java">'
            f'<line nr="1" mi="{instructions}" ci="{9 - instructions}" '
            f'mb="{branches if branches is not None else 0}" '
            f'cb="{2 - branches if branches is not None else 0}"/>'
            f'<line nr="2" mi="4" ci="0" mb="0" cb="0"/>'
            f"{counter_elements({kind: value for kind, value in class_totals.items()})}"
            f"</sourcefile>"
        )
    return (
        f'<report name="r"><package name="net/grug">{classes}'
        f"{counter_elements(package_totals)}</package>"
        f"{counter_elements(package_totals)}</report>"
    )


class RescueClassInitializersTest(unittest.TestCase):
    def rescue(self, counted, everything, rows):
        with tempfile.TemporaryDirectory() as tmp:
            tmp = Path(tmp)
            (tmp / "counted.xml").write_text(report_xml(counted))
            (tmp / "everything.xml").write_text(report_xml(everything))
            (tmp / "report.csv").write_text(HEADER + rows)
            combine.rescue_class_initializers(
                tmp / "report.csv", tmp / "counted.xml", tmp / "everything.xml"
            )
            return (
                (tmp / "report.csv").read_text().replace("\r\n", "\n"),
                ET.parse(tmp / "counted.xml").getroot(),
            )

    def test_counts_an_initializer_only_a_dropped_segment_ran(self):
        out, xml = self.rescue(
            counted=[("$Kind", 9, 2)],
            everything=[("$Kind", 0, 0)],
            rows="core,net.grug,Outer.Kind,13,0,2,0,2,0,2,0,2,0\n",
        )
        # The initializer's 9 instructions, 2 branches, 1 line, complexity and method move to
        # covered; run()'s 4 instructions and its line stay missed.
        self.assertEqual(out, HEADER + "core,net.grug,Outer.Kind,4,9,0,2,1,1,1,1,1,1\n")
        # The XML moves with the CSV, or a passing run uploads a per-line report that says the
        # initializer was missed (#897).
        cls = xml.find("package/class")
        initializer = {
            counter.get("type"): (counter.get("missed"), counter.get("covered"))
            for counter in cls.find("method").findall("counter")
        }
        self.assertEqual(
            initializer,
            {
                "INSTRUCTION": ("0", "9"),
                "BRANCH": ("0", "2"),
                "LINE": ("0", "1"),
                "COMPLEXITY": ("0", "1"),
                "METHOD": ("0", "1"),
            },
        )
        self.assertEqual(
            dict(
                (counter.get("type"), (counter.get("missed"), counter.get("covered")))
                for counter in cls.findall("counter")
            )["CLASS"],
            ("0", "1"),
        )
        line = xml.find("package/sourcefile/line")
        self.assertEqual((line.get("nr"), line.get("mi"), line.get("ci")), ("1", "0", "9"))
        # The report's own totals move by the same amounts: they are sums, not independent.
        totals = {
            counter.get("type"): (counter.get("missed"), counter.get("covered"))
            for counter in xml.findall("counter")
        }
        self.assertEqual(totals["INSTRUCTION"], ("4", "9"))
        self.assertEqual(totals["LINE"], ("1", "1"))
        self.assertEqual(totals["CLASS"], ("0", "1"))

    def test_leaves_an_initializer_nothing_ran(self):
        rows = "core,net.grug,Outer.Kind,13,0,2,0,2,0,2,0,2,0\n"
        out, _xml = self.rescue(counted=[("$Kind", 9, 2)], everything=[("$Kind", 9, 2)], rows=rows)
        self.assertEqual(out, HEADER + rows)

    def test_leaves_what_the_counted_segments_already_covered(self):
        rows = "core,net.grug,Outer.Kind,4,9,0,2,1,1,1,1,1,1\n"
        out, _xml = self.rescue(counted=[("$Kind", 0, 0)], everything=[("$Kind", 0, 0)], rows=rows)
        self.assertEqual(out, HEADER + rows)

    def test_an_initializer_without_branches_has_no_branch_counter(self):
        out, xml = self.rescue(
            counted=[("", 9, None)],
            everything=[("", 0, None)],
            rows="core,net.grug,Outer,13,0,0,0,2,0,2,0,2,0\n",
        )
        self.assertEqual(out, HEADER + "core,net.grug,Outer,4,9,0,0,1,1,1,1,1,1\n")
        self.assertIsNone(xml.find("package/sourcefile/counter[@type='BRANCH']"))

    def test_ignores_a_class_only_one_report_holds(self):
        rows = "core,net.grug,Outer.Kind,13,0,2,0,2,0,2,0,2,0\n"
        out, _xml = self.rescue(counted=[("$Kind", 9, 2)], everything=[], rows=rows)
        self.assertEqual(out, HEADER + rows)


class CarryOverTest(unittest.TestCase):
    """The --carry-over path of main(): the JaCoCo CLI is stubbed, so only what each runner's
    report is handed is under test, which is exactly what the carry-over changes: the carried exec
    of a runner's test reaches that runner's report, where its loader classes count, and core's,
    which unions every runner's exec data."""

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        artifacts = self.root / "artifacts"
        for runner in ("core", "1.20.6-forge"):
            (artifacts / f"coverage-exec-{runner}").mkdir(parents=True)
            (artifacts / f"coverage-exec-{runner}" / "000-boot.exec").write_bytes(b"")
            (artifacts / f"coverage-classes-{runner}").mkdir(parents=True)
            (artifacts / f"coverage-classes-{runner}" / "C.class").write_bytes(b"")
        self.artifacts = artifacts
        self.output = self.root / "output"
        self.runner = "1.20.6-forge"
        self.carry_exec = self.root / "coverage.tests.a-Test.grug.exec"
        self.carry_exec.write_bytes(b"")
        self.report_calls = []
        self.csv_row = ""
        # One test gives the report stub a missed initializer that only the carried replay makes
        # run, which is the #897 shape: nothing of this run's tests can make it run.
        self.initializer_rescue = False
        self.replay_exec = None
        self.orig_report = combine.jacoco_report
        self.orig_argv = sys.argv
        self.addCleanup(setattr, sys, "argv", self.orig_argv)
        self.errors = ""

    def fake_report(self, cli, execs, classes, sources, csv_path, xml_path):
        self.report_calls.append((tuple(execs), csv_path))
        if self.initializer_rescue and csv_path.name == "core.csv":
            # The initializer ran in the other mod's segment, which the carried replay holds, and
            # nowhere else: the same shape a real run's dropped data gives the rescue.
            covered = self.replay_exec is not None and self.replay_exec in execs
            row = (
                "core,net.grug,Outer.Kind,0,9,0,0,0,1,0,1,0,1"
                if covered
                else "core,net.grug,Outer.Kind,9,0,0,0,1,0,1,0,1,0"
            )
            csv_path.write_text(HEADER + row + "\n")
            xml_path.write_text(report_xml([("$Kind", 0 if covered else 9, 0)]))
            return
        if csv_path.name == "1.20.6-forge.csv":
            # A loader's own class that the carried-over test alone covers: the loader's report
            # counts it only when it was handed that test's exec, the defect this pins.
            carried = any(path.name == self.carry_exec.name for path in execs)
            row = (
                "1.20.6-forge,net.grug,ForgeAdapter,0,4,0,0,0,0,0,0,0,0"
                if carried
                else "1.20.6-forge,net.grug,ForgeAdapter,4,0,0,0,0,0,0,0,0,0"
            )
            csv_path.write_text(HEADER + row + "\n")
        else:
            csv_path.write_text(HEADER + self.csv_row)
        xml_path.write_text('<report name="r"></report>')

    def run_main(self, *extra_args):
        sys.argv = [
            "combine-coverage.py",
            "--artifacts",
            str(self.artifacts),
            "--jacoco-cli",
            str(self.root / "cli.jar"),
            "--output",
            str(self.output),
            *extra_args,
        ]
        # Patched for this run only: this module's later classes use the real report, and a stub
        # left behind would make their JaCoCo invocations silently do nothing.
        errors = io.StringIO()
        combine.jacoco_report = self.fake_report
        try:
            with contextlib.redirect_stderr(errors):
                return combine.main()
        finally:
            combine.jacoco_report = self.orig_report
            self.errors = errors.getvalue()

    def report_execs(self, name, everything=False):
        return next(
            execs
            for execs, csv_path in self.report_calls
            if csv_path.name == name
            and (csv_path.parent.name == "everything-reports") == everything
        )

    def core_report_execs(self, everything=False):
        return self.report_execs("core.csv", everything)

    def loader_report_execs(self, everything=False):
        return self.report_execs("1.20.6-forge.csv", everything)

    def test_a_carried_exec_counts_for_its_own_runner_and_for_core(self):
        self.assertEqual(
            self.run_main("--carry-over", f"{self.runner}:{self.carry_exec}"),
            0,
        )
        core_execs = [path.name for path in self.core_report_execs()]
        self.assertIn("coverage.tests.a-Test.grug.exec", core_execs)
        self.assertIn("000-boot.exec", core_execs)
        # The loader's report analyses the carried exec against the loader's own classes, which
        # is what makes a loader class the test covered count.
        loader_execs = [path.name for path in self.loader_report_execs()]
        self.assertIn("coverage.tests.a-Test.grug.exec", loader_execs)

    def test_a_carried_exec_of_another_runner_is_not_counted_for_a_loader(self):
        # The test's coverage of a class no other loader holds cannot be analysed by this one.
        self.assertEqual(
            self.run_main("--carry-over", f"core:{self.carry_exec}"),
            0,
        )
        self.assertIn(
            "coverage.tests.a-Test.grug.exec", [path.name for path in self.core_report_execs()]
        )
        self.assertNotIn(
            "coverage.tests.a-Test.grug.exec",
            [path.name for path in self.loader_report_execs()],
        )

    def test_a_carried_exec_is_what_covers_a_loader_class(self):
        # The loader's own class is covered only by the carried-over test: handed to the loader's
        # report, the line is covered and the gate passes; named for another runner, the loader's
        # line is missed and the gate fails, which is what the per-runner split prevents.
        self.assertEqual(
            self.run_main("--carry-over", f"{self.runner}:{self.carry_exec}", "--require-100"), 0
        )
        self.assertEqual(
            self.run_main("--carry-over", f"another-loader:{self.carry_exec}", "--require-100"), 1
        )

    def test_a_missing_carry_over_exec_is_dropped_not_fatal(self):
        missing = self.root / "gone.exec"
        self.assertEqual(self.run_main("--carry-over", f"{self.runner}:{missing}"), 0)
        # A file an artifact rename or expiry left behind fails safe, but the log has to name it:
        # an under-count with no line about the missing file reads like a test that simply ran.
        self.assertIn(f"--carry-over names {missing}", self.errors)
        self.assertIn("is not a file", self.errors)
        self.assertIn("not counted", self.errors)
        for execs, _name in self.report_calls:
            for path in execs:
                self.assertTrue(path.is_file(), path)

    def test_a_carried_other_mod_exec_is_only_the_everything_reports_dropped_data(self):
        # #897: the other mods' replay is the base run's dropped data, which the gate reads only
        # for the class initializer it made run. Handing it to the counted report would count
        # another mod's test as a coverage test that ran.
        other_mods = self.root / "other-mods"
        other_mods.mkdir()
        replay = other_mods / "1.20.6-forge.exec"
        replay.write_bytes(b"")
        self.assertEqual(self.run_main("--carry-over-dropped", f"{self.runner}:{replay}"), 0)
        self.assertNotIn(replay, self.core_report_execs())
        self.assertIn(replay, self.core_report_execs(everything=True))
        self.assertIn(replay, self.loader_report_execs(everything=True))

    def test_a_carried_other_mod_exec_rescues_a_missed_initializer(self):
        # #897, end to end through main: the initializer ran only in another mod's segment of the
        # base run. Without the replay the counted report misses it and the gate fails; with it,
        # the replay reaches only the everything report, the rescue moves the initializer in the
        # counted CSV and XML, and the uploaded report says what the CSV says.
        other_mods = self.root / "other-mods"
        other_mods.mkdir()
        replay = other_mods / "1.20.6-forge.exec"
        replay.write_bytes(b"")
        self.replay_exec = replay
        self.initializer_rescue = True
        # The loader class is covered by its carried test in both runs, so only the initializer
        # can be what the gate counts as missed.
        carried = ("--carry-over", f"{self.runner}:{self.carry_exec}")
        self.assertEqual(self.run_main(*carried, "--require-100"), 1)
        self.assertEqual(
            self.run_main(
                *carried, "--carry-over-dropped", f"{self.runner}:{replay}", "--require-100"
            ),
            0,
        )
        with (self.output / "core.csv").open(newline="") as handle:
            row = next(row for row in csv.DictReader(handle) if row["CLASS"] == "Outer.Kind")
        self.assertEqual((row["INSTRUCTION_MISSED"], row["INSTRUCTION_COVERED"]), ("0", "9"))
        clinit = ET.parse(self.output / "core.xml").getroot().find("package/class/method")
        self.assertEqual(
            [
                (counter.get("type"), counter.get("missed"), counter.get("covered"))
                for counter in clinit.findall("counter")
            ][0],
            ("INSTRUCTION", "0", "9"),
        )

    def test_a_carried_other_mod_exec_counts_for_its_own_loader_and_core(self):
        replay = self.root / "another-loader.exec"
        replay.write_bytes(b"")
        # The loader has a dropped segment of its own, so it gets an everything report at all,
        # which is where another loader's replay must not appear.
        (self.artifacts / "coverage-exec-1.20.6-forge" / "001-jei.tests.x.dropped").write_bytes(b"")
        self.assertEqual(self.run_main("--carry-over-dropped", f"another-loader:{replay}"), 0)
        # Core unions every runner's dropped data, so another loader's replay counts there; a
        # loader's report analyses only the execs recorded against its own classes.
        self.assertIn(replay, self.core_report_execs(everything=True))
        self.assertNotIn(replay, self.loader_report_execs(everything=True))

    def test_a_missing_carry_over_dropped_exec_is_dropped_not_fatal(self):
        missing = self.root / "gone-dropped.exec"
        self.assertEqual(self.run_main("--carry-over-dropped", f"{self.runner}:{missing}"), 0)
        self.assertIn(f"--carry-over-dropped names {missing}", self.errors)
        self.assertIn("is not a file", self.errors)
        self.assertIn("not counted", self.errors)

    def test_a_carry_over_dropped_without_a_runner_is_an_error(self):
        replay = self.root / "replay.exec"
        replay.write_bytes(b"")
        self.assertEqual(self.run_main("--carry-over-dropped", str(replay)), 1)
        self.assertEqual(self.report_calls, [])

    def test_the_report_stub_does_not_outlive_the_run(self):
        # The stub exists to record what each report was handed, and this module's next class runs
        # in the same process: one left patched would make its real JaCoCo invocation a no-op.
        self.assertEqual(self.run_main("--carry-over", f"{self.runner}:{self.carry_exec}"), 0)
        self.assertIs(combine.jacoco_report, self.orig_report)

    def test_a_carry_over_without_a_runner_is_an_error(self):
        # The runner names which report's classes the exec was recorded against, and a spec
        # without one would count a loader's classes nowhere: refuse it rather than guess.
        self.assertEqual(self.run_main("--carry-over", str(self.carry_exec)), 1)
        self.assertEqual(self.report_calls, [])

    def test_the_gate_still_fails_when_the_carry_over_leaves_a_missed_line(self):
        # A carry-over counts coverage, it is not a pass: a missed line still fails the gate.
        self.csv_row = "core,net.grug,Outer,4,0,0,0,1,0,1,0,1,0\n"
        self.assertEqual(
            self.run_main("--carry-over", f"{self.runner}:{self.carry_exec}", "--require-100"), 1
        )

    def test_the_gate_passes_when_the_carry_over_covers_the_last_line(self):
        self.csv_row = "core,net.grug,Outer,0,4,0,0,0,1,0,1,0,1\n"
        self.assertEqual(
            self.run_main("--carry-over", f"{self.runner}:{self.carry_exec}", "--require-100"),
            0,
        )


if __name__ == "__main__":
    unittest.main()
