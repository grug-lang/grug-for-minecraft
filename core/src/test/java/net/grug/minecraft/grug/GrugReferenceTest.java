package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Path;

/**
 * Covers {@link GrugReference}'s flag parsing, which decides which branch a recreation test takes.
 */
class GrugReferenceTest {

    @AfterEach
    void clearTheProperties() {
        System.clearProperty(GrugReference.PROPERTY);
        System.clearProperty(GrugReference.MODS_DIR_PROPERTY);
        System.clearProperty(GrugReference.CI_PROPERTY);
        System.clearProperty(GrugReference.UPDATE_GOLDENS_PROPERTY);
    }

    @Test
    void anUnsetOrExplicitlyOffFlagIsFalse() {
        assertFalse(GrugReference.isTruthy(null));
        assertFalse(GrugReference.isTruthy(""));
        assertFalse(GrugReference.isTruthy("0"));
        assertFalse(GrugReference.isTruthy("false"));
        assertFalse(GrugReference.isTruthy("FALSE"));
    }

    @Test
    void anyOtherSetValueIsTrue() {
        assertTrue(GrugReference.isTruthy("1"));
        assertTrue(GrugReference.isTruthy("true"));
        assertTrue(GrugReference.isTruthy("yes"));
    }

    @Test
    void isReferenceRunReadsTheProperty() {
        System.setProperty(GrugReference.PROPERTY, "1");
        assertTrue(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "0");
        assertFalse(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "true");
        assertTrue(GrugReference.isReferenceRun());

        System.setProperty(GrugReference.PROPERTY, "false");
        assertFalse(GrugReference.isReferenceRun());
    }

    @Test
    void isCiRunReadsTheProperty() {
        System.setProperty(GrugReference.CI_PROPERTY, "1");
        assertTrue(GrugReference.isCiRun());

        System.setProperty(GrugReference.CI_PROPERTY, "0");
        assertFalse(GrugReference.isCiRun());

        System.setProperty(GrugReference.CI_PROPERTY, "true");
        assertTrue(GrugReference.isCiRun());

        System.setProperty(GrugReference.CI_PROPERTY, "false");
        assertFalse(GrugReference.isCiRun());
    }

    @Test
    void isUpdateGoldensReadsTheProperty() {
        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "1");
        assertTrue(GrugReference.isUpdateGoldens());

        System.setProperty(GrugReference.UPDATE_GOLDENS_PROPERTY, "0");
        assertFalse(GrugReference.isUpdateGoldens());
    }

    @Test
    void modsDirOverrideReturnsADirectoryThatExists(@TempDir Path directory) {
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, directory.toString());
        assertEquals(directory.toFile(), GrugReference.modsDirOverride());
    }

    @Test
    void modsDirOverrideReturnsAMissingPathRatherThanFallingBack() {
        // Falling back silently would load the port into a reference run, so a missing path is
        // returned for the loader to report instead of being treated as unset.
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, "/no/such/grug/mods/directory");
        assertEquals(new File("/no/such/grug/mods/directory"), GrugReference.modsDirOverride());
    }

    @Test
    void modsDirOverrideIsNullForAnEmptyValue() {
        System.setProperty(GrugReference.MODS_DIR_PROPERTY, "");
        assertNull(GrugReference.modsDirOverride());
    }
}
