package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link GrugSide}'s prose, because it reaches the player: a refusal that says "on
 * DEDICATED_SERVER" names the constant rather than the situation.
 */
class GrugSideTest {

    @Test
    void aSideNamesItselfTheWayASentenceWantsIt() {
        assertEquals("a client", GrugSide.CLIENT.description());
        assertEquals("a dedicated server", GrugSide.DEDICATED_SERVER.description());
    }

    @Test
    void onlyTheDedicatedServerIsOne() {
        org.junit.jupiter.api.Assertions.assertTrue(GrugSide.DEDICATED_SERVER.isDedicatedServer());
        org.junit.jupiter.api.Assertions.assertFalse(GrugSide.CLIENT.isDedicatedServer());
    }
}
