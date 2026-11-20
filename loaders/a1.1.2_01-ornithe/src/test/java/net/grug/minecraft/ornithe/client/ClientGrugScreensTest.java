package net.grug.minecraft.ornithe.client;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The guard that decides when a window-id-0 content packet may touch the player's own inventory
 * cursor (#1389).
 *
 * <p>A custom screen stores its carried stack in the same inventory cursor, so a stale inventory
 * packet that arrives after the screen opens must not be applied. The decision is a pure function
 * of whether the client currently has a custom screen open.
 */
class ClientGrugScreensTest {

    @Test
    void inventoryCursorPacketIsAppliedWhenNoCustomScreenIsOpen() {
        assertTrue(ClientGrugScreens.mayApplyInventoryCursor(false));
    }

    @Test
    void inventoryCursorPacketIsIgnoredWhenACustomScreenIsOpen() {
        assertFalse(ClientGrugScreens.mayApplyInventoryCursor(true));
    }
}
