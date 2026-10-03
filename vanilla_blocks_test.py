#!/usr/bin/env python3
"""Tests the vanilla block name audit.

What is worth pinning is what makes the audit fail: a row with the wrong number of columns, a
canonical name that is not a modern Minecraft name, the same spelling given to two rows on one
loader, a loader directory with no column, and a column naming a block the loader does not have.
The committed table is checked too, so a bad edit fails here rather than in a game.
"""

import tempfile
import unittest
from pathlib import Path

import vanilla_blocks

REPOSITORY = Path(__file__).resolve().parent

# Five columns, one per loader, so a test table matches what parse() insists on.
COLUMNS = "one two three four five"


def table_text(*rows: str) -> str:
    return "# a comment\n\n" + "\n".join(rows) + "\n"


class ParseTest(unittest.TestCase):
    def parse(self, text: str) -> vanilla_blocks.Table:
        return vanilla_blocks.parse(text, ["a", "b", "c", "d", "e"])

    def test_a_row_is_a_canonical_name_and_one_column_per_loader(self):
        table = self.parse(table_text(f"minecraft:stone {COLUMNS}"))
        self.assertEqual(table.canonical_names(), ["minecraft:stone"])
        self.assertEqual(table.rows[0][1], ["one", "two", "three", "four", "five"])

    def test_comments_and_blank_lines_are_not_rows(self):
        table = self.parse(f"# a comment\n\n\nminecraft:stone {COLUMNS}\n")
        self.assertEqual(len(table.rows), 1)

    def test_a_row_that_stops_early_is_refused(self):
        # A short row would leave a loader believing a block it does not have is one it can place,
        # which is the silent mismatch the table exists to remove.
        with self.assertRaises(ValueError) as failure:
            self.parse(table_text("minecraft:stone one two three four"))
        self.assertIn("5 columns, expected 6", str(failure.exception))

    def test_a_table_with_no_rows_is_refused(self):
        with self.assertRaises(ValueError) as failure:
            self.parse("# nothing but comments\n")
        self.assertIn("no rows", str(failure.exception))

    def test_a_dash_column_is_a_block_the_loader_does_not_have(self):
        table = self.parse(table_text("minecraft:stone one - three four five"))
        self.assertEqual(table.spellings("b"), {})
        self.assertEqual(table.spellings("a"), {"minecraft:stone": "one"})


class TableErrorsTest(unittest.TestCase):
    def errors(self, *rows: str) -> list:
        table = vanilla_blocks.parse(table_text(*rows), ["a", "b", "c", "d", "e"])
        return vanilla_blocks.table_errors(table)

    def test_a_good_table_has_no_errors(self):
        self.assertEqual(self.errors(f"minecraft:stone {COLUMNS}"), [])

    def test_a_canonical_name_repeated_is_an_error(self):
        errors = self.errors(f"minecraft:stone {COLUMNS}", "minecraft:stone one two three four six")
        self.assertTrue(any("listed more than once" in message for _name, message in errors))

    def test_a_canonical_name_that_is_not_a_modern_name_is_an_error(self):
        errors = self.errors(f"stone {COLUMNS}")
        self.assertTrue(
            any("lower case snake case" in message for _name, message in errors),
            errors,
        )

    def test_two_rows_naming_one_spelling_ambiguously_is_an_error(self):
        # get_block would have no way to say which block that name means.
        errors = self.errors(f"minecraft:stone {COLUMNS}", "minecraft:dirt one two three four five")
        self.assertTrue(
            any("could not say which block" in message for _n, message in errors), errors
        )

    def test_a_spelling_carrying_a_namespace_is_an_error(self):
        errors = self.errors("minecraft:stone minecraft:one two three four five")
        self.assertTrue(any("carries a namespace" in message for _name, message in errors), errors)

    def test_a_block_no_loader_has_is_an_error(self):
        errors = self.errors("minecraft:stone - - - - -")
        self.assertTrue(any("no loader has it" in message for _name, message in errors), errors)


class VerifyBlocksTest(unittest.TestCase):
    def table(self, *rows: str) -> vanilla_blocks.Table:
        return vanilla_blocks.parse(table_text(*rows), ["a", "b", "c", "d", "e"])

    def errors(self, table: vanilla_blocks.Table, dumps: dict) -> list:
        return vanilla_blocks.verify_blocks_errors(table, dumps)

    def test_a_dump_naming_every_column_spelling_passes(self):
        table = self.table(f"minecraft:stone {COLUMNS}")
        dumps = {"a": {"1": "one", "2": "two"}}
        self.assertEqual(self.errors(table, dumps), [])

    def test_a_spelling_the_loader_does_not_have_is_an_error(self):
        table = self.table(f"minecraft:stone {COLUMNS}", "minecraft:dirt granite - - - -")
        # One spelling is still in common, so the check has something to be wrong about.
        errors = self.errors(table, {"a": {"1": "one"}})
        self.assertTrue(any("has no block called 'granite'" in m for _n, m in errors), errors)

    def test_a_dump_that_mentions_nothing_in_the_column_is_an_error(self):
        # Otherwise the check passes vacuously, which is worse than not running.
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = self.errors(table, {"a": {"1": "granite"}})
        self.assertTrue(
            any("cannot be talking about the same loader" in m for _n, m in errors), errors
        )

    def test_a_dump_for_a_loader_with_no_column_is_an_error(self):
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = self.errors(table, {"z": {"1": "one"}})
        self.assertTrue(any("not a loader under loaders/" in m for _n, m in errors), errors)

    def test_the_blocks_a_column_does_not_mention_are_reported(self):
        table = self.table("minecraft:stone one two three four five")
        report = vanilla_blocks.unmentioned_blocks(table, {"a": {"1": "one", "2": "granite"}})
        self.assertEqual(report, [("a", 2, ["granite"])])


