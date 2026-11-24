package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the light a settle ask compares over a box a dedicated server sent (#1695). */
class GrugLightAskTest {
    private static final GrugLightSync.BlockLight DARK = (x, y, z) -> 0;
    private static final GrugLightSync.BlockLight FULL = (x, y, z) -> 15;

    private static final int[] BOX = {0, 64, 0, 3, 67, 3};

    @Test
    void anAskRoundTripsAndOnlyItsOwnTextIsOne() {
        String text = GrugLightAsk.encode(BOX, 0x1FFFFFFFFFFFFFL);
        assertEquals("light:0,64,0,3,67,3|1fffffffffffff", text);

        GrugLightAsk.Expected sent = GrugLightAsk.decode(text);
        assertArrayEquals(BOX, sent.box);
        assertEquals(0x1FFFFFFFFFFFFFL, sent.fingerprint);

        assertTrue(GrugLightAsk.isAsk(text));
        // The block region's text and the empty text are not light asks, which is how the client
        // tells the two apart in the one name slot.
        assertNull(GrugLightAsk.decode(""));
        assertNull(GrugLightAsk.decode("0,64,0,3,67,3;|1fffffffffffff"));
        assertFalse(GrugLightAsk.isAsk(""));
        assertFalse(GrugLightAsk.isAsk("0,64,0,3,67,3;|1fffffffffffff"));
    }

    @Test
    void aMalformedAskThrowsRatherThanReadingAsNoExpectation() {
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightAsk.decode("light:0,64,0,3,67,3"));
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightAsk.decode("light:0,64,0,3,67|1"));
        assertThrows(
                IllegalArgumentException.class, () -> GrugLightAsk.decode("light:0,64,0,3,67,x|1"));
    }

    @Test
    void aBoxWithNoLightAtAllIsZeroAndTheSameFieldIsTheSameFingerprint() {
        assertEquals(0, GrugLightAsk.fingerprint(BOX, DARK, DARK));
        assertEquals(
                GrugLightAsk.fingerprint(BOX, DARK, FULL),
                GrugLightAsk.fingerprint(BOX, DARK, FULL));
        // Both fields are part of it: a client of open sky over an enclosed room hashes other than
        // the server's, which is the stale sky field that passed a block-only comparison (#1695).
        assertNotEquals(
                GrugLightAsk.fingerprint(BOX, DARK, DARK),
                GrugLightAsk.fingerprint(BOX, DARK, FULL));
        assertNotEquals(
                GrugLightAsk.fingerprint(BOX, DARK, FULL),
                GrugLightAsk.fingerprint(BOX, FULL, DARK));
    }

    @Test
    void aBoxHoldsOnlyTheLightTheServerSentForItsOwnCells() {
        String ask = GrugLightAsk.ask(BOX, DARK, DARK);
        // A box is the cell one below the asked range, so a client that lights one cell outside the
        // box differently still holds it: the ask is over the box the server sent, not a section.
        GrugLightSync.BlockLight outsideOnly = (x, y, z) -> y == 63 || y == 68 ? 15 : 0;
        assertTrue(GrugLightAsk.holds(GrugLightAsk.decode(ask), outsideOnly, DARK));

        String lit = GrugLightAsk.ask(BOX, DARK, (x, y, z) -> x == 2 && z == 2 ? 15 : 0);
        assertFalse(GrugLightAsk.holds(GrugLightAsk.decode(lit), DARK, DARK));
        assertTrue(
                GrugLightAsk.holds(
                        GrugLightAsk.decode(lit), DARK, (x, y, z) -> x == 2 && z == 2 ? 15 : 0));
        // A null expectation is held: the ask named no box.
        assertTrue(GrugLightAsk.holds(null, DARK, FULL));
    }

    @Test
    void aDivergentAnswerIsNotSettledAndSendsTheBoxAgain() {
        int[] rearms = new int[1];
        Runnable rearm = () -> rearms[0]++;

        assertTrue(GrugLightAsk.settled(GrugControlWire.SETTLE_YES, rearm));
        assertEquals(0, rearms[0], "a settled answer owes nothing");
        assertFalse(GrugLightAsk.settled(GrugControlWire.SETTLE_NO, rearm));
        assertEquals(0, rearms[0], "an unsettled world is not about the light");

        // The divergent answer is a client that drew the world from its own light: the loader sends
        // the box again and this poll is not settled.
        assertFalse(GrugLightAsk.settled(GrugControlWire.SETTLE_LIGHT_DIVERGED, rearm));
        assertEquals(1, rearms[0]);
    }

    @Test
    void anAskWithNoBoxIsTheEmptyText() {
        assertEquals("", GrugLightAsk.ask(new int[0], DARK, FULL));
    }
}
