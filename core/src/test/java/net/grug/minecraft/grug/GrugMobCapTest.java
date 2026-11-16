package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
