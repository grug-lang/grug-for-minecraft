package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugSpriteSlotsTest {
    @Test
    void theAnimatedWaterAndLavaSlotsAreRefused() {
        // Each flowing frame is replicated over a 2x2 block of tiles (DynamicTexture.replicate).
        int[] animated = {205, 206, 207, 222, 223, 237, 238, 239, 254, 255};
        for (int slot : animated) {
            assertTrue(GrugSpriteSlots.isAnimated(slot), "slot " + slot);
        }
        assertEquals(208, GrugSpriteSlots.firstUsable(205));
        assertEquals(224, GrugSpriteSlots.firstUsable(222));
        assertEquals(240, GrugSpriteSlots.firstUsable(237));
    }

    @Test
    void anyOtherSlotIsKept() {
        for (int slot : new int[] {160, 204, 208, 221, 224, 236, 240, 253}) {
            assertFalse(GrugSpriteSlots.isAnimated(slot), "slot " + slot);
            assertEquals(slot, GrugSpriteSlots.firstUsable(slot));
        }
    }

    @Test
    void theAllocatorNeverHandsOutAnAnimatedSlotAndNeverRepeats() {
        GrugSpriteSlots.Allocator allocator = new GrugSpriteSlots.Allocator(160);
        java.util.Set<Integer> seen = new java.util.HashSet<>();
        int previous = 159;
        for (int block = 0; block < 72; block++) {
            int slot = allocator.next();
            assertFalse(GrugSpriteSlots.isAnimated(slot), "block " + block + " got " + slot);
            assertTrue(slot > previous, "block " + block + " got " + slot);
            assertTrue(seen.add(slot), "block " + block + " repeated " + slot);
            previous = slot;
        }
        // 72 slots from 160 end at 236 once the animated 205, 206, 207, 222 and 223 are skipped.
        assertEquals(236, previous);
    }
}
