package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

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
    void aRoomWhoseSkyUpdateWasSkippedIsSweptUntilItsSkyIsTheEnclosedOne() {
        // The engine dropped the sky update for this box, as beta's does over 32768 cells, so the
        // room put up where the sky was open still reads the sky's 15. Its block light is already
        // the field its blocks define, so only a sweep that queues and hashes the sky field can
        // see that the room is not settled, and a capture would bake every face of it at full
        // brightness (#1368).
        EnclosedRoom room = new EnclosedRoom(9, 0);

        int sweeps = GrugRelight.settle(room, 0, 0, 0, 0, 0, 0, 8);

        assertEquals(2, sweeps);
        assertEquals(0, room.sky(0, 0, 0));
        assertEquals(sweeps, room.skyQueues);
        assertEquals(sweeps, room.blockQueues);
    }

    @Test
    void aSweepThatStillMovesOnlyTheSkyIsNotSettled() {
        // A sky field that walks to its enclosed value over two sweeps: the block light is the
        // same after every one, so a hash of it alone reports a settled room that is still half
        // bright.
        EnclosedRoom room = new EnclosedRoom(9, 8, 0);

        int sweeps = GrugRelight.settle(room, 0, 0, 0, 0, 0, 0, 8);

        assertEquals(3, sweeps);
        assertEquals(0, room.sky(0, 0, 0));
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
}
