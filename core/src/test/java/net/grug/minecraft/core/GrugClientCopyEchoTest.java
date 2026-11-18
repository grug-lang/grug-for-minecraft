package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugClientCopyEchoTest {
    @Test
    void aClientsCopyOfAServersBlockEntityNeverMarksItselfDirty() {
        assertFalse(GrugClientCopyEcho.mayMarkDirty(true));
    }

    @Test
    void aServersOrSingleplayerBlockEntityMarksItselfDirtyToBeSaved() {
        assertTrue(GrugClientCopyEcho.mayMarkDirty(false));
    }
}
