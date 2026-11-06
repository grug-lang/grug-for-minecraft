package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.grug.minecraft.grug.GrugPower;
import net.grug.minecraft.grug.GrugSavedLists;
import net.grug.minecraft.grug.GrugSavedNumbers;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Covers the decision to send a block entity's saved data to the client's copy, which every loader
 * makes the same way and only a running game can carry out.
 */
class GrugSavedDataSyncTest {

    /** Stands in for a loader's grug block entity. */
    private static final class Machine implements GrugSavedNumbers.Holder, GrugSavedLists.Holder {
        @Override
        public void markSavedNumbersChanged() {}
    }

    /** A loader's side, remembering what it was asked about and what it sent. */
    private static final class Recorder implements GrugSavedDataSync.Transport {
        boolean onServer = true;
        boolean frameOnly = true;
        int sends = 0;
        int redraws = 0;
        Object lastSent = null;
        Object lastRedrawn = null;

        @Override
        public boolean onServer(Object blockEntity) {
            return onServer;
        }

        @Override
        public boolean frameOnly(Object blockEntity) {
            return frameOnly;
        }

        @Override
        public void send(Object blockEntity) {
            sends++;
            lastSent = blockEntity;
        }

        @Override
        public void redraw(Object blockEntity) {
            redraws++;
            lastRedrawn = blockEntity;
        }
    }

    @AfterEach
    void uninstall() {
        GrugSavedDataSync.install(null);
    }

    @Test
    void aDedicatedServerNeverAppliesSavedData() {
        assertTrue(
                GrugSavedDataSync.appliesOn(GrugSide.CLIENT),
                "the client holds the copy the frame pass reads, so it applies what the server"
                        + " sent");
        assertFalse(
                GrugSavedDataSync.appliesOn(GrugSide.DEDICATED_SERVER),
                "a server that applied a packet would write a client's copy into its own world");
    }

    @Test
    void nothingIsSentBeforeALoaderInstallsItsSide() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        GrugSavedDataSync.install(null);
        Machine machine = new Machine();
        GrugSavedNumbers.set(machine, "marker", 1);

        GrugSavedDataSync.changed(machine);

        assertEquals(0, recorder.sends, "no side is installed yet");
    }

    @Test
    void aChangedNumberIsSentOnceAndTheSameNumberAgainIsNot() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);

        assertEquals(1, recorder.sends);
        assertEquals(machine, recorder.lastSent, "the send is about the block entity that changed");

        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends, "the client already holds that data");

        GrugSavedNumbers.set(machine, "marker", 2);
        GrugSavedDataSync.changed(machine);
        assertEquals(2, recorder.sends, "a different number is a different frame");
    }

    @Test
    void aChangedListIsSentAndTheSameListAgainIsNot() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        int record = GrugSavedLists.append(machine, "items");
        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends);

        GrugSavedLists.setNumber(
                machine, GrugSavedLists.record(machine, "items", record), "progress", 0.5);
        GrugSavedDataSync.changed(machine);
        assertEquals(2, recorder.sends);

        GrugSavedDataSync.changed(machine);
        assertEquals(2, recorder.sends);

        GrugSavedLists.setItem(machine, GrugSavedLists.record(machine, "items", record), "stone");
        GrugSavedDataSync.changed(machine);
        assertEquals(3, recorder.sends, "a record's item is part of what is drawn");
    }

    @Test
    void aChangedEnergyStoreIsSent() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugPower.load(machine, 10);
        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends);

        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends);

        GrugPower.load(machine, 20);
        GrugSavedDataSync.changed(machine);
        assertEquals(2, recorder.sends, "a store that moved again is a new frame");
    }

    @Test
    void aReorderedListIsSent() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedLists.append(machine, "items");
        GrugSavedLists.append(machine, "items");
        GrugSavedLists.setNumber(machine, GrugSavedLists.record(machine, "items", 0), "slot", 1);
        GrugSavedLists.setNumber(machine, GrugSavedLists.record(machine, "items", 1), "slot", 2);
        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends);

        Map<String, List<GrugSavedLists.Record>> reordered = new TreeMap<>();
        List<GrugSavedLists.Record> records =
                new ArrayList<>(GrugSavedLists.snapshot(machine).get("items"));
        Collections.reverse(records);
        reordered.put("items", records);
        GrugSavedLists.replace(machine, reordered);

        GrugSavedDataSync.changed(machine);
        assertEquals(2, recorder.sends, "the order of a list is part of what the frame pass draws");
    }

    @Test
    void aRedrawSendsTheBlockEntityToTheClientThatRebuildsItsMesh() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedDataSync.redrawn(machine);
        assertEquals(1, recorder.redraws, "the client is told to build the mesh again");
        assertEquals(machine, recorder.lastRedrawn);
    }

    @Test
    void aClientCopyAndABlockWithGeometryInItsMeshAreNotSentOnAChange() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();
        GrugSavedNumbers.set(machine, "marker", 1);

        recorder.onServer = false;
        GrugSavedDataSync.changed(machine);
        assertEquals(0, recorder.sends, "a client's copy is the one that is written to");

        recorder.onServer = true;
        recorder.frameOnly = false;
        GrugSavedDataSync.changed(machine);
        assertEquals(0, recorder.sends, "a block in its chunk's mesh is sent by its redraw");
    }

    @Test
    void nothingIsRedrawnBeforeALoaderInstallsItsSide() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(null);
        Machine machine = new Machine();

        GrugSavedDataSync.redrawn(machine);

        assertEquals(0, recorder.redraws, "no side is installed, so nothing was asked to redraw");
    }

    @Test
    void aClientCopyIsNotSentTheRedraw() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        recorder.onServer = false;
        GrugSavedDataSync.redrawn(machine);

        assertEquals(
                0,
                recorder.redraws,
                "a client builds its own mesh again and sends nothing, because the data travels"
                        + " server to client only");
    }

    @Test
    void theSnapshotIsTheSameForTheSameDataAndDiffersForDifferentData() {
        Machine one = new Machine();
        Machine two = new Machine();
        GrugSavedNumbers.set(one, "marker", 1);
        GrugSavedNumbers.set(two, "marker", 1);

        assertEquals(GrugSavedDataSync.snapshot(one), GrugSavedDataSync.snapshot(two));

        GrugSavedNumbers.set(two, "other", 1);
        assertNotEquals(GrugSavedDataSync.snapshot(one), GrugSavedDataSync.snapshot(two));

        Machine three = new Machine();
        Machine four = new Machine();
        GrugSavedLists.append(three, "items");
        GrugSavedLists.append(four, "items");
        assertEquals(
                GrugSavedDataSync.snapshot(three),
                GrugSavedDataSync.snapshot(four),
                "two empty records hold the same data");
    }
}
