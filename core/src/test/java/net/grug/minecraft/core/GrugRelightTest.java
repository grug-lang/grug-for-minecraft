package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

class GrugRelightTest {
    @Test
    void aSweepThatChangesNothingIsConfirmedByASecondOne() {
        AtomicInteger calls = new AtomicInteger();

        int sweeps = GrugRelight.sweepUntilStable(() -> calls.incrementAndGet() * 0 + 7, 5);

        assertEquals(2, sweeps);
        assertEquals(2, calls.get());
    }

    @Test
    void sweepsContinueWhileTheLightKeepsChanging() {
        long[] hashes = {1, 2, 3, 3};
        AtomicInteger next = new AtomicInteger();

        int sweeps = GrugRelight.sweepUntilStable(() -> hashes[next.getAndIncrement()], 5);

        assertEquals(4, sweeps);
        assertEquals(4, next.get());
    }

    @Test
    void lightThatNeverSettlesFailsInsteadOfLoopingForever() {
        AtomicInteger next = new AtomicInteger();

        IllegalStateException failure =
                assertThrows(
                        IllegalStateException.class,
                        () -> GrugRelight.sweepUntilStable(() -> next.incrementAndGet(), 4));

        assertEquals(
                "Broken grug invariant: the room's light did not settle in 4 sweeps of recomputing"
                        + " it",
                failure.getMessage());
        assertEquals(4, next.get());
    }

    @Test
    void aLimitOfTwoAllowsExactlyOneConfirmation() {
        long[] hashes = {5, 5};
        AtomicInteger next = new AtomicInteger();

        assertEquals(2, GrugRelight.sweepUntilStable(() -> hashes[next.getAndIncrement()], 2));
    }

    @Test
    void aRoomWhoseSkyWalksToItsEnclosedValueOverThreeSweepsIsSweptForIt() {
        // The room was put up where the sky was open, so its sky has to walk to the enclosed 0 over
        // three sweeps while its block light already holds the field its blocks define. A hash of
        // the block field alone calls that settled after the second sweep, with the sky still at 4,
        // and a sweep that never queues the sky leaves it at 15 for any number of them (#1368).
        EnclosedRoom room = new EnclosedRoom(9, 8, 4, 0);

        int sweeps = GrugRelight.settle(room, 0, 0, 0, 0, 0, 0, 8);

        assertEquals(4, sweeps);
        assertEquals(0, room.sky(0, 0, 0));
        assertEquals(sweeps, room.skyQueues);
        assertEquals(sweeps, room.blockQueues);
    }

    @Test
    void aBoxTooLargeForOneUpdateIsQueuedInPiecesEachUpdateAccepts() {
        // A radius 13 room's box is 29 x 29 x 42, which is 35322 cells against the 32768 one update
        // may cover. Beta's engine prints "Light too large, skipping!" and recomputes nothing for
        // the whole box, so a settle that queues it as one leaves the room at the light it was
        // built over (#1368).
        RecordingRoom room = new RecordingRoom();

        int sweeps = GrugRelight.settle(room, 0, 0, 0, 28, 41, 28, 4);

        assertEquals(2, sweeps);
        assertEquals(sweeps, room.boxesPerSweep.size());
        for (List<int[]> boxes : room.boxesPerSweep) {
            long covered = 0;
            for (int at = 0; at < boxes.size(); at++) {
                int[] box = boxes.get(at);
                assertTrue(
                        cells(box) <= GrugRelight.MAX_UPDATE_CELLS,
                        "one update should cover at most " + GrugRelight.MAX_UPDATE_CELLS);
                assertTrue(inside(box), "a piece should stay inside the box it was cut from");
                covered = covered + cells(box);
                for (int other = at + 1; other < boxes.size(); other++) {
                    assertFalse(
                            overlaps(box, boxes.get(other)), "two pieces should not share a cell");
                }
            }
            assertEquals(35322, covered, "the pieces should cover the box exactly once");
        }
        assertEquals(room.skyQueues, room.blockQueues, "both fields should be queued in pieces");
    }

    /** How many cells a box covers, as GrugRelight counts them. */
    private static long cells(int[] box) {
        return (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
    }

    /** Whether a box is inside the box a radius 13 room settles. */
    private static boolean inside(int[] box) {
        return box[0] >= 0
                && box[1] >= 0
                && box[2] >= 0
                && box[3] <= 28
                && box[4] <= 41
                && box[5] <= 28;
    }

    /** Whether two boxes share at least one cell. */
    private static boolean overlaps(int[] a, int[] b) {
        return a[0] <= b[3]
                && b[0] <= a[3]
                && a[1] <= b[4]
                && b[1] <= a[4]
                && a[2] <= b[5]
                && b[2] <= a[5];
    }

    /** A queued engine that recomputes only the field an update asked it for. */
    private static final class EnclosedRoom implements GrugRelight.Room {
        private final int blockLight;
        private final int[] skyPerSweep;
        private int sweepsRun;
        private int pendingSky;
        private int skyLight = 15;
        int skyQueues;
        int blockQueues;

        EnclosedRoom(int blockLight, int... skyPerSweep) {
            this.blockLight = blockLight;
            this.skyPerSweep = skyPerSweep;
        }

        @Override
        public void queueSky(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.pendingSky++;
            this.skyQueues++;
        }

        @Override
        public void queueBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.blockQueues++;
        }

        @Override
        public void drain() {
            if (this.pendingSky > 0) {
                this.skyLight =
                        this.skyPerSweep[Math.min(this.sweepsRun, this.skyPerSweep.length - 1)];
            }
            this.pendingSky = 0;
            this.sweepsRun++;
        }

        @Override
        public int sky(int x, int y, int z) {
            return this.skyLight;
        }

        @Override
        public int block(int x, int y, int z) {
            return this.blockLight;
        }
    }

    /**
     * A queued engine whose light never changes, recording the boxes one sweep queues for each
     * field, so a test can check that none of them is larger than the engine accepts.
     */
    private static final class RecordingRoom implements GrugRelight.Room {
        private final List<List<int[]>> boxesPerSweep = new ArrayList<>();
        private List<int[]> skyBoxes;
        int skyQueues;
        int blockQueues;

        @Override
        public void queueSky(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (this.skyBoxes == null) {
                this.skyBoxes = new ArrayList<>();
                this.boxesPerSweep.add(this.skyBoxes);
            }
            this.skyBoxes.add(new int[] {minX, minY, minZ, maxX, maxY, maxZ});
            this.skyQueues++;
        }

        @Override
        public void queueBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this.blockQueues++;
        }

        @Override
        public void drain() {
            this.skyBoxes = null;
        }

        @Override
        public int sky(int x, int y, int z) {
            return 0;
        }

        @Override
        public int block(int x, int y, int z) {
            return 0;
        }
    }
}
