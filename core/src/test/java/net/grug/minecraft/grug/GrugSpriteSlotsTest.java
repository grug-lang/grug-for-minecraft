package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugSpriteSlotsTest {
    @Test
    void theAnimatedWaterAndLavaSlotsAreRefused() {
        for (int slot : new int[] {205, 206, 237, 238}) {
            assertTrue(GrugSpriteSlots.isAnimated(slot), "slot " + slot);
            assertEquals(
                    slot == 205 ? 207 : slot == 206 ? 207 : 239, GrugSpriteSlots.firstUsable(slot));
        }
    }

    @Test
    void anyOtherSlotIsKept() {
        for (int slot : new int[] {160, 204, 207, 236, 239, 255}) {
            assertFalse(GrugSpriteSlots.isAnimated(slot), "slot " + slot);
            assertEquals(slot, GrugSpriteSlots.firstUsable(slot));
        }
    }

    @Test
    void walkingFromTheFirstSlotNeverLandsOnOne() {
        // The way the loaders hand out slots: take the first usable one at or after the cursor and
        // move past it. Enough blocks to walk over both animated pairs must still land clear.
        int cursor = 160;
        for (int block = 0; block < 90; block++) {
            int slot = GrugSpriteSlots.firstUsable(cursor);
            assertFalse(GrugSpriteSlots.isAnimated(slot), "block " + block + " got " + slot);
            cursor = slot + 1;
        }
    }
}
