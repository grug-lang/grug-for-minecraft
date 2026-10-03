package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/** Covers the fallback a loader asks for when it cannot resolve a vanilla block name. */
class GrugBlockNamesTest {

    @Test
    void offersTheOtherSpellingOfTheLitTorch() {
        assertEquals("redstone_torch", GrugBlockNames.otherSpelling("redstone_torch_lit"));
    }

    @Test
    void hasNoAlternativeForANameTheLoadersAgreeOn() {
        assertNull(GrugBlockNames.otherSpelling("redstone_torch"));
        assertNull(GrugBlockNames.otherSpelling("stone"));
        assertNull(GrugBlockNames.otherSpelling("grug:does_not_exist"));
        assertNull(GrugBlockNames.otherSpelling(""));
    }

    @Test
    void holdsOnlyTheNamesAnAuditFoundToDiffer() {
        assertEquals(1, GrugBlockNames.otherSpellings().size());
    }

    @Test
    void handsOutTheTableReadOnly() {
        try {
            GrugBlockNames.otherSpellings().put("stone", "dirt");
        } catch (UnsupportedOperationException expected) {
            // A loader cannot be handed a table entry to bend the naming around it.
            return;
        }
        assertEquals("stone", GrugBlockNames.otherSpelling("stone"));
    }
}
