package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugMobCapTest {
    @Test
    void indexOfFindsEachCategoryAndRefusesOthers() {
        assertEquals(0, GrugMobCap.indexOf("hostile"));
        assertEquals(1, GrugMobCap.indexOf("passive"));
        assertEquals(2, GrugMobCap.indexOf("water"));
        assertEquals(-1, GrugMobCap.indexOf("Hostile"));
    }

    @Test
    void onePlayerHas289EligibleChunksAndTheGameCaps() {
        int chunks = GrugMobCap.eligibleChunks(new int[] {-3}, new int[] {7});
        assertEquals(289, chunks);
        assertEquals(79, GrugMobCap.cap(0, chunks));
        assertEquals(16, GrugMobCap.cap(1, chunks));
        assertEquals(5, GrugMobCap.cap(2, chunks));
    }

    @Test
    void version1206DividesBy289SoOnePlayerGetsTheBaseCaps() {
        int chunks = GrugMobCap.eligibleChunks(new int[] {0}, new int[] {0});
        assertEquals(70, GrugMobCap.cap(70, chunks, 289));
        assertEquals(10, GrugMobCap.cap(10, chunks, 289));
        assertEquals(5, GrugMobCap.cap(5, chunks, 289));
        assertEquals(0, GrugMobCap.cap(70, 0, 289));
        assertEquals(74, GrugMobCap.cap(70, 306, 289));
    }

    @Test
    void persistentMobsAreNotCounted() {
        assertTrue(GrugMobCap.counted(false, true));
        assertFalse(GrugMobCap.counted(true, true));
        assertFalse(GrugMobCap.counted(false, false));
        assertFalse(GrugMobCap.counted(true, false));
    }

    @Test
    void playersShareTheChunksTheyOverlapIn() {
        assertEquals(289, GrugMobCap.eligibleChunks(new int[] {0, 0}, new int[] {0, 0}));
        assertEquals(306, GrugMobCap.eligibleChunks(new int[] {0, 1}, new int[] {0, 0}));
        assertEquals(578, GrugMobCap.eligibleChunks(new int[] {0, 100}, new int[] {0, 0}));
        assertEquals(0, GrugMobCap.eligibleChunks(new int[] {}, new int[] {}));
    }

    @Test
    void negativeChunkCoordinatesDoNotCollideWithPositiveOnes() {
        assertEquals(578, GrugMobCap.eligibleChunks(new int[] {0, -100}, new int[] {-1, 0}));
    }
}
