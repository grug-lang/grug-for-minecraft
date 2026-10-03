package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Covers the lock that serializes entries into the native grug state. */
class GrugStateLockTest {

    /** A failed assertion must not leave the static lock held for the tests that follow. */
    @AfterEach
    void releaseTheLock() {
        while (GrugStateLock.isHeldByCurrentThread()) {
            GrugStateLock.unlock();
        }
    }

    @Test
    void lockIsReentrant() {
        GrugStateLock.lock();
        GrugStateLock.lock();
        assertEquals(2, GrugStateLock.holdCount());

        GrugStateLock.unlock();
        assertTrue(GrugStateLock.isHeldByCurrentThread());

        GrugStateLock.unlock();
        assertFalse(GrugStateLock.isHeldByCurrentThread());
    }

    @Test
    void anotherThreadWaitsForTheHolder() throws Exception {
        GrugStateLock.lock();
        CountDownLatch acquired = new CountDownLatch(1);
        Thread other =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            try {
                                acquired.countDown();
                            } finally {
                                GrugStateLock.unlock();
                            }
                        });
        try {
            other.start();
            assertFalse(
                    acquired.await(200, TimeUnit.MILLISECONDS),
                    "another thread acquired a lock that was still held");
        } finally {
            GrugStateLock.unlock();
        }
        assertTrue(acquired.await(2, TimeUnit.SECONDS), "the other thread never acquired the lock");
        other.join(2000);
        assertFalse(other.isAlive());
    }

    @Test
    void runReleasedLetsAnotherThreadEnterAndRestoresTheHold() throws Exception {
        GrugStateLock.lock();
        AtomicBoolean otherEntered = new AtomicBoolean();
        Thread other =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            try {
                                otherEntered.set(true);
                            } finally {
                                GrugStateLock.unlock();
                            }
                        });
        try {
            GrugStateLock.runReleased(
                    () -> {
                        other.start();
                        try {
                            other.join(2000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            assertTrue(otherEntered.get(), "the waiting thread could not enter the state");
            assertTrue(
                    GrugStateLock.isHeldByCurrentThread(),
                    "the hold released for the wait was not restored");
        } finally {
            GrugStateLock.unlock();
        }
        other.join(2000);
        assertFalse(other.isAlive());
    }

    @Test
    void runReleasedRestoresEveryHold() {
        GrugStateLock.lock();
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () ->
                            assertFalse(
                                    GrugStateLock.isHeldByCurrentThread(),
                                    "the lock must be free while the wait runs"));
            assertEquals(2, GrugStateLock.holdCount());
        } finally {
            GrugStateLock.unlock();
            GrugStateLock.unlock();
        }
    }

    @Test
    void runReleasedRunsTheWaitWhenNoLockIsHeld() {
        AtomicBoolean ran = new AtomicBoolean();
        GrugStateLock.runReleased(() -> ran.set(true));
        assertTrue(ran.get());
        assertFalse(GrugStateLock.isHeldByCurrentThread());
    }

    @Test
    void grugRunWithStateLockReleasedDelegates() {
        AtomicBoolean ran = new AtomicBoolean();
        Grug.runWithStateLockReleased(() -> ran.set(true));
        assertTrue(ran.get());
    }
}