class RepositoryTest(unittest.TestCase):
    """The committed table, and the two files that have to agree about its columns."""

    def setUp(self):
        self.table = vanilla_blocks.parse(
            vanilla_blocks.TABLE.read_text(encoding="utf-8"),
            vanilla_blocks.loaders_in_repository(REPOSITORY / "loaders"),
            vanilla_blocks.TABLE,
        )

    def test_the_committed_table_has_no_errors(self):
        self.assertEqual(vanilla_blocks.table_errors(self.table), [])

    def test_the_committed_table_covers_the_blocks_that_differ(self):
        # The cases from #58, so a regenerated table cannot quietly lose them.
        self.assertEqual(
            self.table.spellings("1.2.5-forge")["minecraft:redstone_torch"],
            "activeredstonetorch",
        )
        self.assertEqual(
            self.table.spellings("b1.7.3-stationapi")["minecraft:redstone_torch"],
            "redstone_torch_lit",
        )
        self.assertEqual(
            self.table.spellings("b1.7.3-stationapi")["minecraft:unlit_redstone_torch"],
            "redstone_torch",
        )
        self.assertEqual(
            self.table.spellings("1.2.5-forge")["minecraft:crafting_table"], "workbench"
        )

    def test_a_loader_without_a_block_says_so_with_a_dash(self):
        # 1.20.6 folded the unlit redstone torch into the lit one, so there is nothing to place.
        self.assertNotIn("minecraft:unlit_redstone_torch", self.table.spellings("1.20.6-forge"))
        self.assertIn("minecraft:unlit_redstone_torch", self.table.spellings("b1.7.3-ornithe"))

    def test_the_java_column_order_is_the_sorted_loader_list(self):
        # GrugVanillaBlocks reads the same file, so the two have to agree on which column is which
        # loader. Only the Java side is spelled out, so it is read back out of the source.
        source = (
            REPOSITORY / "core/src/main/java/net/grug/minecraft/grug/GrugVanillaBlocks.java"
        ).read_text(encoding="utf-8")
        declared = source.split("COLUMN_LOADERS =", 1)[1].split("));", 1)[0]
        self.assertEqual(
            [
                line.strip().strip('",')
                for line in declared.splitlines()
                if line.strip().startswith('"')
            ],
            self.table.loaders,
        )


class CommandTest(unittest.TestCase):
    def run_main(self, *argv: str) -> int:
        import sys

        saved = sys.argv
        sys.argv = ["vanilla_blocks.py", *argv]
        try:
            return vanilla_blocks.main()
        finally:
            sys.argv = saved

    def write(self, text: str) -> Path:
        directory = Path(tempfile.mkdtemp())
        loaders = directory / "loaders"
        for loader in ["a", "b", "c", "d", "e"]:
            (loaders / loader).mkdir(parents=True)
        table = directory / "vanilla_blocks.txt"
        table.write_text(text, encoding="utf-8")
        return table

    def test_a_good_table_passes(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"))
        self.assertEqual(
            self.run_main("--table", str(table), "--loaders", str(table.parent / "loaders")), 0
        )

    def test_a_bad_table_fails(self):
        table = self.write(table_text("stone " + COLUMNS))
        self.assertEqual(
            self.run_main("--table", str(table), "--loaders", str(table.parent / "loaders")), 1
        )

    def test_a_missing_table_fails(self):
        self.assertEqual(self.run_main("--table", "/nonexistent/vanilla_blocks.txt"), 1)

    def test_verify_blocks_needs_a_loader_and_a_file(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"))
        self.assertEqual(
            self.run_main(
                "--table",
                str(table),
                "--loaders",
                str(table.parent / "loaders"),
                "--verify-blocks",
                "a",
            ),
            1,
        )

    def test_verify_blocks_reports_a_missing_dump(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"))
        self.assertEqual(
            self.run_main(
                "--table",
                str(table),
                "--loaders",
                str(table.parent / "loaders"),
                "--verify-blocks",
                "a=/nonexistent/dump.txt",
            ),
            1,
        )


if __name__ == "__main__":
    unittest.main()
