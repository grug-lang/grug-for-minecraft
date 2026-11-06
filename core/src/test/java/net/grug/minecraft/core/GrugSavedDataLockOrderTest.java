package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;

import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;

import org.junit.jupiter.api.Test;

/**
 * Covers the lock order between a saved-data write and the send its mark triggers.
 *
 * <p>{@link GrugSavedDataSync#changed} holds its own monitor and then snapshots the block entity's
 * numbers and lists. A write's mark calls back into it, so the write must release its data's
 * monitor before marking: otherwise one thread can hold a list's monitor and wait for the sender's
 * while another holds the sender's and waits for the list's, and the two deadlock. Nothing reaches
 * that today, because script writes are one thread per side and the render only reads, but the
 * order is kept here so it cannot change unnoticed. The test observes the monitor directly instead
 * of racing two threads, so a regression fails every run and leaves no blocked thread behind.
 */
class GrugSavedDataLockOrderTest {

    /** A block entity that records which of the saved-data monitors its mark still held. */
    private static final class Holder implements GrugSavedNumbers.Holder, GrugSavedLists.Holder {
        private boolean heldTheNumbersMonitor;
        private boolean heldTheListsMonitor;

        @Override
        public void markSavedNumbersChanged() {
            heldTheNumbersMonitor = Thread.holdsLock(GrugSavedNumbers.class);
            heldTheListsMonitor = Thread.holdsLock(GrugSavedLists.class);
        }
    }

    @Test
    void aListWriteMarksTheBlockEntityAfterReleasingItsMonitor() {
        Holder holder = new Holder();
        GrugSavedLists.append(holder, "things");
        assertFalse(
                holder.heldTheListsMonitor,
                "the list's mark ran while the write still held GrugSavedLists' monitor");
    }

    @Test
    void aNumberWriteMarksTheBlockEntityAfterReleasingItsMonitor() {
        Holder holder = new Holder();
        GrugSavedNumbers.set(holder, "marker", 1);
        assertFalse(
                holder.heldTheNumbersMonitor,
                "the number's mark ran while the write still held GrugSavedNumbers' monitor");
    }
}
