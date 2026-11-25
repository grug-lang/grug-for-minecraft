package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Covers {@link GrugModFile}, the handle {@code File.open} returns. */
class GrugModFileTest {

    @Test
    void answersTheModItWasOpenedFor() {
        assertEquals("coverage", new GrugModFile("coverage").modName());
    }

    @Test
    void anOpenThatFailedHoldsNoMod() {
        assertNull(new GrugModFile(null).modName());
    }
}
