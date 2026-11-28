package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Covers {@link GrugGameKeyQueue}: a queued key is one press, then its release, then nothing. */
class GrugGameKeyQueueTest {

    @BeforeEach
    void forgetWhatAnEarlierTestLeft() {
        // The queue is static and shared by every test in this JVM, exactly as it is in the game.
        GrugGameKeyQueue.forget();
    }

    @Test
    void aQueuedKeyIsOnePressAndThenItsRelease() {
        GrugGameKeyQueue.queue(18);

        assertTrue(GrugGameKeyQueue.next(() -> false), "the press is due first");
        assertTrue(GrugGameKeyQueue.eventKeyState(false), "the first event is a press");
        assertEquals(18, GrugGameKeyQueue.eventKey(0), "the press carries the queued key");

        assertTrue(GrugGameKeyQueue.next(() -> false), "the release is due next");
        assertFalse(GrugGameKeyQueue.eventKeyState(true), "the second event is a release");
        assertEquals(18, GrugGameKeyQueue.eventKey(0), "the release carries the same key");
    }

    @Test
    void nothingQueuedAsksTheGameAsItAlwaysDid() {
        assertFalse(GrugGameKeyQueue.next(() -> false));
        assertFalse(GrugGameKeyQueue.eventKeyState(false));
        assertEquals(7, GrugGameKeyQueue.eventKey(7));

        assertTrue(GrugGameKeyQueue.next(() -> true));
        assertFalse(GrugGameKeyQueue.eventKeyState(false), "the game's own state stands");
        assertEquals(7, GrugGameKeyQueue.eventKey(7), "the game's own key stands");
    }

    @Test
    void aQueuedKeyIsGoneAfterTheReset() {
        // A test that queues a press in the tick it ends must not leave it for the next test
        // (#1926), whether or not its press had started being delivered.
        GrugGameKeyQueue.queue(18);
        assertTrue(GrugGameKeyQueue.next(() -> false), "the press is due first");

        GrugGameKeyQueue.forget();

        assertFalse(
                GrugGameKeyQueue.next(() -> false),
                "the reset drops a press the game had begun to serve");
        assertFalse(GrugGameKeyQueue.eventKeyState(false), "nothing is being served any more");
        assertEquals(7, GrugGameKeyQueue.eventKey(7), "the game's own key stands again");

        GrugGameKeyQueue.queue(30);
        assertTrue(GrugGameKeyQueue.next(() -> false), "a key queued after the reset is served");
        assertTrue(GrugGameKeyQueue.eventKeyState(false), "it starts with its press");
        assertEquals(30, GrugGameKeyQueue.eventKey(0), "and no state of the dropped key remains");
    }

    @Test
    void twoQueuedKeysKeepTheirOrder() {
        GrugGameKeyQueue.queue(18);
        GrugGameKeyQueue.queue(30);

        assertTrue(GrugGameKeyQueue.next(() -> false));
        assertTrue(GrugGameKeyQueue.eventKeyState(false));
        assertEquals(18, GrugGameKeyQueue.eventKey(0));

        assertTrue(GrugGameKeyQueue.next(() -> false));
        assertFalse(GrugGameKeyQueue.eventKeyState(true));
        assertEquals(18, GrugGameKeyQueue.eventKey(0));

        assertTrue(GrugGameKeyQueue.next(() -> false));
        assertTrue(GrugGameKeyQueue.eventKeyState(false));
        assertEquals(30, GrugGameKeyQueue.eventKey(0));

        assertTrue(GrugGameKeyQueue.next(() -> false));
        assertFalse(GrugGameKeyQueue.eventKeyState(true));
        assertEquals(30, GrugGameKeyQueue.eventKey(0));
    }
}
