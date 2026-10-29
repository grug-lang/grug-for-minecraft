#!/usr/bin/env python3
"""Tests combine-coverage.py: a class initializer that only ran in a dropped segment counts."""

import importlib.util
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


if __name__ == "__main__":
    unittest.main()
