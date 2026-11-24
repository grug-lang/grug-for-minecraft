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
    void aBoxWithNegativeCoordinatesIsSplitIntoPiecesThatTileItExactly() {
        List<int[]> pieces = GrugRelight.pieces(-41, 0, 0, 0, 28, 28);

        assertTrue(pieces.size() > 1);
        long total = 0;
        for (int[] piece : pieces) {
            assertTrue(piece[0] <= piece[3] && piece[1] <= piece[4] && piece[2] <= piece[5]);
            assertTrue(piece[0] >= -41 && piece[3] <= 0);
            total +=
                    (long) (piece[3] - piece[0] + 1)
                            * (piece[4] - piece[1] + 1)
                            * (piece[5] - piece[2] + 1);
        }
        assertEquals(42L * 29 * 29, total);
        for (int i = 0; i < pieces.size(); i++) {
            for (int j = i + 1; j < pieces.size(); j++) {
                int[] a = pieces.get(i);
                int[] b = pieces.get(j);
                boolean overlap =
                        a[0] <= b[3]
                                && b[0] <= a[3]
                                && a[1] <= b[4]
                                && b[1] <= a[4]
                                && a[2] <= b[5]
                                && b[2] <= a[5];
                assertFalse(overlap);
            }
        }
    }

    @Test
    void aBoxNearTheIntegerLimitsIsSplitWithoutOverflow() {
        int low = (1 << 30) + 10;
        List<int[]> pieces = GrugRelight.pieces(low, 0, 0, low + 41, 28, 28);

        assertTrue(pieces.size() > 1);
        long total = 0;
        for (int[] piece : pieces) {
            assertTrue(piece[0] <= piece[3] && piece[0] >= low && piece[3] <= low + 41);
            total +=
                    (long) (piece[3] - piece[0] + 1)
                            * (piece[4] - piece[1] + 1)
                            * (piece[5] - piece[2] + 1);
        }
        assertEquals(42L * 29 * 29, total);
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
        assertPiecesCoverExactlyOnce(room.boxesPerSweep, new int[] {0, 0, 0, 28, 41, 28});
        assertEquals(room.skyQueues, room.blockQueues, "both fields should be queued in pieces");
    }

    @Test
    void aBoxOverTheEnginesLimitReachesBetaAsPiecesItAcceptsAndSettlesBothFields() {
        // What the two Ornithe Beta loaders now do with the box a resend relights, with an engine
        // that behaves as theirs does: an update over 32768 cells is dropped and recomputes
        // nothing. A settle that handed over the radius 13 room's whole box would drop every sweep
        // and confirm the 15 its sky was left at, which the ask fingerprints and the resend then
        // imposes on the joined client.
        BetaRoom room = new BetaRoom(9, 8, 4, 0);

        int sweeps = GrugRelight.settle(room, 0, 0, 0, 28, 41, 28, 8);

        assertEquals(4, sweeps);
        assertEquals(0, room.dropped, "no update may be larger than the engine accepts");
        assertEquals(0, room.sky(0, 0, 0), "the sky should settle at the field its blocks define");
        assertEquals(9, room.block(0, 0, 0));
        assertPiecesCoverExactlyOnce(room.skyBoxesPerSweep, new int[] {0, 0, 0, 28, 41, 28});
        assertPiecesCoverExactlyOnce(room.blockBoxesPerSweep, new int[] {0, 0, 0, 28, 41, 28});
    }

    /** Asserts that every sweep's boxes are accepted ones that cover {@code outer} exactly once. */
    private static void assertPiecesCoverExactlyOnce(List<List<int[]>> boxesPerSweep, int[] outer) {
        assertFalse(boxesPerSweep.isEmpty(), "a sweep should have queued at least one box");
        for (List<int[]> boxes : boxesPerSweep) {
            long covered = 0;
            for (int at = 0; at < boxes.size(); at++) {
                int[] box = boxes.get(at);
                assertTrue(
                        cells(box) <= GrugRelight.MAX_UPDATE_CELLS,
                        "one update should cover at most " + GrugRelight.MAX_UPDATE_CELLS);
                assertTrue(
                        inside(box, outer), "a piece should stay inside the box it was cut from");
                covered = covered + cells(box);
                for (int other = at + 1; other < boxes.size(); other++) {
                    assertFalse(
                            overlaps(box, boxes.get(other)), "two pieces should not share a cell");
                }
            }
            assertEquals(cells(outer), covered, "the pieces should cover the box exactly once");
        }
    }

    @Test
    void aSettleOnAnEngineThatRecomputesAtOnceNeverHandsItABoxItDrops() {
        // Both dedicated servers relight a room through settleImmediately, whose engine (beta's
        // LightUpdate) recomputes a box the moment it is given one and refuses a box over 32768
        // cells ("Light too large, skipping!"). This fake keeps that number as its own oracle
        // rather than reading GrugRelight's, so the test cannot agree with the production constant
        // by construction: a box handed over whole is dropped here and the room stays unlit
        // (#1522).
        DroppingRoom room = new DroppingRoom();

        int sweeps = GrugRelight.settleImmediately(room, 0, 0, 0, 28, 41, 28, 4);

        assertEquals(2, sweeps);
        assertEquals(
                0,
                room.dropped,
                "the engine drops a box over 32768 cells, so none may be that large");
        assertEquals(2, room.boxesPerSweep.size(), "both sweeps should update the box");
        for (List<int[]> boxes : room.boxesPerSweep) {
            long covered = 0;
            for (int at = 0; at < boxes.size(); at++) {
                int[] box = boxes.get(at);
                assertTrue(
                        inside(box, new int[] {0, 0, 0, 28, 41, 28}),
                        "a piece should stay inside the box it was cut from");
                covered = covered + cells(box);
                for (int other = at + 1; other < boxes.size(); other++) {
                    assertFalse(
                            overlaps(box, boxes.get(other)), "two pieces should not share a cell");
                }
            }
            assertEquals(35322, covered, "the pieces should cover the room exactly once");
        }
    }

    @Test
    void aSettleOnAnEngineThatRecomputesAtOnceSweepsTheSkyItsBlocksDefine() {
        // The two Ornithe Beta servers relight the box a resend sends through settleImmediately,
        // and the settle ask fingerprints both of its fields, so an update that recomputed only
        // one of them would leave the resend imposing the 15 the room's sky was built over. The sky
        // walks to its enclosed value over three sweeps while the block light already holds the
        // field its blocks define, so a hash of the block field alone calls that settled after the
        // second sweep, with the sky still at 4.
        DroppingRoom room = new DroppingRoom(8, 4, 0);

        int sweeps = GrugRelight.settleImmediately(room, 0, 0, 0, 28, 41, 28, 8);

        assertEquals(4, sweeps);
        assertEquals(0, room.dropped, "no update may be larger than the engine accepts");
        assertEquals(0, room.sky(0, 0, 0), "the sky should settle at the field its blocks define");
        assertEquals(4, room.boxesPerSweep.size(), "every sweep should update the box");
    }

    @Test
    void aBoxOfExactlyTheEnginesLimitPassesAndOneCellMoreIsCut() {
        // Beta's check is a strict >, so 32768 cells are accepted in one update and 32769 are not.
        assertEquals(1, GrugRelight.pieces(0, 0, 0, 32767, 0, 0).size(), "32768 cells pass");
        assertEquals(2, GrugRelight.pieces(0, 0, 0, 32768, 0, 0).size(), "32769 cells are cut");
    }

    @Test
    void aBoxIsCutAlongItsLongestSide() {
        // The settle case above cuts a room's box, whose longest side is y. These put the longest
        // side on x and on z, and the last one has x longer than y but shorter than z, so every
        // comparison the cut makes is taken both ways.
        int[][] boxes = {
            {0, 0, 0, 40, 28, 28},
            {0, 0, 0, 28, 28, 40},
            {0, 0, 0, 30, 28, 40},
        };

        for (int[] outer : boxes) {
            List<int[]> pieces =
                    GrugRelight.pieces(outer[0], outer[1], outer[2], outer[3], outer[4], outer[5]);
            assertTrue(pieces.size() > 1, "a box over one update should be cut");

            long covered = 0;
            for (int at = 0; at < pieces.size(); at++) {
                int[] piece = pieces.get(at);
                assertTrue(
                        cells(piece) <= GrugRelight.MAX_UPDATE_CELLS,
                        "one update should cover at most " + GrugRelight.MAX_UPDATE_CELLS);
                assertTrue(
                        inside(piece, outer), "a piece should stay inside the box it was cut from");
                covered = covered + cells(piece);
                for (int other = at + 1; other < pieces.size(); other++) {
                    assertFalse(
                            overlaps(piece, pieces.get(other)),
                            "two pieces should not share a cell");
                }
            }
            assertEquals(cells(outer), covered, "the pieces should cover the box exactly once");
        }
    }

    /** How many cells a box covers, as GrugRelight counts them. */
    private static long cells(int[] box) {
        return (long) (box[3] - box[0] + 1) * (box[4] - box[1] + 1) * (box[5] - box[2] + 1);
    }

    /** Whether a box is inside {@code outer}. */
    private static boolean inside(int[] box, int[] outer) {
        return box[0] >= outer[0]
                && box[1] >= outer[1]
                && box[2] >= outer[2]
                && box[3] <= outer[3]
                && box[4] <= outer[4]
                && box[5] <= outer[5];
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
     * A queued engine that behaves as beta's does: an update over {@link
     * GrugRelight#MAX_UPDATE_CELLS} is dropped and recomputes nothing, and an accepted sky update
     * walks the sky light to the next value the room's blocks define. It records the boxes it
     * accepted per sweep, so a test can check that none was dropped and that the pieces tile the
     * box.
     */
    private static final class BetaRoom implements GrugRelight.Room {
        private final int blockLight;
        private final int[] skyPerSweep;
        private int skyLight = 15;
        private boolean skyQueued;
        private int sweepsRun;
        private List<int[]> skyBoxes;
        private List<int[]> blockBoxes;
        final List<List<int[]>> skyBoxesPerSweep = new ArrayList<>();
        final List<List<int[]>> blockBoxesPerSweep = new ArrayList<>();
        int dropped;

        BetaRoom(int blockLight, int... skyPerSweep) {
            this.blockLight = blockLight;
            this.skyPerSweep = skyPerSweep;
        }

        @Override
        public void queueSky(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (accept(minX, minY, minZ, maxX, maxY, maxZ) == null) {
                return;
            }
            if (this.skyBoxes == null) {
                this.skyBoxes = new ArrayList<>();
                this.skyBoxesPerSweep.add(this.skyBoxes);
            }
            this.skyBoxes.add(new int[] {minX, minY, minZ, maxX, maxY, maxZ});
            this.skyQueued = true;
        }

        @Override
        public void queueBlock(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            if (accept(minX, minY, minZ, maxX, maxY, maxZ) == null) {
                return;
            }
            if (this.blockBoxes == null) {
                this.blockBoxes = new ArrayList<>();
                this.blockBoxesPerSweep.add(this.blockBoxes);
            }
            this.blockBoxes.add(new int[] {minX, minY, minZ, maxX, maxY, maxZ});
        }

        /**
         * The box when the engine accepts it, which is the box it covers; null when it drops it, as
         * beta does for an update over the cells one may cover.
         */
        private int[] accept(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            int[] box = {minX, minY, minZ, maxX, maxY, maxZ};
            if (cells(box) > GrugRelight.MAX_UPDATE_CELLS) {
                this.dropped++;
                return null;
            }
            return box;
        }

        @Override
        public void drain() {
            if (this.skyQueued) {
                this.skyLight =
                        this.skyPerSweep[Math.min(this.sweepsRun, this.skyPerSweep.length - 1)];
            }
            this.skyQueued = false;
            this.skyBoxes = null;
            this.blockBoxes = null;
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

    /**
     * An engine that recomputes a box the moment it is given one and refuses a box over 32768 cells
     * the way beta's {@code LightUpdate} does, recording the drop and leaving the room unlit. The
     * limit is the engine's own number rather than GrugRelight's, so the test cannot pass by
     * agreeing with the production constant. Its sky walks the values it was given, one per sweep,
     * as this version's engine recomputes the sky field it was given a box for.
     */
    private static final class DroppingRoom implements GrugRelight.ImmediateRoom {
        private final List<List<int[]>> boxesPerSweep = new ArrayList<>();
        private final int[] skyPerSweep;
        private List<int[]> current;
        private int skyLight;
        private int sweepsRun;
        int dropped;

        DroppingRoom(int... skyPerSweep) {
            this.skyPerSweep = skyPerSweep;
        }

        @Override
        public void update(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            int[] box = {minX, minY, minZ, maxX, maxY, maxZ};
            if (cells(box) > 32768) {
                this.dropped++;
                return;
            }
            if (this.current == null) {
                this.current = new ArrayList<>();
                this.boxesPerSweep.add(this.current);
                if (this.sweepsRun < this.skyPerSweep.length) {
                    this.skyLight = this.skyPerSweep[this.sweepsRun];
                }
                this.sweepsRun++;
            }
            this.current.add(box);
        }

        @Override
        public int sky(int x, int y, int z) {
            return this.skyLight;
        }

        @Override
        public int block(int x, int y, int z) {
            // A sweep reads the field after its updates, which is where the next sweep's boxes
            // begin. The value never changes, so the settle confirms once its sky stops walking.
            this.current = null;
            return 7;
        }
    }
}
