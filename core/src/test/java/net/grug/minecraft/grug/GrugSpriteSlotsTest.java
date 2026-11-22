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
    void theAllocatorNeverHandsOutAnAnimatedSlotAndNeverRepeats() {
        GrugSpriteSlots.Allocator allocator = new GrugSpriteSlots.Allocator(160);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int previous = 159;
        for (int block = 0; block < 90; block++) {
            int slot = allocator.next();
            assertFalse(GrugSpriteSlots.isAnimated(slot), "block " + block + " got " + slot);
            assertTrue(slot > previous, "block " + block + " got " + slot);
            assertTrue(seen.add(slot), "block " + block + " repeated " + slot);
            previous = slot;
        }
        // 90 slots from 160 end at 253 once the four animated ones are skipped.
        assertEquals(253, previous);
    }
}
