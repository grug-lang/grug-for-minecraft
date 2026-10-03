#!/usr/bin/env python3
"""Tests the vanilla block name audit.

What is worth pinning is what makes the audit fail: a row with the wrong number of columns, a
canonical name that is not a modern Minecraft name, the same spelling given to two rows on one
loader, a loader directory with no column, a column naming a block the loader does not have, and a
block the loader has that neither its column nor the unmapped list records. The committed table and
unmapped list are checked too, so a bad edit fails here rather than in a game.
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

    def errors(self, table: vanilla_blocks.Table, dumps: dict, unmapped: dict = None) -> list:
        return vanilla_blocks.verify_blocks_errors(table, dumps, unmapped)

    def test_a_dump_naming_every_column_spelling_passes(self):
        table = self.table(f"minecraft:stone {COLUMNS}")
        dumps = {"a": {"1": "one"}}
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

    def test_a_block_the_loader_has_but_no_row_mentions_is_an_error(self):
        # This is the direction that hid the missing rows: the loader had the block and the table
        # believed it did not, so place_block failed on a block that existed.
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = self.errors(table, {"a": {"1": "one", "2": "granite"}})
        self.assertTrue(any("no row mentions it" in m for _n, m in errors), errors)

    def test_an_unmentioned_block_in_the_unmapped_list_passes(self):
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = self.errors(table, {"a": {"1": "one", "2": "granite"}}, {"a": {"granite"}})
        self.assertEqual(errors, [])

    def test_the_canonical_column_is_not_checked_for_completeness(self):
        # 1.20.6 is the registry itself: most of its blocks have no row because no older loader has
        # them, so only the column-to-dump direction applies.
        table = vanilla_blocks.parse(
            table_text("minecraft:stone one two three four five"),
            ["a", "b", "c", "d", vanilla_blocks.CANONICAL_LOADER],
        )
        errors = self.errors(
            table, {vanilla_blocks.CANONICAL_LOADER: {"1": "five", "2": "granite"}}
        )
        self.assertEqual(errors, [])

    def test_unmapped_errors_flag_an_unknown_loader(self):
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = vanilla_blocks.unmapped_errors(table, {"z": {"granite"}})
        self.assertTrue(any("not a loader under loaders/" in m for _n, m in errors), errors)

    def test_unmapped_errors_flag_a_name_the_table_maps(self):
        # A stale entry would hide the next block that really is unmapped.
        table = self.table(f"minecraft:stone {COLUMNS}")
        errors = vanilla_blocks.unmapped_errors(table, {"a": {"one"}})
        self.assertTrue(any("maps it" in m for _n, m in errors), errors)

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
        # The cases the audit missed once, so a regenerated table cannot quietly lose them. Each is
        # (loader, canonical name, the loader's spelling).
        cases = [
            ("1.2.5-forge", "minecraft:redstone_torch", "activeredstonetorch"),
            ("b1.7.3-stationapi", "minecraft:redstone_torch", "redstone_torch_lit"),
            ("b1.7.3-stationapi", "minecraft:unlit_redstone_torch", "redstone_torch"),
            ("1.2.5-forge", "minecraft:crafting_table", "workbench"),
            # The unlit ore is the modern redstone_ore, and the lit one is the pre-1.13 name.
            ("1.2.5-forge", "minecraft:redstone_ore", "oreredstone"),
            ("1.2.5-forge", "minecraft:lit_redstone_ore", "glowingoreredstone"),
            ("a1.1.2_01-ornithe", "minecraft:redstone_ore", "redstone_ore"),
            ("b1.7.3-ornithe", "minecraft:redstone_ore", "redstone_ore"),
            ("b1.7.3-stationapi", "minecraft:redstone_ore", "redstone_ore"),
            # The fields a regex over the decompiled Block.java missed, because the declared type
            # is a subclass or the constructor carries no id.
            ("1.2.5-forge", "minecraft:grass_block", "grass"),
            ("1.2.5-forge", "minecraft:oak_leaves", "leaves"),
            ("1.2.5-forge", "minecraft:white_wool", "cloth"),
            ("1.2.5-forge", "minecraft:dandelion", "plantyellow"),
            ("1.2.5-forge", "minecraft:poppy", "plantred"),
            ("1.2.5-forge", "minecraft:brown_mushroom", "brownmushroom"),
            ("1.2.5-forge", "minecraft:red_mushroom", "mushroomred"),
            ("1.2.5-forge", "minecraft:dead_bush", "bushdead"),
            ("1.2.5-forge", "minecraft:short_grass", "grasstall"),
            ("1.2.5-forge", "minecraft:moving_piston", "movingpiston"),
            ("1.2.5-forge", "minecraft:nether_portal", "portal"),
            ("1.2.5-forge", "minecraft:piston_head", "extensionpiston"),
            ("b1.7.3-ornithe", "minecraft:white_wool", "wool"),
            ("b1.7.3-stationapi", "minecraft:oak_leaves", "leaves"),
            ("b1.7.3-stationapi", "minecraft:white_wool", "wool"),
            ("b1.7.3-stationapi", "minecraft:poppy", "rose"),
            ("b1.7.3-stationapi", "minecraft:short_grass", "grass"),
            # The alias the removed 1.2.5 stopgap carried, now a row.
            ("1.2.5-forge", "minecraft:enchanting_table", "enchantmenttable"),
            # The blocks 1.2.5 added after Beta, which 1.20.6 also has.
            ("1.2.5-forge", "minecraft:brewing_stand", "brewingstand"),
            ("1.2.5-forge", "minecraft:end_stone", "stonewhite"),
            ("1.2.5-forge", "minecraft:lily_pad", "waterlily"),
        ]
        for loader_id, canonical, spelling in cases:
            with self.subTest(loader=loader_id, canonical=canonical):
                self.assertEqual(self.table.spellings(loader_id)[canonical], spelling)

    def test_the_canonical_column_is_the_registry_spelling(self):
        # The 1.20.6 column is the reference the others are read against, so a cell is either a dash
        # or the canonical name itself.
        column = self.table.loaders.index(vanilla_blocks.CANONICAL_LOADER)
        for canonical, row in self.table.rows:
            if row[column] != vanilla_blocks.NO_BLOCK:
                self.assertEqual(row[column], canonical[len("minecraft:") :], canonical)

    def test_the_committed_unmapped_list_is_consistent(self):
        unmapped = vanilla_blocks.read_unmapped(REPOSITORY / "vanilla_blocks_unmapped.txt")
        self.assertEqual(vanilla_blocks.unmapped_errors(self.table, unmapped), [])
        # The no-canonical decisions the dumps found, so a regeneration cannot drop them silently.
        self.assertIn("movingwater", unmapped["1.2.5-forge"])
        self.assertIn("lit_furnace", unmapped["a1.1.2_01-ornithe"])
        self.assertIn("chest_locked_april_fools", unmapped["b1.7.3-ornithe"])
        self.assertIn("locked_chest", unmapped["b1.7.3-stationapi"])

    def test_the_unmapped_list_has_no_entry_for_the_canonical_column(self):
        unmapped = vanilla_blocks.read_unmapped(REPOSITORY / "vanilla_blocks_unmapped.txt")
        self.assertNotIn(vanilla_blocks.CANONICAL_LOADER, unmapped)

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

    def write(self, text: str, unmapped: str = "") -> Path:
        directory = Path(tempfile.mkdtemp())
        loaders = directory / "loaders"
        for loader in ["a", "b", "c", "d", "e"]:
            (loaders / loader).mkdir(parents=True)
        table = directory / "vanilla_blocks.txt"
        table.write_text(text, encoding="utf-8")
        (directory / "unmapped.txt").write_text(unmapped, encoding="utf-8")
        return table

    def dump(self, text: str) -> Path:
        path = Path(tempfile.mkdtemp()) / "dump.txt"
        path.write_text(text, encoding="utf-8")
        return path

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

    def test_verify_blocks_requires_the_unmapped_list(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"))
        dump = self.dump("1 one\n")
        self.assertEqual(
            self.run_main(
                "--table",
                str(table),
                "--loaders",
                str(table.parent / "loaders"),
                "--verify-blocks",
                f"a={dump}",
                "--unmapped",
                "/nonexistent/unmapped.txt",
            ),
            1,
        )

    def test_verify_blocks_passes_with_the_unmapped_list(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"), "a granite\n")
        dump = self.dump("1 one\n2 granite\n")
        self.assertEqual(
            self.run_main(
                "--table",
                str(table),
                "--loaders",
                str(table.parent / "loaders"),
                "--verify-blocks",
                f"a={dump}",
                "--unmapped",
                str(table.parent / "unmapped.txt"),
            ),
            0,
        )

    def test_verify_blocks_fails_on_an_unrecorded_block(self):
        table = self.write(table_text(f"minecraft:stone {COLUMNS}"))
        dump = self.dump("1 one\n2 granite\n")
        self.assertEqual(
            self.run_main(
                "--table",
                str(table),
                "--loaders",
                str(table.parent / "loaders"),
                "--verify-blocks",
                f"a={dump}",
                "--unmapped",
                str(table.parent / "unmapped.txt"),
            ),
            1,
        )


if __name__ == "__main__":
    unittest.main()
