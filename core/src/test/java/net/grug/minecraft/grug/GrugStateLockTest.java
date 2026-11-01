package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Covers the lock that serializes entries into the native grug state. */
class GrugStateLockTest {

    /** A failed assertion must not leave the static lock held for the tests that follow. */
    @AfterEach
    void releaseTheLock() {
        GrugStateLock.gateTimeoutMillis = 60_000;
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
        CountDownLatch otherAcquired = new CountDownLatch(1);
        Thread other =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            try {
                                otherAcquired.countDown();
                            } finally {
                                GrugStateLock.unlock();
                            }
                        });
        try {
            // The acquisition is awaited inside the release, so the wait cannot end and reacquire
            // the lock before the other thread has had its turn. Joining the thread instead would
            // time out into a failure that blamed the release for a thread that was merely slow to
            // start.
            GrugStateLock.runReleased(
                    () -> {
                        other.start();
                        try {
                            assertTrue(
                                    otherAcquired.await(2, TimeUnit.SECONDS),
                                    "the waiting thread could not enter the state");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
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

    /** A thread that takes the lock, says so, and gives it back. */
    private static Thread entrant(CountDownLatch entered) {
        Thread thread =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            try {
                                entered.countDown();
                            } finally {
                                GrugStateLock.unlock();
                            }
                        });
        thread.setDaemon(true);
        return thread;
    }

    @Test
    void aGatedWaitLetsInOnlyTheThreadItIsFor() throws Exception {
        CountDownLatch admittedEntered = new CountDownLatch(1);
        CountDownLatch strangerEntered = new CountDownLatch(1);
        Thread admitted = entrant(admittedEntered);
        Thread stranger = entrant(strangerEntered);
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () -> {
                        stranger.start();
                        admitted.start();
                        try {
                            assertTrue(
                                    admittedEntered.await(2, TimeUnit.SECONDS),
                                    "the thread the wait is for could not enter the state");
                            assertFalse(
                                    strangerEntered.await(200, TimeUnit.MILLISECONDS),
                                    "another thread entered the state during the wait");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    admitted);
            assertTrue(GrugStateLock.isHeldByCurrentThread(), "the hold was not restored");
            // The wait is over and this thread has its lock back, so the stranger still waits for
            // it
            // and not for the window.
            assertFalse(strangerEntered.await(200, TimeUnit.MILLISECONDS));
        } finally {
            GrugStateLock.unlock();
        }
        assertTrue(
                strangerEntered.await(2, TimeUnit.SECONDS),
                "the waiting thread never entered once the wait was over");
        stranger.join(2000);
        admitted.join(2000);
    }

    @Test
    void aThreadKeptOutHoldsNothingWhileItWaits() throws Exception {
        CountDownLatch strangerEntered = new CountDownLatch(1);
        Thread stranger = entrant(strangerEntered);
        AtomicBoolean ownerCouldTakeItBack = new AtomicBoolean();
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () -> {
                        stranger.start();
                        try {
                            assertFalse(strangerEntered.await(200, TimeUnit.MILLISECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        // The thread that released the lock may enter during its own wait too.
                        GrugStateLock.lock();
                        GrugStateLock.unlock();
                    },
                    new Thread());
            ownerCouldTakeItBack.set(GrugStateLock.isHeldByCurrentThread());
        } finally {
            GrugStateLock.unlock();
        }
        assertTrue(ownerCouldTakeItBack.get());
        assertTrue(strangerEntered.await(2, TimeUnit.SECONDS));
        stranger.join(2000);
    }

    @Test
    void aGatedWaitOpenedInsideAnotherOneLeavesTheOuterOneInCharge() throws Exception {
        CountDownLatch strangerEntered = new CountDownLatch(1);
        CountDownLatch innerDone = new CountDownLatch(1);
        Thread stranger = entrant(strangerEntered);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread admitted =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            GrugStateLock.lock();
                            try {
                                // The admitted thread holds the lock twice, and asks for a wait of
                                // its own that admits the stranger: the outer wait decides, so the
                                // stranger stays out.
                                GrugStateLock.runReleased(
                                        () -> {
                                            stranger.start();
                                            try {
                                                if (strangerEntered.await(
                                                        200, TimeUnit.MILLISECONDS)) {
                                                    failure.set(
                                                            new AssertionError(
                                                                    "the inner wait let in a"
                                                                            + " thread"));
                                                }
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                        },
                                        stranger);
                            } finally {
                                GrugStateLock.unlock();
                                GrugStateLock.unlock();
                                innerDone.countDown();
                            }
                        });
        admitted.setDaemon(true);
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () -> {
                        admitted.start();
                        try {
                            assertTrue(innerDone.await(3, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    admitted);
        } finally {
            GrugStateLock.unlock();
        }
        assertTrue(strangerEntered.await(2, TimeUnit.SECONDS));
        stranger.join(2000);
        admitted.join(2000);
        assertEquals(null, failure.get());
    }

    @Test
    void aGatedWaitWithoutALockRunsTheWait() {
        AtomicBoolean ran = new AtomicBoolean();
        GrugStateLock.runReleased(() -> ran.set(true), Thread.currentThread());
        assertTrue(ran.get());
        assertFalse(GrugStateLock.isHeldByCurrentThread());
    }

    @Test
    void aThreadThatWaitsTooLongForAGatedWaitIsToldSo() throws Exception {
        GrugStateLock.gateTimeoutMillis = 100;
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread stranger =
                new Thread(
                        () -> {
                            try {
                                GrugStateLock.lock();
                            } catch (RuntimeException e) {
                                thrown.set(e);
                            }
                        });
        stranger.setDaemon(true);
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () -> {
                        stranger.start();
                        try {
                            stranger.join(3000);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    Thread.currentThread());
        } finally {
            GrugStateLock.unlock();
        }
        assertTrue(thrown.get() instanceof IllegalStateException, String.valueOf(thrown.get()));
        assertTrue(thrown.get().getMessage().contains("outlasted 100 ms"));
    }

    @Test
    void anInterruptedThreadEntersAndKeepsItsInterrupt() throws Exception {
        AtomicBoolean interruptedOnEntry = new AtomicBoolean();
        CountDownLatch entered = new CountDownLatch(1);
        Thread stranger =
                new Thread(
                        () -> {
                            GrugStateLock.lock();
                            try {
                                interruptedOnEntry.set(Thread.currentThread().isInterrupted());
                                entered.countDown();
                            } finally {
                                GrugStateLock.unlock();
                            }
                        });
        stranger.setDaemon(true);
        GrugStateLock.lock();
        try {
            GrugStateLock.runReleased(
                    () -> {
                        stranger.start();
                        try {
                            Thread.sleep(200);
                            stranger.interrupt();
                            assertTrue(entered.await(2, TimeUnit.SECONDS));
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    },
                    Thread.currentThread());
        } finally {
            GrugStateLock.unlock();
        }
        stranger.join(2000);
        assertTrue(interruptedOnEntry.get());
    }

    @Test
    void grugRunWithStateLockReleasedForAThreadDelegates() {
        AtomicBoolean ran = new AtomicBoolean();
        Grug.runWithStateLockReleased(() -> ran.set(true), Thread.currentThread());
        assertTrue(ran.get());
    }
}
