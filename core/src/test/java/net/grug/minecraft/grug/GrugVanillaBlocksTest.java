package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/** Covers the canonical block name table every loader reads out of the core jar. */
class GrugVanillaBlocksTest {

    /** Five loader columns, so a test table has the width {@link GrugVanillaBlocks} insists on. */
    private static final String FIVE_COLUMNS = " stone stone stone stone stone";

    @Test
    void everyCanonicalNameRoundTripsThroughEveryLoader() {
        // The property the table exists for: a name means the same block going in as it does coming
        // back out. This holds for every loader including one that does not have the block, because
        // such a name goes in and comes back out unchanged.
        for (String loaderId : GrugVanillaBlocks.loaderIds()) {
            GrugVanillaBlocks.Loader loader = GrugVanillaBlocks.forLoader(loaderId);
            for (String canonical : GrugVanillaBlocks.canonicalNames()) {
                String local = loader.localName(canonical);
                assertEquals(
                        canonical,
                        loader.canonicalName(local),
                        loaderId + " does not get " + canonical + " back from " + local);
            }
        }
    }

    @Test
    void everyCanonicalNameIsNamespacedAndListedOnce() {
        List<String> names = GrugVanillaBlocks.canonicalNames();
        assertEquals(
                names.size(),
                names.stream().distinct().count(),
                "a canonical name is listed twice");
        for (String canonical : names) {
            assertTrue(canonical.startsWith("minecraft:"), canonical + " is not a minecraft: name");
        }
    }

    @Test
    void aLoaderResolvesTheBlockACanonicalNameNamesToIt() {
        // The lit torch is the case from #58: four loaders spell it redstone_torch and 1.2.5's
        // obfuscated runtime knows it as a word-ordered key, but all five put the lit block there.
        // StationAPI's own name for it is the reverse, which is the mismatch the table absorbs.
        assertEquals(
                "activeredstonetorch",
                GrugVanillaBlocks.forLoader("1.2.5-forge").localName("minecraft:redstone_torch"));
        assertEquals(
                "redstone_torch",
                GrugVanillaBlocks.forLoader("b1.7.3-ornithe").localName("redstone_torch"));
        assertEquals(
                "redstone_torch_lit",
                GrugVanillaBlocks.forLoader("b1.7.3-stationapi")
                        .localName("minecraft:redstone_torch"));
    }

    @Test
    void aLoaderReportsTheCanonicalNameForTheBlockItSpellsItsOwnWay() {
        assertEquals(
                "minecraft:crafting_table",
                GrugVanillaBlocks.forLoader("1.2.5-forge").canonicalName("workbench"));
        // 1.2.5's column is a word-ordered key, so this is the key its lookup table is built from
        // rather than the MCP field name the key came from.
        assertEquals(
                "minecraft:iron_ore",
                GrugVanillaBlocks.forLoader("1.2.5-forge").canonicalName("ironore"));
        assertEquals(
                "minecraft:redstone_ore",
                GrugVanillaBlocks.forLoader("b1.7.3-ornithe").canonicalName("lit_redstone_ore"));
    }

    @Test
    void aNameTheTableDoesNotCoverKeepsTheLoadersOwnSpelling() {
        // A block from another mod, or a vanilla block added after the audit: it is not ours to
        // rename, and renaming it to a name it does not resolve would be worse.
        assertEquals(
                "minecraft:grug_something",
                GrugVanillaBlocks.forLoader("1.2.5-forge").canonicalName("grug_something"));
        // A name the audit does not cover goes to the loader's own resolver exactly as it was
        // written, namespace or not.
        assertEquals(
                "cherry_log", GrugVanillaBlocks.forLoader("1.2.5-forge").localName("cherry_log"));
        assertEquals(
                "cherry_log",
                GrugVanillaBlocks.forLoader("1.2.5-forge").localName("minecraft:cherry_log"));
    }

    @Test
    void anUnknownLoaderIsRejected() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugVanillaBlocks.forLoader("1.7.10-forge"));
    }

    @Test
    void theTableInTheJarHasAColumnForEveryLoader() {
        // Guards the packaging rather than the parsing: the file has to be a core resource, because
        // that is the only place all five loaders can find it.
        assertNotNull(
                GrugVanillaBlocks.class.getResourceAsStream("/vanilla_blocks.txt"),
                "vanilla_blocks.txt is not a core resource");
        assertEquals(
                GrugVanillaBlocks.loaderIds().size(), 5, "the table has one column per loader");
        assertTrue(GrugVanillaBlocks.canonicalNames().size() > 0, "the table is empty");
    }

    @Test
    void aRowThatStopsBeforeTheLastLoaderIsRejected() throws IOException {
        // A short row would leave a loader believing a block it does not have is one it can place,
        // which is the silent mismatch this table exists to remove.
        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> parse("minecraft:stone stone stone stone stone"));
        assertTrue(
                failure.getMessage().contains("has 5 columns"),
                "the failure should say how short the row is: " + failure.getMessage());
    }

    @Test
    void commentsAndBlankLinesAreNotRows() throws IOException {
        GrugVanillaBlocks.Table table =
                parse("# a comment\n\nminecraft:stone" + FIVE_COLUMNS + "\n");
        assertEquals(1, table.canonicalNames().size());
    }

    @Test
    void aDashColumnIsLeftToTheLoadersOwnResolver() throws IOException {
        // A dash records that the audit found no such block on that loader, so there is nothing to
        // map
        // to and the name goes to that loader's resolver as a mod wrote it. The other columns of
        // the
        // row are unaffected.
        GrugVanillaBlocks.Table table = parse("minecraft:stone stone - stone stone stone\n");
        assertEquals("stone", table.loader("1.2.5-forge").localName("minecraft:stone"));
        assertEquals("stone", table.loader("1.20.6-forge").localName("minecraft:stone"));
        assertEquals("stone", table.loader("b1.7.3-ornithe").localName("minecraft:stone"));
    }

    /** Parses a table of the test's own, without touching the one the loaders read. */
    private static GrugVanillaBlocks.Table parse(String table) throws IOException {
        return GrugVanillaBlocks.parse(
                new ByteArrayInputStream(table.getBytes(StandardCharsets.UTF_8)));
    }
}
