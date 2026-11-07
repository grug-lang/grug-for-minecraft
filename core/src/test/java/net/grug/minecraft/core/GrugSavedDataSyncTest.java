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
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Covers the decision to send a block entity's saved data to the client's copy, which every loader
 * makes the same way and only a running game can carry out.
 */
class GrugSavedDataSyncTest {

    /** Stands in for a loader's grug block entity. */
    private static final class Machine implements GrugSavedNumbers.Holder, GrugSavedLists.Holder {
        /** The level this copy stands in, for a transport that scopes its record. */
        Object level = null;

        /** Whether this copy is the simulated one, when the recorder asks per block entity. */
        boolean serverSide = true;

        @Override
        public void markSavedNumbersChanged() {}
    }

    /** A loader's side, remembering what it was asked about and what it sent. */
    private static final class Recorder implements GrugSavedDataSync.Transport {
        boolean onServer = true;

        /** Whether the block's shape is in its chunk's mesh, as a pipe's is. */
        boolean drawsChunkGeometry = false;

        /** Whether its script draws per frame as well, as a pipe's does. */
        boolean drawsFrame = true;

        /** Whether it answers {@link Transport#defersSends} true, as the a1 server does. */
        boolean defers = false;

        /**
         * Whether the block entity answers which side and level it is, which is what one process
         * holding both an integrated server's copy and a client's needs.
         */
        boolean perEntity = false;

        /** The level every block entity stands in, unless {@link #perEntity} says otherwise. */
        Object level = new Object();

        /** Whether this side names a level at all. */
        boolean namesLevel = true;

        int sends = 0;
        int redraws = 0;
        Object lastSent = null;
        Object lastRedrawn = null;
        List<Object> sent = new ArrayList<>();

        @Override
        public boolean onServer(Object blockEntity) {
            return perEntity ? ((Machine) blockEntity).serverSide : onServer;
        }

        @Override
        public boolean drawsFrame(Object blockEntity) {
            return drawsFrame;
        }

        @Override
        public boolean defersSends() {
            return defers;
        }

        @Override
        public Object levelOf(Object blockEntity) {
            if (perEntity) {
                return ((Machine) blockEntity).level;
            }
            return namesLevel ? level : null;
        }

        /**
         * The answer the rule this replaced gave for the same block, which is what tells a
         * both-kind block's test apart from one that would pass either way: only a block that drew
         * per frame alone was sent, so a both-kind block was dropped.
         */
        boolean frameOnly() {
            return drawsFrame && !drawsChunkGeometry;
        }

        @Override
        public void send(Object blockEntity) {
            sends++;
            lastSent = blockEntity;
            sent.add(blockEntity);
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
        // A deferring transport's burst belongs to the test that made it, not to the next one.
        GrugSavedDataSync.flush();
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
    void aClientCopyAndABlockEntityThatDrawsNoFrameAreNotSentOnAChange() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();
        GrugSavedNumbers.set(machine, "marker", 1);

        recorder.onServer = false;
        GrugSavedDataSync.changed(machine);
        assertEquals(0, recorder.sends, "a client's copy is the one that is written to");

        recorder.onServer = true;
        recorder.drawsFrame = false;
        recorder.drawsChunkGeometry = false;
        GrugSavedDataSync.changed(machine);
        assertEquals(0, recorder.sends, "nothing of this block entity is drawn between frames");
    }

    @Test
    void aBothKindBlockSendsItsChangeOnceAndNeverRebuildsItsMesh() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        // The wooden pipe's shape: its geometry is in the chunk's mesh and its contents are drawn
        // per frame, so the rule this replaced dropped its change (#1162). This fails if `changed`
        // goes back to asking that rule, and the send assertions fail if a loader answers
        // `drawsFrame` the old way.
        recorder.drawsFrame = true;
        recorder.drawsChunkGeometry = true;
        assertFalse(recorder.frameOnly(), "the old rule dropped a both-kind block's change");

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);

        assertEquals(1, recorder.sends, "the frame pass reads the number from the client's copy");
        assertEquals(0, recorder.redraws, "a change send must not rebuild the block's mesh");

