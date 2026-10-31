#!/usr/bin/env python3
"""Tests combine-coverage.py: a class initializer that only ran in a dropped segment counts, and
the recorded coverage of a carried-over test counts for core only (#786)."""

import importlib.util
import sys
import tempfile
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location(
    "combine_coverage", Path(__file__).resolve().parent / "combine-coverage.py"
)
combine = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(combine)

HEADER = "GROUP,PACKAGE,CLASS,INSTRUCTION_MISSED,INSTRUCTION_COVERED,BRANCH_MISSED,BRANCH_COVERED\n"


def report_xml(clinits):
    """A JaCoCo XML report holding the given <clinit> counters: (class, instr missed, branch missed)."""
    classes = ""
    for name, instructions, branches in clinits:
        branch_counter = (
            f'<counter type="BRANCH" missed="{branches}" covered="{2 - branches}"/>'
            if branches is not None
            else ""
        )
        classes += (
            f'<class name="net/grug/Outer{name}">'
            f'<method name="&lt;clinit&gt;" desc="()V" line="1">'
            f'<counter type="INSTRUCTION" missed="{instructions}" covered="{9 - instructions}"/>'
            f"{branch_counter}</method>"
            f'<method name="run" desc="()V" line="2">'
            f'<counter type="INSTRUCTION" missed="4" covered="0"/></method>'
            f"</class>"
        )
    return f'<report name="r"><package name="net/grug">{classes}</package></report>'


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
            return (tmp / "report.csv").read_text().replace("\r\n", "\n")

    def test_counts_an_initializer_only_a_dropped_segment_ran(self):
        out = self.rescue(
            counted=[("$Kind", 9, 2)],
            everything=[("$Kind", 0, 0)],
            rows="core,net.grug,Outer.Kind,13,0,2,0\n",
        )
        # The initializer's 9 instructions and 2 branches move to covered; run()'s 4 stay missed.
        self.assertEqual(out, HEADER + "core,net.grug,Outer.Kind,4,9,0,2\n")

    def test_leaves_an_initializer_nothing_ran(self):
        rows = "core,net.grug,Outer.Kind,13,0,2,0\n"
        out = self.rescue(counted=[("$Kind", 9, 2)], everything=[("$Kind", 9, 2)], rows=rows)
        self.assertEqual(out, HEADER + rows)

    def test_leaves_what_the_counted_segments_already_covered(self):
        rows = "core,net.grug,Outer.Kind,4,9,0,2\n"
        out = self.rescue(counted=[("$Kind", 0, 0)], everything=[("$Kind", 0, 0)], rows=rows)
        self.assertEqual(out, HEADER + rows)

    def test_an_initializer_without_branches_has_no_branch_counter(self):
        out = self.rescue(
            counted=[("", 9, None)],
            everything=[("", 0, None)],
            rows="core,net.grug,Outer,13,0,0,0\n",
        )
        self.assertEqual(out, HEADER + "core,net.grug,Outer,4,9,0,0\n")

    def test_ignores_a_class_only_one_report_holds(self):
        rows = "core,net.grug,Outer.Kind,13,0,2,0\n"
        out = self.rescue(counted=[("$Kind", 9, 2)], everything=[], rows=rows)
        self.assertEqual(out, HEADER + rows)


class CarryOverTest(unittest.TestCase):
    """The --carry-over path of main(): the JaCoCo CLI is stubbed, so only what each runner's
    report is handed is under test, which is exactly what the carry-over changes: core's report
    gains the carried-over execs and no loader's does."""

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
        self.carry_exec = self.root / "coverage.tests.a-Test.grug.exec"
        self.carry_exec.write_bytes(b"")
        self.report_calls = []
        self.csv_row = ""
        self.orig_report = combine.jacoco_report
        combine.jacoco_report = self.fake_report
        self.orig_argv = sys.argv
        self.addCleanup(setattr, sys, "argv", self.orig_argv)

    def fake_report(self, cli, execs, classes, sources, csv_path, xml_path):
        self.report_calls.append((tuple(execs), csv_path.name))
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
        return combine.main()

    def core_report_execs(self):
        return next(execs for execs, name in self.report_calls if name == "core.csv")

    def loader_report_execs(self):
        return next(execs for execs, name in self.report_calls if name == "1.20.6-forge.csv")

    def test_the_carry_over_execs_count_for_core_only(self):
        self.assertEqual(
            self.run_main("--carry-over", str(self.carry_exec)),
            0,
        )
        core_execs = [path.name for path in self.core_report_execs()]
        self.assertIn("coverage.tests.a-Test.grug.exec", core_execs)
        self.assertIn("000-boot.exec", core_execs)
        loader_execs = [path.name for path in self.loader_report_execs()]
        self.assertNotIn("coverage.tests.a-Test.grug.exec", loader_execs)

    def test_a_missing_carry_over_exec_is_dropped_not_fatal(self):
        self.assertEqual(self.run_main("--carry-over", str(self.root / "gone.exec")), 0)
        for execs, _name in self.report_calls:
            for path in execs:
                self.assertTrue(path.is_file(), path)

    def test_the_gate_still_fails_when_the_carry_over_leaves_a_missed_line(self):
        # A carry-over counts coverage, it is not a pass: a missed line still fails the gate.
        self.csv_row = "core,net.grug,Outer,4,0,0,0\n"
        self.assertEqual(self.run_main("--carry-over", str(self.carry_exec), "--require-100"), 1)

    def test_the_gate_passes_when_the_carry_over_covers_the_last_line(self):
        self.csv_row = "core,net.grug,Outer,0,4,0,0\n"
        self.assertEqual(self.run_main("--carry-over", str(self.carry_exec), "--require-100"), 0)


if __name__ == "__main__":
    unittest.main()
