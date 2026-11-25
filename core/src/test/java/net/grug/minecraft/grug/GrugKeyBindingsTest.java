package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers how a polled binding becomes one press: the first poll that sees it down, no press while a
 * screen is open, and no press again until it is released.
 */
class GrugKeyBindingsTest {

    @Test
    void aBindingIsOnePressAndNotOnePerPoll() {
        GrugKeyBindings keys = new GrugKeyBindings();

        assertTrue(keys.deliver("mod", "toggle", true, true), "The first down poll is a press.");
        assertFalse(keys.deliver("mod", "toggle", true, true), "A held binding is not a press.");
        assertFalse(keys.deliver("mod", "toggle", false, true), "A release is not a press.");
        assertTrue(keys.deliver("mod", "toggle", true, true), "After a release it presses again.");
    }

    @Test
    void aPressWithAScreenOpenIsNotDeliveredButTheStateIsKept() {
        GrugKeyBindings keys = new GrugKeyBindings();

        assertFalse(keys.deliver("mod", "toggle", true, false), "No screen means no press.");
        assertFalse(
                keys.deliver("mod", "toggle", true, true),
                "The key held while the screen was open was not a fresh press.");
        assertFalse(keys.deliver("mod", "toggle", false, true));
        assertTrue(keys.deliver("mod", "toggle", true, true));
    }

    @Test
    void bindingsAreIndependentByModAndId() {
        GrugKeyBindings keys = new GrugKeyBindings();

        assertTrue(keys.deliver("one", "toggle", true, true));
        assertTrue(keys.deliver("one", "other", true, true));
        assertTrue(keys.deliver("two", "toggle", true, true));
        assertFalse(keys.deliver("one", "toggle", true, true));
        assertFalse(keys.deliver("two", "toggle", true, true));
    }
}