        GrugSavedDataSync.changed(machine);
        assertEquals(1, recorder.sends, "the client already holds that data");
        assertEquals(0, recorder.redraws);
    }

    @Test
    void nothingIsRedrawnBeforeALoaderInstallsItsSide() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedDataSync.install(null);
        GrugSavedDataSync.redrawn(machine);

        assertEquals(
                0,
                recorder.redraws,
                "installing no side detaches the last one, so nothing was asked to redraw");
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
    void aDeferringSideSendsOnePacketForABurstOfChanges() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        GrugSavedDataSync.install(recorder);
        Machine pipe = new Machine();

        // A pipe's tick moves its travelling item, which saves nine fields one after another, and a
        // test may tick the level a thousand times inside one of its own (#780). A send per field
        // per tick is what overran the a1 connection's queue and dropped the player (#1191).
        for (int tick = 0; tick < 1000; tick++) {
            GrugSavedNumbers.set(pipe, "x", tick);
            GrugSavedDataSync.changed(pipe);
        }

        assertEquals(0, recorder.sends, "a deferring side holds the burst until it is flushed");

        GrugSavedDataSync.flush();

        assertEquals(1, recorder.sends, "the burst is one packet, carrying where it ended");
        assertEquals(pipe, recorder.lastSent);

        GrugSavedDataSync.flush();
        assertEquals(1, recorder.sends, "a flush with nothing held sends nothing");
    }

    @Test
    void aFlushSendsEachBlockEntityOfABurstOnce() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        GrugSavedDataSync.install(recorder);
        Machine pipe = new Machine();
        Machine engine = new Machine();

        GrugSavedNumbers.set(pipe, "x", 1);
        GrugSavedDataSync.changed(pipe);
        GrugSavedNumbers.set(engine, "energy", 1);
        GrugSavedDataSync.changed(engine);
        GrugSavedNumbers.set(pipe, "x", 2);
        GrugSavedDataSync.changed(pipe);

        GrugSavedDataSync.flush();

        assertEquals(2, recorder.sends, "one packet per block entity, however often it changed");
    }

    @Test
    void aRedrawReplacesAChangeTheSideHeld() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);
        GrugSavedDataSync.redrawn(machine);
        GrugSavedDataSync.flush();

        assertEquals(1, recorder.redraws, "a redraw is not held: the client rebuilds at once");
        assertEquals(
                0,
                recorder.sends,
                "the redraw packet carries the data, so a held change would only repeat it");
    }

    @Test
    void aHeldChangeIsForgottenWhenTheSideIsTakenAway() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);
        GrugSavedDataSync.install(null);
        GrugSavedDataSync.flush();

        GrugSavedDataSync.install(recorder);
        GrugSavedDataSync.flush();

        assertEquals(0, recorder.sends, "no side was installed when the held change was flushed");
    }

    @Test
    void aFlushSendsAndDropsOnlyTheLevelItNames() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        recorder.perEntity = true;
        GrugSavedDataSync.install(recorder);
        Object serverLevel = new Object();
        Object otherLevel = new Object();
        Machine inServerLevel = new Machine();
        inServerLevel.level = serverLevel;
        Machine inOtherLevel = new Machine();
        inOtherLevel.level = otherLevel;

        GrugSavedNumbers.set(inServerLevel, "x", 1);
        GrugSavedDataSync.changed(inServerLevel);
        GrugSavedNumbers.set(inOtherLevel, "x", 1);
        GrugSavedDataSync.changed(inOtherLevel);

        GrugSavedDataSync.flush(serverLevel);
        assertEquals(
                List.of(inServerLevel),
                recorder.sent,
                "a flush of one level sends that level's change and no other's");

        GrugSavedDataSync.flush(serverLevel);
        assertEquals(1, recorder.sends, "that level held nothing more");

        GrugSavedDataSync.flush(otherLevel);
        assertEquals(
                List.of(inServerLevel, inOtherLevel),
                recorder.sent,
                "the other level's change was still held, not dropped by the first flush");
    }

    @Test
    void oneProcessHoldsTheServerCopyAndNeverTheClientCopy() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        recorder.perEntity = true;
        GrugSavedDataSync.install(recorder);
        Object level = new Object();
        Machine serverCopy = new Machine();
        serverCopy.level = level;
        Machine clientCopy = new Machine();
        clientCopy.level = level;
        clientCopy.serverSide = false;

        GrugSavedNumbers.set(serverCopy, "x", 1);
        GrugSavedDataSync.changed(serverCopy);
        GrugSavedNumbers.set(clientCopy, "x", 1);
        GrugSavedDataSync.changed(clientCopy);

        assertEquals(0, recorder.sends, "the simulated copy is held until the flush");

        GrugSavedDataSync.flush(level);
        assertEquals(
                List.of(serverCopy),
                recorder.sent,
                "the client's own copy is written to already, so only the server's is sent");
    }

    @Test
    void aNewSideIsNeverHandedWhatTheOldSideHeld() {
        Recorder server = new Recorder();
        server.defers = true;
        server.perEntity = true;
        GrugSavedDataSync.install(server);
        Object level = new Object();
        Machine copy = new Machine();
        copy.level = level;

        GrugSavedNumbers.set(copy, "x", 1);
        GrugSavedDataSync.changed(copy);

        Recorder client = new Recorder();
        client.perEntity = true;
        GrugSavedDataSync.install(client);
        GrugSavedDataSync.flush(level);

        assertEquals(0, client.sends, "the new side is never handed the old side's change");
        assertEquals(0, server.sends, "the old side's change is forgotten with it");
    }

    @Test
    void reinstallingTheSameSideKeepsWhatItHeld() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        recorder.perEntity = true;
        GrugSavedDataSync.install(recorder);
        Object level = new Object();
        Machine machine = new Machine();
        machine.level = level;

        GrugSavedNumbers.set(machine, "x", 1);
        GrugSavedDataSync.changed(machine);
        GrugSavedDataSync.install(recorder);
        GrugSavedDataSync.flush(level);

        assertEquals(1, recorder.sends, "the same side holds the same changes");
    }

    @Test
    void aDeferringSideThatNamesNoLevelSendsAtOnce() {
        Recorder recorder = new Recorder();
        recorder.defers = true;
        recorder.namesLevel = false;
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);

        assertEquals(1, recorder.sends, "a change no level could flush is not held forever");
    }

    @Test
    void aSideThatSendsAtOnceHoldsNothingToFlush() {
        Recorder recorder = new Recorder();
        GrugSavedDataSync.install(recorder);
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);
        GrugSavedDataSync.flush();

        assertEquals(1, recorder.sends, "the loaders that answer defersSends false are unchanged");
    }

    @Test
    void aSideThatSaysNothingAboutDeferralSendsAtOnce() {
        List<Object> sent = new ArrayList<>();
        GrugSavedDataSync.install(
                new GrugSavedDataSync.Transport() {
                    @Override
                    public boolean onServer(Object blockEntity) {
                        return true;
                    }

                    @Override
                    public boolean drawsFrame(Object blockEntity) {
                        return true;
                    }

                    @Override
                    public void send(Object blockEntity) {
                        sent.add(blockEntity);
                    }

                    @Override
                    public void redraw(Object blockEntity) {}
                });
        Machine machine = new Machine();

        GrugSavedNumbers.set(machine, "marker", 1);
        GrugSavedDataSync.changed(machine);

        assertEquals(
                List.of(machine),
                sent,
                "a loader that does not ask to defer keeps sending on every change");
    }

    @Test
    void aClientLevelRunsItsOwnRebuild() {
        AtomicBoolean ran = new AtomicBoolean(false);
        GrugSavedDataSync.onClientLevel(true, () -> ran.set(true));
        assertTrue(ran.get(), "a client's copy of the block entity marks its own level");
    }

    @Test
    void aServerLevelLeavesTheRebuildToItsTransport() {
        AtomicBoolean ran = new AtomicBoolean(false);
        GrugSavedDataSync.onClientLevel(false, () -> ran.set(true));
        assertFalse(ran.get(), "a server's transport sends the data and its client rebuilds there");
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
