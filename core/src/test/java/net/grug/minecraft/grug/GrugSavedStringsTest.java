package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the text a grug block entity keeps in its saved data. */
class GrugSavedStringsTest {

    /** Stands in for a loader's grug block entity, counting how often it was marked changed. */
    private static final class Engine implements GrugSavedStrings.Holder {
        int changes = 0;

        @Override
        public void markSavedNumbersChanged() {
            changes++;
        }
    }

    @Test
    void aBlockEntityThatIsNotAHolderSavesNothingAndReadsNull() {
        Object chest = new Object();

        assertFalse(GrugSavedStrings.isHolder(chest));
        assertFalse(GrugSavedStrings.set(chest, "liquid", "water"));
        assertNull(GrugSavedStrings.get(chest, "liquid"));
        assertTrue(GrugSavedStrings.snapshot(chest).isEmpty());
    }

    @Test
    void savingEqualTextMarksNothingAndIsStoredAllTheSame() {
        Engine engine = new Engine();
        GrugSavedStrings.set(engine, "liquid", "water");
        int saved = engine.changes;

        assertTrue(GrugSavedStrings.set(engine, "liquid", "water"));

        assertEquals(saved, engine.changes, "the same text again is not a change");
        assertEquals("water", GrugSavedStrings.get(engine, "liquid"));

        GrugSavedStrings.set(engine, "liquid", "lava");
        assertEquals(saved + 1, engine.changes, "different text is a change");
        assertEquals("lava", GrugSavedStrings.get(engine, "liquid"));
    }

    @Test
    void anAbsentNameReadsNullSoSavingNullMarksNothing() {
        Engine engine = new Engine();

        assertTrue(GrugSavedStrings.set(engine, "liquid", null));

        assertEquals(0, engine.changes, "null into a never-written name is what it read back");
        assertNull(GrugSavedStrings.get(engine, "liquid"));

        GrugSavedStrings.set(engine, "liquid", null);
        assertEquals(0, engine.changes, "no text twice is not a change");
    }

    @Test
    void droppingAndRestoringTextMarksOnceEachWay() {
        Engine engine = new Engine();
        GrugSavedStrings.set(engine, "liquid", "water");
        int saved = engine.changes;

        GrugSavedStrings.set(engine, "liquid", null);
        assertEquals(saved + 1, engine.changes, "dropping the text is a change");
        assertNull(GrugSavedStrings.get(engine, "liquid"));

        GrugSavedStrings.set(engine, "liquid", "water");
        assertEquals(saved + 2, engine.changes, "giving the text back is a change");
        assertEquals("water", GrugSavedStrings.get(engine, "liquid"));
    }
}
