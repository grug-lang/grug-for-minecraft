package net.grug.minecraft.core;

import java.util.function.LongSupplier;

/**
 * Keeps an integrated server's ticks in step with the ticks of the client it serves while the
 * game's clock runs faster than real time, so the world and the test runner stay on one clock.
 *
 * <p>On a version with an integrated server (1.20.6) the world ticks on the server's thread at the
 * rate its tick rate manager holds, and the test runner is driven from the client's ticks. The
 * client takes at most 10 ticks for a frame and draws at the frame rate CI manages, so it cannot
 * consume ticks as fast as the server can produce them: with only the server's rate raised the
 * world ran 1.64 times ahead of the runner at 10x and 15 times ahead at 100x (#677). The server
 * therefore asks this class before each of its ticks, and a tick waits until the client has made as
 * many ticks as the server is about to have made since {@link #start}. The server never runs ahead
 * of what the client consumed, and it catches up at once when the client made several ticks in one
 * frame.
 *
 * <p>The wait is bounded, so a client that makes no ticks (it is busy reloading resources, or has
 * stopped) cannot stop the server: the server's tick goes ahead after {@link #TIMEOUT_NANOS} and
 * its count stays ahead, so the next tick waits for the client to catch up with it.
 *
 * <p>The client may run at most {@link #MAX_TICKS_AHEAD} ticks ahead of the server, which is what
 * one frame can make. Banking that many lets the server catch up in the part of a frame the client
 * spends drawing, so a test the server keeps up with keeps its speed. A tick beyond the bank is not
 * counted, and its caller is told so: the client made it while the server was blocked, and
 * replaying it after the block would run a test's ticks back to back with no frame drawn and no
 * packet read between them (#709). {@link #clientTicked} answers whether the tick counted, and the
 * runner's tick is queued only for a counted one.
 */
public final class GrugTickLockstep {
    /** How long a server tick waits for the client before it goes ahead without it. */
    public static final long TIMEOUT_NANOS = 250_000_000L;

    /**
     * The most client ticks the client may run ahead of the server: one frame's worth, since a
     * frame runs at most {@link ModLoaderAdapter#CLIENT_TICKS_PER_FRAME} client ticks.
     */
    public static final long MAX_TICKS_AHEAD = (long) ModLoaderAdapter.CLIENT_TICKS_PER_FRAME;

    /** How a thread waits on a monitor, which a test replaces to drive the clock by hand. */
    interface Waiter {
        void await(Object monitor, long nanos) throws InterruptedException;
    }

    static final Waiter MONITOR_WAIT =
            (monitor, nanos) -> {
                // Object.wait takes milliseconds, and 0 means forever, so round a remainder up.
                monitor.wait((nanos + 999_999L) / 1_000_000L);
            };

    private final Object lock = new Object();
    private final LongSupplier clock;
    private final Waiter waiter;

    private boolean active = false;
    private long clientTicks = 0;
    private long serverTicks = 0;

    public GrugTickLockstep() {
        this(System::nanoTime, MONITOR_WAIT);
    }

    GrugTickLockstep(LongSupplier clock, Waiter waiter) {
        this.clock = clock;
        this.waiter = waiter;
    }

    /** Starts keeping the two in step, counting from zero on both sides. */
    public void start() {
        synchronized (lock) {
            clientTicks = 0;
            serverTicks = 0;
            active = true;
        }
    }

    /** Stops keeping them in step, and lets a tick that is waiting go. */
    public void stop() {
        synchronized (lock) {
            active = false;
            lock.notifyAll();
        }
    }

    /**
     * One client tick, called from the client's tick before the runner is queued. Answers whether
     * it counted: a tick made while the client is already {@link #MAX_TICKS_AHEAD} ticks ahead of
     * the server is dropped, because the server cannot act on it yet, and the caller queues no
     * runner tick for it. A tick made before {@link #start}, or after {@link #stop}, always counts.
     */
    public boolean clientTicked() {
        synchronized (lock) {
            if (!active) return true;
            if (clientTicks >= serverTicks + MAX_TICKS_AHEAD) return false;
            clientTicks++;
            lock.notifyAll();
            return true;
        }
    }

    /**
     * Called by the server thread before each of its ticks. Returns once the client has made as
     * many ticks as this one makes the server have made, or after {@link #TIMEOUT_NANOS}, or at
     * once when the lockstep is not running. Answers whether the client had caught up, which is
     * false for a tick that went ahead without it.
     */
    public boolean awaitClient() {
        synchronized (lock) {
            if (!active) return true;
            long wanted = ++serverTicks;
            long deadline = clock.getAsLong() + TIMEOUT_NANOS;
            while (active && clientTicks < wanted) {
                long left = deadline - clock.getAsLong();
                if (left <= 0) return false;
                try {
                    waiter.await(lock, left);
                } catch (InterruptedException e) {
                    // The server is being stopped. Keep the flag for whoever stops it, and go.
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }
}
