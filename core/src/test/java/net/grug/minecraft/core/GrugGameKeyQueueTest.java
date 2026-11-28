package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers {@link GrugGameKeyQueue}: a queued key is one press, then its release, then nothing. */
class GrugGameKeyQueueTest {

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
