package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class GrugSpriteSlotsTest {

    /**
     * Forgets the shortage a test recorded, and drains the fatal an exhausted allocator provoked.
     * {@code Grug.fatal} remembers the first error per thread and rethrows it at the next native
     * call boundary, so leaving one set would fail the next test on this JVM that drives an export
     * function, for a reason unrelated to that test.
     */
    @AfterEach
    void clearShortageAndDrainPendingFatal() {
        GrugIdShortage.clear();
        try {
            GrugErrors.throwPendingFatal();
        } catch (IllegalStateException expected) {
            // The fatal the test provoked, cleared so it cannot outlive the test.
        }
    }

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

    @Test
    void theAllocatorEndsAtTheGridsLastUsableSlotAndRefusesOnTheNextCall() {
        // The 16 by 16 grid has 256 slots, and the ten the game animates leave 86 usable ones
        // from 160. The last of them is 253, because 254 and 255 are the lava frame's tiles.
        GrugSpriteSlots.Allocator allocator = new GrugSpriteSlots.Allocator(160);
        assertEquals(86, allocator.remaining());
        int slot = -1;
        for (int block = 0; block < 86; block++) {
            slot = allocator.next();
            assertFalse(GrugSpriteSlots.isAnimated(slot), "block " + block + " got " + slot);
            assertTrue(slot <= GrugSpriteSlots.LAST_SLOT, "block " + block + " got " + slot);
        }
        assertEquals(253, slot, "The last usable slot of the atlas");
        assertEquals(0, allocator.remaining());

        IllegalStateException full = assertThrows(IllegalStateException.class, allocator::next);
        assertTrue(full.getMessage().contains("terrain.png"), full.getMessage());
        assertTrue(full.getMessage().contains("16 by 16"), full.getMessage());
    }

    @Test
    void anExactFitIsRoomAndLeavesNoShortage() {
        GrugSpriteSlots.Allocator blocks = new GrugSpriteSlots.Allocator(160);
        Map<String, String> needs = new LinkedHashMap<>();
        for (int texture = 0; texture < blocks.remaining(); texture++) {
            needs.put("grug:block/texture" + texture, "coverage");
        }

        assertTrue(blocks.hasRoomFor(needs.size(), "block sprite", needs));
        assertTrue(GrugIdShortage.all().isEmpty());
    }

    @Test
    void spritesTwoModsShareAreCountedOnceAndFitTogether() {
        GrugSpriteSlots.Allocator blocks = new GrugSpriteSlots.Allocator(160);
        Map<String, List<String>> needs = new LinkedHashMap<>();
        for (int texture = 0; texture < blocks.remaining(); texture++) {
            needs.put("grug:block/texture" + texture, List.of("a", "b"));
        }

        assertTrue(blocks.hasRoomForSprites(needs.size(), "block sprite", needs));
        assertTrue(GrugIdShortage.all().isEmpty());
    }

    @Test
    void oneSlotOverRecordsTheShortageAndWhatEachModNeeds() {
        GrugSpriteSlots.Allocator items = GrugSpriteSlots.Allocator.items(160);
        Map<String, String> needs = new LinkedHashMap<>();
        for (int item = 0; item < 40; item++) needs.put("a" + item, "a");
        for (int item = 0; item < 40; item++) needs.put("b" + item, "b");
        needs.put("shared", "a");
        needs.put("shared", "b"); // the second put replaces the first mod, as registration does
        for (int item = 0; item < 16; item++) needs.put("c" + item, "c");
        // 40 + 40 + the one shared entry + 16 is 97, one more than the items grid's 96 slots.
        assertEquals(97, needs.size());

        assertFalse(items.hasRoomFor(needs.size(), "item sprite", needs));

        assertEquals(1, GrugIdShortage.all().size());
        GrugIdShortage shortage = GrugIdShortage.all().get(0);
        assertEquals("item sprite", shortage.kind());
        assertEquals(97, shortage.needed());
        assertEquals(96, shortage.free());
        assertEquals(1, shortage.missing());
        assertEquals(40, shortage.needs("a"));
        assertEquals(41, shortage.needs("b")); // 40, and the shared entry's slot counts once
        assertEquals(16, shortage.needs("c"));
    }

    @Test
    void aBlockNeedsOneEntryPerTextureAndOneOfItsOwnWhenItsFacesDoNotResolve() {
        Map<String, String> mods = new LinkedHashMap<>();
        mods.put("first", "a");
        mods.put("second", "b");
        mods.put("third", "c");
        Map<String, String[]> faces = new HashMap<>();
        String[] cube = {
            "grug:block/stone",
            "grug:block/stone",
            "grug:block/side",
            "grug:block/side",
            "grug:block/stone",
            "grug:block/stone"
        };
        faces.put("first", cube);
        faces.put("second", cube); // shares every texture with the first
        faces.put("third", null);

        Map<String, List<String>> needs = GrugSpriteSlots.blockSpriteNeeds(mods, faces);

        assertEquals(3, needs.size());
        assertEquals(List.of("a", "b"), needs.get("grug:block/stone")); // both mods need it
        assertEquals(List.of("a", "b"), needs.get("grug:block/side"));
        assertEquals(List.of("c"), needs.get("block third"));
    }

    @Test
    void theItemAllocatorStopsAtTheLastSlotOfItsOwnGrid() {
        // gui/items.png is a 16 by 16 grid too, and the game repaints none of the slots grug
        // starts after, so every one from 160 to 255 is usable.
        GrugSpriteSlots.Allocator allocator = GrugSpriteSlots.Allocator.items(160);
        assertEquals(96, allocator.remaining());
        int slot = -1;
        for (int item = 0; item < 96; item++) {
            slot = allocator.next();
            assertEquals(160 + item, slot, "item " + item);
        }
        assertEquals(GrugSpriteSlots.LAST_SLOT, slot);
        assertEquals(0, allocator.remaining());

        IllegalStateException full = assertThrows(IllegalStateException.class, allocator::next);
        assertTrue(full.getMessage().contains("gui/items.png"), full.getMessage());
    }
}
