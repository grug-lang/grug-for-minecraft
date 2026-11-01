#!/usr/bin/env python3
"""Tests markdown-columns.py: today's file passes, a 152-column line fails, and the exemptions."""

import contextlib
import importlib.util
import io
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location("markdown_columns", HERE / "markdown-columns.py")
markdown_columns = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(markdown_columns)

AGENTS = HERE.parent.parent / "AGENTS.md"


def a_line(columns: int) -> str:
    """A wrappable line of exactly that many columns."""
    return "wrap " * (columns // 5) + "x" * (columns % 5)


class MarkdownColumnsTest(unittest.TestCase):
    def test_todays_agents_file_is_wrapped(self):
        self.assertEqual([], markdown_columns.long_lines(AGENTS.read_text(encoding="utf-8")))

    def test_a_152_column_line_is_named_with_its_line_number(self):
        text = "# notes\n\n" + a_line(152) + "\n"
        self.assertEqual(152, len(a_line(152)))
        self.assertEqual([(3, 152)], markdown_columns.long_lines(text))

    def test_a_line_at_the_limit_passes_and_one_column_over_fails(self):
        self.assertEqual([], markdown_columns.long_lines(a_line(markdown_columns.LIMIT)))
        self.assertEqual(
            [(1, markdown_columns.LIMIT + 1)],
            markdown_columns.long_lines(a_line(markdown_columns.LIMIT + 1)),
        )

    def test_a_table_row_is_exempt_because_markdown_cannot_wrap_it(self):
        self.assertEqual([], markdown_columns.long_lines(f"| path | {a_line(200)} |"))

    def test_a_word_that_no_wrap_can_shorten_is_exempt(self):
        url = "https://example.invalid/" + "a" * 200
        self.assertEqual([], markdown_columns.long_lines(f"- see [{url}]({url}) for the grammar"))

    def test_an_unshortenable_word_exempts_only_the_line_it_is_on(self):
        url = "https://example.invalid/" + "a" * 200
        text = f"- see [{url}]({url})\n" + a_line(152) + "\n"
        self.assertEqual([(2, 152)], markdown_columns.long_lines(text))

    def test_main_passes_on_todays_file_and_names_the_line_it_fails_on(self):
        with contextlib.redirect_stdout(io.StringIO()) as printed:
            self.assertEqual(0, markdown_columns.main([str(AGENTS)]))
        self.assertEqual("", printed.getvalue())

        with tempfile.TemporaryDirectory() as directory:
            long = Path(directory) / "long.md"
            long.write_text("# notes\n\n" + a_line(152) + "\n", encoding="utf-8")
            with contextlib.redirect_stdout(io.StringIO()) as printed:
                self.assertEqual(1, markdown_columns.main([str(long)]))
        self.assertIn(f"{long}:3: 152 columns", printed.getvalue())


if __name__ == "__main__":
    unittest.main()
