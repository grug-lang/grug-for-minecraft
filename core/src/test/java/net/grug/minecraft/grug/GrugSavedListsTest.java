package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the lists a grug block entity keeps in its saved data. */
class GrugSavedListsTest {

    /** Stands in for a loader's grug block entity, counting how often it was marked changed. */
    private static final class Machine implements GrugSavedLists.Holder {
        int changes = 0;

        @Override
        public void markSavedNumbersChanged() {
            changes++;
        }
    }

    @Test
    void aBlockEntityThatIsNotAHolderIsNotOne() {
        assertTrue(!GrugSavedLists.isHolder(new Object()));
        assertTrue(GrugSavedLists.isHolder(new Machine()));
    }

    @Test
    void savingAnEqualNumberStoresItAndMarksNothing() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setNumber(machine, stored, "progress", 2.5);
        assertEquals(appended + 1, machine.changes, "a new key is a change");

        GrugSavedLists.setNumber(machine, stored, "progress", 2.5);

        assertEquals(appended + 1, machine.changes, "the same number again is not a change");
        assertEquals(2.5, stored.numbers().get("progress"));
    }

    @Test
    void aChangedNumberMarksOnce() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setNumber(machine, stored, "progress", 2.5);
        GrugSavedLists.setNumber(machine, stored, "progress", 3.5);

        assertEquals(appended + 2, machine.changes);
        assertEquals(3.5, stored.numbers().get("progress"));
    }

    @Test
    void numbersAreComparedTheWayTheSnapshotWritesThem() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setNumber(machine, stored, "zero", 0.0);
        GrugSavedLists.setNumber(machine, stored, "zero", -0.0);
        assertEquals(appended + 2, machine.changes, "0.0 and -0.0 are different text");

        GrugSavedLists.setNumber(machine, stored, "nan", Double.NaN);
        GrugSavedLists.setNumber(machine, stored, "nan", Double.NaN);
        assertEquals(appended + 3, machine.changes, "every NaN reads the same text");
    }

    @Test
    void savingAnEqualItemStoresItAndMarksNothing() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setItem(machine, stored, "minecraft:stone");
        assertEquals(appended + 1, machine.changes, "a record with no item gained one");

        GrugSavedLists.setItem(machine, stored, "minecraft:stone");
        assertEquals(appended + 1, machine.changes, "the same item again is not a change");
        assertEquals("minecraft:stone", stored.item());

        // A loader that read the record back may hand a new object for the same item, which the
        // canonical snapshot compares by its text. The write is still stored.
        Object readBack = new String("minecraft:stone");
        GrugSavedLists.setItem(machine, stored, readBack);
        assertEquals(appended + 1, machine.changes, "an equal item under another object is equal");
        assertSame(readBack, stored.item(), "an equal write is stored all the same");
    }

    @Test
    void aChangedItemOrAnAbsentOneMarksOnce() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setItem(machine, stored, "minecraft:stone");
        GrugSavedLists.setItem(machine, stored, "minecraft:dirt");
        assertEquals(appended + 2, machine.changes);
        assertEquals("minecraft:dirt", stored.item());

        GrugSavedLists.setItem(machine, stored, null);
        assertEquals(appended + 3, machine.changes, "dropping the item is a change");
        assertNull(stored.item());

        GrugSavedLists.setItem(machine, stored, null);
        assertEquals(appended + 3, machine.changes, "no item twice is not a change");
    }

    @Test
    void savingEqualTextStoresItAndMarksNothing() {
        Machine machine = new Machine();
        int record = GrugSavedLists.append(machine, "items");
        GrugSavedLists.Record stored = GrugSavedLists.record(machine, "items", record);
        int appended = machine.changes;

        GrugSavedLists.setText(machine, stored, "north");
        assertEquals(appended + 1, machine.changes, "a record with no text gained some");

        GrugSavedLists.setText(machine, stored, "north");
        assertEquals(appended + 1, machine.changes, "the same text again is not a change");
        assertEquals("north", stored.text());

        GrugSavedLists.setText(machine, stored, "south");
        assertEquals(appended + 2, machine.changes);
        assertEquals("south", stored.text());

        GrugSavedLists.setText(machine, stored, null);
        assertEquals(appended + 3, machine.changes, "dropping the text is a change");
        assertNull(stored.text());

        GrugSavedLists.setText(machine, stored, null);
        assertEquals(appended + 3, machine.changes, "no text twice is not a change");
    }
}
