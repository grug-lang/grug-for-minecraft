package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Covers the ordinal lookup the native bridge uses to hand entity types across JNI. */
class GrugEntityTypeTest {

    @Test
    void getReturnsTheTypeAtThatOrdinal() {
        GrugEntityType[] values = GrugEntityType.values();
        for (int i = 0; i < values.length; i++) {
            assertSame(values[i], GrugEntityType.get(i));
        }
    }

    @Test
    void getMatchesTheFirstAndLastTypes() {
        assertEquals(GrugEntityType.Block, GrugEntityType.get(0));
        assertEquals(
                GrugEntityType.values()[GrugEntityType.values().length - 1],
                GrugEntityType.get(GrugEntityType.values().length - 1));
    }
}
