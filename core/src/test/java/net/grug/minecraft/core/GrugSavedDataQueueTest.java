package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Covers the world scoping of a loader's saved-data queue, which is what keeps a packet from a
 * server the client has left out of the next server's block at the same position, and the order a
 * packet goes back in when the tick could not apply it yet.
 *
 * <p>The queue only names its levels as objects, so a test can stand a world or a connection in for
 * one without a game: what the packets hold does not matter here, only which world queued them.
 */
class GrugSavedDataQueueTest {

    /** The world a packet was queued in, or the one the client joined after it. */
    private static final Object FIRST_WORLD = new Object();

    private static final Object SECOND_WORLD = new Object();

    private static final String PACKET = "saved data for block 137 at 10,64,10";

    @Test
    void aPacketQueuedBeforeTheFirstWorldWaitsForIt() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        queue.add(PACKET);

        // The client has no world for a tick or two while it joins, which is not a world it left.
        assertTrue(queue.take(null).isEmpty());
        assertEquals(1, queue.size());

        assertEquals(Collections.singletonList(PACKET), queue.take(FIRST_WORLD));
    }

    @Test
    void aStalePacketIsNotAppliedInTheNextWorldAtTheSamePosition() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        assertTrue(queue.take(FIRST_WORLD).isEmpty());
        queue.add(PACKET);

        // The client disconnects while the packet is still waiting, then joins another server that
        // happens to hold the same block id at the same position. The packet names that block, so
        // only the world it was queued in can tell the two apart.
        assertTrue(queue.take(null).isEmpty());
        assertEquals(0, queue.size());

        // Nothing is handed to the next world, so nothing is applied there.
        assertTrue(queue.take(SECOND_WORLD).isEmpty());
        assertEquals(0, queue.size());
        assertTrue(queue.take(SECOND_WORLD).isEmpty());
    }

    @Test
    void aWorldChangeDropsWhatTheOldWorldQueued() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        assertTrue(queue.take(FIRST_WORLD).isEmpty());
        queue.add(PACKET);

        // A world replaced without a tick that found no world still is a different world.
        assertTrue(queue.take(SECOND_WORLD).isEmpty());
        assertEquals(0, queue.size());
    }

    @Test
    void aRetryKeepsWhatTheSameWorldQueued() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        queue.add(PACKET);

        List<String> first = queue.take(FIRST_WORLD);
        assertEquals(Collections.singletonList(PACKET), first);

        // A packet the tick could not apply yet goes back into the queue it came from, in front.
        queue.requeue(first);
        assertEquals(Collections.singletonList(PACKET), queue.take(FIRST_WORLD));
        assertEquals(0, queue.size());
    }

    @Test
    void aRetryGoesInFrontOfAPacketThatArrivedWhileItWasApplied() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        queue.add("an older number");
        List<String> older = queue.take(FIRST_WORLD);

        // The network thread queues a newer packet for the same block while the older one is
        // applied. Appending the older one would apply the newer first and then overwrite it.
        queue.add("a newer number");
        queue.requeue(older);

        assertEquals(Arrays.asList("an older number", "a newer number"), queue.take(FIRST_WORLD));
        assertEquals(0, queue.size());
    }

    @Test
    void retriesKeepTheirOrderAheadOfPacketsThatArrivedDuringThePass() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        queue.add("a");
        queue.add("b");
        queue.add("c");
        List<String> first = queue.take(FIRST_WORLD);

        // "a" applied; "b" and "c" have to be tried again, and "d" and "e" arrive meanwhile.
        queue.add("d");
        queue.add("e");
        queue.requeue(Arrays.asList("b", "c"));

        assertEquals(Arrays.asList("b", "c", "d", "e"), queue.take(FIRST_WORLD));
        assertEquals(0, queue.size());
    }

    @Test
    void theQueueDoesNotGrowAcrossSessions() {
        GrugSavedDataQueue<String> queue = new GrugSavedDataQueue<>();
        assertTrue(queue.take(FIRST_WORLD).isEmpty());
        queue.add(PACKET);
        queue.add(PACKET);
        queue.add(PACKET);
        assertEquals(3, queue.size());

        // Leaving the world drops them all, whatever was still waiting.
        assertTrue(queue.take(null).isEmpty());
        assertEquals(0, queue.size());

        // The next session queues only its own.
        queue.add("saved data for the next server's block");
        assertEquals(
                Collections.singletonList("saved data for the next server's block"),
                queue.take(SECOND_WORLD));
        assertEquals(0, queue.size());
    }
}
