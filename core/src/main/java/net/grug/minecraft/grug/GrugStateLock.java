package net.grug.minecraft.grug;

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
 * #runReleased(Runnable)} releases the lock for the duration of the wait.
 */
final class GrugStateLock {
    private static final ReentrantLock LOCK = new ReentrantLock();

    private GrugStateLock() {}

    static void lock() {
        LOCK.lock();
    }

    static void unlock() {
        LOCK.unlock();
    }

    /**
     * Runs {@code blockingWait} with this thread's hold on the state lock released, then restores
     * the hold. A wait on a thread that holds no lock is unaffected, so callers do not have to know
     * whether they are inside a grug call.
     */
    static void runReleased(Runnable blockingWait) {
        int holds = isHeldByCurrentThread() ? holdCount() : 0;
        for (int i = 0; i < holds; i++) {
            unlock();
        }
        try {
            blockingWait.run();
        } finally {
            for (int i = 0; i < holds; i++) {
                lock();
            }
        }
    }

    static boolean isHeldByCurrentThread() {
        return LOCK.isHeldByCurrentThread();
    }

    static int holdCount() {
        return LOCK.getHoldCount();
    }
}
