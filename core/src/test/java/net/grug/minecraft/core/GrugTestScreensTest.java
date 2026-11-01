package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Covers the screen rule in {@link GrugTestScreens}. */
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
}
