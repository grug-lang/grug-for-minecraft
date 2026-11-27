package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Covers the numbers a grug block entity keeps in its saved data. */
class GrugSavedNumbersTest {

    /** Stands in for a loader's grug block entity, counting how often it was marked changed. */
    private static final class Engine implements GrugSavedNumbers.Holder {
        int changes = 0;

        @Override
        public void markSavedNumbersChanged() {
            changes++;
        }
    }

    @Test
    void aBlockEntityThatIsNotAHolderSavesNothingAndReadsZero() {
        Object chest = new Object();

        assertFalse(GrugSavedNumbers.isHolder(chest));
        assertFalse(GrugSavedNumbers.set(chest, "energy", 3));
        assertEquals(0.0, GrugSavedNumbers.get(chest, "energy"));
        assertTrue(GrugSavedNumbers.snapshot(chest).isEmpty());
    }

    @Test
    void aHolderReadsBackWhatItSavedAndZeroForTheRest() {
        Engine engine = new Engine();

        assertTrue(GrugSavedNumbers.isHolder(engine));
        assertEquals(0.0, GrugSavedNumbers.get(engine, "energy"), "nothing saved yet reads 0");
        assertTrue(GrugSavedNumbers.set(engine, "energy", 251));

        assertEquals(251.0, GrugSavedNumbers.get(engine, "energy"));
        assertEquals(0.0, GrugSavedNumbers.get(engine, "heat"));
    }

    @Test
    void savingANumberMarksTheBlockEntityChanged() {
        Engine engine = new Engine();

        GrugSavedNumbers.set(engine, "energy", 1);
        GrugSavedNumbers.set(engine, "energy", 2);

        assertEquals(2, engine.changes);
    }

    @Test
    void savingAnEqualNumberMarksNothingAndIsStoredAllTheSame() {
        Engine engine = new Engine();
        GrugSavedNumbers.set(engine, "energy", 5);
        int saved = engine.changes;

        assertTrue(GrugSavedNumbers.set(engine, "energy", 5));

        assertEquals(saved, engine.changes, "the same number again is not a change");
        assertEquals(5.0, GrugSavedNumbers.get(engine, "energy"));

        GrugSavedNumbers.set(engine, "energy", 6);
        assertEquals(saved + 1, engine.changes, "a different number is a change");
        assertEquals(6.0, GrugSavedNumbers.get(engine, "energy"));
    }

    @Test
    void aNameNeverWrittenReadsZeroSoSavingZeroMarksNothing() {
        Engine engine = new Engine();

        assertTrue(GrugSavedNumbers.set(engine, "energy", 0));

        assertEquals(0, engine.changes, "zero into a never-written name is what it read back");
        assertEquals(0.0, GrugSavedNumbers.get(engine, "energy"));

        GrugSavedNumbers.set(engine, "energy", -0.0);
        assertEquals(1, engine.changes, "0.0 and -0.0 are different text");
        assertEquals(-0.0, GrugSavedNumbers.get(engine, "energy"));

        GrugSavedNumbers.set(engine, "nan", Double.NaN);
        GrugSavedNumbers.set(engine, "nan", Double.NaN);
        assertEquals(2, engine.changes, "every NaN reads the same text");
    }

    @Test
    void aSnapshotIsSortedByNameAndIsACopy() {
        Engine engine = new Engine();
        GrugSavedNumbers.set(engine, "b", 2);
        GrugSavedNumbers.set(engine, "a", 1);

        Map<String, Double> snapshot = GrugSavedNumbers.snapshot(engine);
        snapshot.put("c", 3.0);

        assertEquals(List.of("a", "b", "c"), List.copyOf(snapshot.keySet()));
        assertEquals(0.0, GrugSavedNumbers.get(engine, "c"));
    }

    @Test
    void loadingReplacesEveryNumberWithACopyOfTheSavedOnes() {
        Engine engine = new Engine();
        GrugSavedNumbers.set(engine, "stale", 1);
        Map<String, Double> saved = new HashMap<>();
        saved.put("energy", 500.0);

        GrugSavedNumbers.replace(engine, saved);
        saved.put("energy", 1.0);

        assertEquals(0.0, GrugSavedNumbers.get(engine, "stale"));
        assertEquals(500.0, GrugSavedNumbers.get(engine, "energy"));
    }

    @Test
    void onlyAWholeNonNegativeTickCountIsAccepted() {
        assertEquals(null, GrugTestState.checkTickCount(0));
        assertEquals(null, GrugTestState.checkTickCount(5020));
        assertEquals(null, GrugTestState.checkTickCount(Integer.MAX_VALUE));
        assertTrue(GrugTestState.checkTickCount(Integer.MAX_VALUE + 1.0).contains("2.147483648E9"));
        assertTrue(GrugTestState.checkTickCount(-1).contains("-1.0"));
        assertTrue(GrugTestState.checkTickCount(2.5).contains("2.5"));
        assertTrue(GrugTestState.checkTickCount(Double.NaN).contains("NaN"));
        assertTrue(GrugTestState.checkTickCount(Double.POSITIVE_INFINITY).contains("Infinity"));
    }
}
