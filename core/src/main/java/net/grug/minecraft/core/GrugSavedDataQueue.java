package net.grug.minecraft.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Packets a client holds until its world can take them, scoped to the world they were queued in.
 *
 * <p>A packet is queued by the game's network thread and applied on the client's own tick, because
 * the network thread's copy of the world is not the one the frame pass draws. The queue belongs to
 * the world it was filled in: a tick that finds no world after there was one drops what that world
 * left waiting, and so does a tick whose world is not the one those packets were queued in. That is
 * what keeps a packet from a server the client has left from being written into the next server's
 * block at the same position, and it keeps the queue from growing across sessions.
 *
 * <p>A packet that arrives before the first world is up belongs to the session that is joining, so
 * it waits for that world instead of being dropped. The queue names its world as an object, so a
 * unit test can stand one in without a game.
 */
public final class GrugSavedDataQueue<T> {
    private final List<T> pending = new ArrayList<>();
    private Object world;
    private boolean hadWorld;

    /** Queues a packet, from whatever thread the game's network uses. */
    public synchronized void add(T packet) {
        pending.add(packet);
    }

    /**
     * Puts the packets a tick could not apply yet back in front of the queue, in the order they
     * were handed out, so the next tick tries them before anything the network thread queued while
     * that tick was applying. Appending them instead would let a newer packet for the same block be
     * applied first and then be overwritten by the older retry.
     */
    public synchronized void requeue(List<T> packets) {
        pending.addAll(0, packets);
    }

    /**
     * The packets to try against {@code level} this tick. A tick with no world answers an empty
     * list and drops the packets an earlier world queued, and a world other than the one they were
     * queued in drops them too.
     */
    public synchronized List<T> take(Object level) {
        if (level == null) {
            if (hadWorld) {
                pending.clear();
                world = null;
                hadWorld = false;
            }
            return Collections.emptyList();
        }
        if (hadWorld && world != level) {
            pending.clear();
        }
        world = level;
        hadWorld = true;
        if (pending.isEmpty()) {
            return Collections.emptyList();
        }
        List<T> ready = new ArrayList<>(pending);
        pending.clear();
        return ready;
    }

    /** How many packets are still waiting, which the unit test reads. */
    public synchronized int size() {
        return pending.size();
    }
}
