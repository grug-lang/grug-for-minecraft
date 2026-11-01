package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Covers reading the name and id table a pre-registry loader's build writes. */
class GrugVanillaIdsTest {

    private static final String TABLE = "vanilla_names.txt";

    /**
     * Drains the fatal a test provoked. {@code Grug.fatal} remembers the first error per thread and
     * rethrows it at the next native call boundary, so leaving one set would fail the next test on
     * this JVM that drives an export function, for a reason unrelated to that test.
     */
    @AfterEach
    void drainPendingFatal() {
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

    private static InputStream table(String content) {
        return new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8));
    }

    private static InputStream names() {
        return table("B stone 1\nB ingotiron 42\nI diamond 264\nI swordwood 268\n");
    }

    @Test
    void aBlockNameResolvesToItsId() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, names());

        assertEquals(1, ids.blockId("stone"));
        assertEquals(42, ids.blockId("iron_ingot"));
    }

    @Test
    void anItemNameResolvesToItsId() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, names());

        assertEquals(264, ids.itemId("diamond"));
        assertEquals(268, ids.itemId("woodSword"));
    }

    @Test
    void aBlockIdResolvesBackToTheNameTheTableKeyedItBy() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, names());

        assertEquals("stone", ids.blockName(1));
        assertEquals("ingotiron", ids.blockName(42));
    }

    @Test
    void aNameTheTableLacksResolvesToNothing() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, names());

        assertEquals(-1, ids.blockId("gravel"));
        assertEquals(-1, ids.itemId("gravel"));
        assertNull(ids.blockName(999));
    }

    @Test
    void aNameIsMatchedRegardlessOfCamelCaseAndWordOrder() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, names());

        assertEquals(42, ids.blockId("ironIngot"));
        assertEquals(268, ids.itemId("wood_sword"));
    }

    @Test
    void aJarWithoutTheTableIsFatal() {
        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> GrugVanillaIds.read(TABLE, null));

        assertTrue(error.getMessage().contains(TABLE), error.getMessage());
    }

    @Test
    void aLineThatIsNotAKindANameAndAnIdIsSkipped() {
        // The build writes the table from the tool's stdout, so the game's own prints land in it.
        GrugVanillaIds ids =
                GrugVanillaIds.read(TABLE, table("27 achievements\nB stone 1\n182 recipes\n"));

        assertEquals(1, ids.blockId("stone"));
    }

    @Test
    void aThreeWordLineThatIsNotANameAndAnIdIsSkipped() {
        GrugVanillaIds ids =
                GrugVanillaIds.read(TABLE, table("B stone 1\nLoaded 27 achievements\n"));

        assertEquals(1, ids.blockId("stone"));
    }

    @Test
    void aLineWhoseKindIsNeitherBlockNorItemIsSkipped() {
        GrugVanillaIds ids = GrugVanillaIds.read(TABLE, table("B stone 1\nT torch 50\n"));

        assertEquals(1, ids.blockId("stone"));
        assertEquals(-1, ids.itemId("torch"));
    }

    @Test
    void aTableThatCannotBeReadIsFatal() {
        IOException cause = new IOException("disk gone");
        InputStream broken =
                new InputStream() {
                    @Override
                    public int read() throws IOException {
                        throw cause;
                    }
                };

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> GrugVanillaIds.read(TABLE, broken));

        assertSame(cause, error.getCause());
    }
}
