package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

/** Covers {@link GrugTickLockstep} with a clock and a wait that the test drives by hand. */
class GrugTickLockstepTest {
    /** A clock that only moves when a wait passes time, and a wait that runs a script. */
    private static final class Hand implements GrugTickLockstep.Waiter {
        long now = 0;
        final List<Long> waits = new ArrayList<>();
        Runnable whileWaiting = () -> {};
        boolean interrupt = false;

        @Override
        public void await(Object monitor, long nanos) throws InterruptedException {
            waits.add(nanos);
            if (interrupt) throw new InterruptedException();
            now += nanos;
            whileWaiting.run();
        }
    }

    private static GrugTickLockstep lockstep(Hand hand) {
        return new GrugTickLockstep(() -> hand.now, hand);
    }

    @Test
    void doesNotHoldAnythingBeforeItStarts() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);

        assertTrue(lockstep.awaitClient());
        lockstep.clientTicked();

        assertTrue(hand.waits.isEmpty());
    }

    @Test
    void aServerTickWaitsUntilTheClientHasMadeAsManyTicks() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();
        lockstep.clientTicked();

        assertTrue(lockstep.awaitClient(), "The first tick has the client's first tick behind it.");
        assertTrue(hand.waits.isEmpty());

        hand.whileWaiting = lockstep::clientTicked;
        assertTrue(lockstep.awaitClient(), "The second tick waits for the client's second tick.");
        assertEquals(1, hand.waits.size());
    }

    @Test
    void aClientThatRanAheadLetsTheServerCatchUpWithoutWaiting() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();
        for (int i = 0; i < 10; i++) lockstep.clientTicked();

        for (int i = 0; i < 10; i++) assertTrue(lockstep.awaitClient());

        assertTrue(hand.waits.isEmpty());
    }

    @Test
    void aClientThatStopsTicksOnlyHoldsTheServerForTheTimeout() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();

        assertFalse(lockstep.awaitClient());

        assertEquals(List.of(GrugTickLockstep.TIMEOUT_NANOS), hand.waits);
    }

    @Test
    void aTickThatWentAheadLeavesTheServerAheadUntilTheClientCatchesUp() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();
        assertFalse(lockstep.awaitClient());

        // The client makes the one tick it owed, which is not enough for the next server tick.
        lockstep.clientTicked();
        hand.waits.clear();
        hand.now = 0;
        assertFalse(lockstep.awaitClient(), "Two ticks are owed and the client made one.");
        assertFalse(hand.waits.isEmpty());
    }

    /** A wait that passes a tenth of the timeout each time, and ticks the client on the third. */
    private static final class Pieces implements GrugTickLockstep.Waiter {
        long now = 0;
        final List<Long> waits = new ArrayList<>();
        GrugTickLockstep owner;

        @Override
        public void await(Object monitor, long nanos) {
            waits.add(nanos);
            now += GrugTickLockstep.TIMEOUT_NANOS / 10;
            if (waits.size() == 3) owner.clientTicked();
        }
    }

    @Test
    void aWaitThatWakesEarlyWaitsOutTheRest() {
        Pieces pieces = new Pieces();
        GrugTickLockstep lockstep = new GrugTickLockstep(() -> pieces.now, pieces);
        pieces.owner = lockstep;
        lockstep.start();

        assertTrue(lockstep.awaitClient());

        assertEquals(3, pieces.waits.size());
        assertEquals(GrugTickLockstep.TIMEOUT_NANOS, pieces.waits.get(0));
        assertEquals(GrugTickLockstep.TIMEOUT_NANOS / 10 * 9, pieces.waits.get(1));
        assertEquals(GrugTickLockstep.TIMEOUT_NANOS / 10 * 8, pieces.waits.get(2));
    }

    @Test
    void stoppingReleasesAWaitingTickAndStopsCounting() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();
        hand.whileWaiting = lockstep::stop;

        assertTrue(lockstep.awaitClient());

        hand.whileWaiting = () -> {};
        lockstep.clientTicked();
        assertTrue(lockstep.awaitClient(), "Stopped, nothing is held.");
        assertEquals(1, hand.waits.size());
    }

    @Test
    void startingAgainCountsFromZero() {
        Hand hand = new Hand();
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();
        for (int i = 0; i < 5; i++) lockstep.clientTicked();
        for (int i = 0; i < 5; i++) lockstep.awaitClient();
        lockstep.stop();

        lockstep.start();

        assertFalse(lockstep.awaitClient(), "Nothing from the run before counts.");
    }

    @Test
    void anInterruptEndsTheWaitAndKeepsTheFlag() {
        Hand hand = new Hand();
        hand.interrupt = true;
        GrugTickLockstep lockstep = lockstep(hand);
        lockstep.start();

        try {
            assertFalse(lockstep.awaitClient());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void theMonitorWaitWaitsOnTheMonitorForAtLeastTheTimeItWasGiven() throws Exception {
        Object monitor = new Object();
        long before = System.nanoTime();

        synchronized (monitor) {
            GrugTickLockstep.MONITOR_WAIT.await(monitor, 2_000_000L);
        }

        assertTrue(System.nanoTime() - before >= 1_000_000L);
    }
}
