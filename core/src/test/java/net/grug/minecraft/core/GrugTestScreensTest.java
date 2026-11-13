package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the screen rules in {@link GrugTestScreens}. */
class GrugTestScreensTest {

    /** Stands in for the runner's empty screen, which a test never sees as open. */
    private static final class EmptyScreen {}

    /** Stands in for a screen a test opened. */
    private static final class PlayerScreen {}

    @Test
    void noScreenIsNotAnOpenOne() {
        assertFalse(GrugTestScreens.isOpen(null, EmptyScreen.class));
    }

    @Test
    void theRunnersEmptyScreenIsNotAnOpenOne() {
        assertFalse(GrugTestScreens.isOpen(new EmptyScreen(), EmptyScreen.class));
    }

    @Test
    void anyOtherScreenIsOpen() {
        assertTrue(GrugTestScreens.isOpen(new PlayerScreen(), EmptyScreen.class));
    }

    @Test
    void aScreenKeepsItsIdentityAndAnotherScreenAnswersAnother() {
        PlayerScreen screen = new PlayerScreen();
        long identity = GrugTestScreens.identityOf(screen);
        // A screen object keeps its number, so a caller that remembered it does not see a change
        // on every ask, and the number is never 0, which says no screen is open (#1289).
        assertEquals(identity, GrugTestScreens.identityOf(screen));
        assertNotEquals(0, identity);
        // A screen that replaced it while the panel stayed open is another object, and answers
        // another number, which is what tells the caller its measurements are the old screen's.
        assertNotEquals(identity, GrugTestScreens.identityOf(new PlayerScreen()));
    }

    @Test
    void anIdentityIsNeverZeroAndNeverNegative() {
        // The identity hash is negative for about half of all objects, and the server refuses a
        // negative answer, so the arithmetic is pinned here, where a hash can be chosen: the sign
        // comes only from the mask (#1289).
        for (int hash : new int[] {0, 1, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            long identity = GrugTestScreens.identity(hash);
            assertTrue(identity > 0, "identity of hash " + hash + " is " + identity);
            // The unsigned 32 bits doubled need 33 bits, and no more.
            assertTrue(identity < (1L << 33), "identity of hash " + hash + " is " + identity);
        }
    }

    @Test
    void anIdentityIsTheUnsignedHashDoubledWithTheLowBitSet() {
        assertEquals(1L, GrugTestScreens.identity(0));
        assertEquals(3L, GrugTestScreens.identity(1));
        // 0xFFFFFFFF doubled is 0x1FFFFFFFE, whose low bit is already set, so what keeps the
        // answer positive is the mask and not the OR.
        assertEquals(0xFFFFFFFFL << 1 | 1L, GrugTestScreens.identity(-1));
        assertEquals(0x80000000L << 1 | 1L, GrugTestScreens.identity(Integer.MIN_VALUE));
        assertEquals(0x7FFFFFFFL << 1 | 1L, GrugTestScreens.identity(Integer.MAX_VALUE));
    }

    @Test
    void theLargestIdentityIsExactlyADouble() {
        // The identity travels the wire as a double, so every identity must be one exactly; the
        // largest is under 2^33, well inside the 2^53 a double holds exactly.
        long largest = GrugTestScreens.identity(-1);
        assertEquals(largest, (long) (double) largest);
        assertTrue(largest < (1L << 53));
    }
}
