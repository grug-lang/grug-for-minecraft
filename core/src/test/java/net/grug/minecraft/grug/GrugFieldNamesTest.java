package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** Covers reading the mapped names of vanilla fields that the Ornithe loaders' builds write. */
class GrugFieldNamesTest {

    private static final String TABLE = "grug_vanilla_names.txt";

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

    private static InputStream sticks() {
        return table("intermediary named\nf_70868303 STICK\nf_05581995 DIRT\n");
    }

    @Test
    void anInstalledGameLooksUpItsIntermediaryNames() {
        GrugFieldNames names = GrugFieldNames.read(TABLE, sticks(), "intermediary");

        assertEquals("STICK", names.mapped("f_70868303"));
        assertEquals("DIRT", names.mapped("f_05581995"));
    }

    @Test
    void aDevelopmentRunLooksUpTheMappedNamesThemselves() {
        GrugFieldNames names = GrugFieldNames.read(TABLE, sticks(), "named");

        assertEquals("STICK", names.mapped("STICK"));
    }

    @Test
    void aFieldTheTableLacksIsFatal() {
        GrugFieldNames names = GrugFieldNames.read(TABLE, sticks(), "intermediary");

        IllegalStateException error =
                assertThrows(IllegalStateException.class, () -> names.mapped("f_00000000"));

        assertTrue(error.getMessage().contains("f_00000000"), error.getMessage());
    }

    @Test
    void aJarWithoutTheTableIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFieldNames.read(TABLE, null, "intermediary"));

        assertTrue(error.getMessage().contains(TABLE), error.getMessage());
    }

    @Test
    void aTableWithoutTheRuntimeNamespaceIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFieldNames.read(TABLE, sticks(), "official"));

        assertTrue(error.getMessage().contains("lack official"), error.getMessage());
    }

    @Test
    void aTableWithoutMappedNamesIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GrugFieldNames.read(
                                        TABLE,
                                        table("official intermediary\na f_1\n"),
                                        "official"));

        assertTrue(error.getMessage().contains("lack named"), error.getMessage());
    }

    @Test
    void anEmptyTableIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFieldNames.read(TABLE, table(""), "intermediary"));

        assertTrue(error.getMessage().contains(TABLE + " is empty"), error.getMessage());
    }

    @Test
    void aLineWithoutANameInEachNamespaceIsFatal() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () ->
                                GrugFieldNames.read(
                                        TABLE,
                                        table("intermediary named\nf_70868303\n"),
                                        "intermediary"));

        assertTrue(error.getMessage().contains("\"f_70868303\""), error.getMessage());
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
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugFieldNames.read(TABLE, broken, "intermediary"));

        assertSame(cause, error.getCause());
    }
}
