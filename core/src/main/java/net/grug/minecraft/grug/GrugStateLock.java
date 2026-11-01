package net.grug.minecraft.grug;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Serializes every entry into the native grug state.
 *
 * <p>grug's state is not thread safe by design. The Rust API is {@code !Sync}, so Rust refuses to
 * share one state between threads, and the C API's callers are responsible for serializing their
 * calls. This bridge stores the state pointer in a Java {@code long} that any thread can hand to a
 * native method, which erases that guarantee, so it restores it here: every native entry takes this
 * lock.
 *
 * <p>The lock is reentrant because a host function called by a script can call back into the state
 * on the same thread before the outer call returns.
 *
 * <p>The one place the lock cannot simply be held is a call that blocks on another thread which
 * itself enters the state, which is what {@code ForgeAdapter.onClientThread} does. The blocked
 * thread is not running grug code then, so the other thread can be the active entry, and {@link
 * #runReleased(Runnable, Thread)} releases the lock for the duration of the wait.
 *
 * <p>A released wait lets in only the thread it waits for. grug's error flag belongs to the state
 * and not to a call, and the engine folds the error a nested call ends with into the call it
 * interrupted, so a chunk worker that drew a block entity and reported a refused draw while a
 * test's call was suspended stopped that test's call for an error that was not its own (#583,
 * #836). A thread that is not let in waits, holding nothing, until the wait is over and the owner
 * has taken the lock back, and then enters between calls, where an err it reports is its own.
 */
final class GrugStateLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    /** How long a thread waits for a released wait to end before that is reported as a defect. */
    static volatile long gateTimeoutMillis = 60_000;

    /** The released wait in progress that keeps other threads out, or null. */
    private static volatile Window window;

    /** A released wait that lets in one thread besides the one that released the lock. */
    private static final class Window {
        final Thread owner;
        final Thread admitted;
        final CountDownLatch over = new CountDownLatch(1);

        Window(Thread owner, Thread admitted) {
            this.owner = owner;
            this.admitted = admitted;
        }

        boolean admits(Thread thread) {
            return thread == owner || thread == admitted;
        }
    }

    private GrugStateLock() {}

    static void lock() {
        LOCK.lock();
        // A reentrant hold belongs to a thread that was let in when it took the lock the first
        // time.
        if (LOCK.getHoldCount() == 1) {
            waitOutsideWindow();
        }
    }

    static void unlock() {
        LOCK.unlock();
    }

    /**
     * Gives the lock back and waits, holding nothing, while a released wait that does not admit
     * this thread is in progress, then takes the lock again. Only the thread that released the lock
     * opens a window, and does so while it holds the lock, so a thread that has just acquired the
     * lock sees every window that was opened before it did.
     */
    private static void waitOutsideWindow() {
        Thread me = Thread.currentThread();
        Window current = window;
        while (current != null && !current.admits(me)) {
            LOCK.unlock();
            boolean timedOut = false;
            boolean interrupted = false;
            try {
                timedOut = !current.over.await(gateTimeoutMillis, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                interrupted = true;
            }
            if (timedOut) {
                throw Grug.fatal(
                        "A released wait on the grug state outlasted "
                                + gateTimeoutMillis
                                + " ms with "
                                + me.getName()
                                + " waiting to enter it, so the thread the wait is for is probably"
                                + " waiting for this one");
            }
            LOCK.lock();
            if (interrupted) {
                // Interrupted at a shutdown: enter rather than lose the interrupt, and the call
                // that follows is the caller's to fail.
                me.interrupt();
                return;
            }
            current = window;
        }
    }

    /**
     * Runs {@code blockingWait} with this thread's hold on the state lock released, then restores
     * the hold. A wait on a thread that holds no lock is unaffected, so callers do not have to know
     * whether they are inside a grug call.
     *
     * <p>Every thread may enter the state while the wait runs. This is the form for a wait that
     * needs the other threads' work to finish, such as a settle wait that polls until the chunk
     * workers have drawn.
     *
     * <p>The released task may run export functions only. Compiling or updating files would reach
     * the backend's file table borrow, which the suspended call still holds, so it would panic
     * rather than wait. See {@link Grug#runWithStateLockReleased(Runnable)}.
     */
    static void runReleased(Runnable blockingWait) {
        int holds = isHeldByCurrentThread() ? holdCount() : 0;
        release(holds);
        try {
            blockingWait.run();
        } finally {
            reacquire(holds);
        }
    }

    /**
     * Like {@link #runReleased(Runnable)}, but the only thread besides this one that may enter the
     * state while the wait runs is {@code admitted}, which is the thread the wait is for. Every
     * other thread that asks waits until the wait is over and this thread holds the lock again. See
     * the class comment for why.
     *
     * <p>Without a lock held there is no suspended call to protect, so the wait runs as it is. A
     * window opened by a thread that was itself let in by an outer one is not opened, since the
     * outer one already decides who enters.
     */
    static void runReleased(Runnable blockingWait, Thread admitted) {
        int holds = isHeldByCurrentThread() ? holdCount() : 0;
        Window opened = null;
        if (holds > 0 && window == null) {
            opened = new Window(Thread.currentThread(), admitted);
            window = opened;
        }
        release(holds);
        try {
            blockingWait.run();
        } finally {
            reacquire(holds);
            if (opened != null) {
                window = null;
                opened.over.countDown();
            }
        }
    }

    private static void release(int holds) {
        for (int i = 0; i < holds; i++) {
            unlock();
        }
    }

    /** Takes back holds this thread had, which no window can refuse it. */
    private static void reacquire(int holds) {
        for (int i = 0; i < holds; i++) {
            LOCK.lock();
        }
    }

    static boolean isHeldByCurrentThread() {
        return LOCK.isHeldByCurrentThread();
    }

    static int holdCount() {
        return LOCK.getHoldCount();
    }
}
