package net.grug.minecraft.grug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

/** Covers {@link WeakGrugValueMap}'s storage and explicit clearing. */
class WeakGrugValueMapTest {

    @Test
    void getReturnsWhatWasPut() {
        WeakGrugValueMap map = new WeakGrugValueMap();
        GrugObject value = new GrugObject(GrugEntityType.Item, "diamond");
        map.put(1L, value);
        assertSame(value, map.get(1L));
    }

    @Test
    void getReturnsNullForAMissingId() {
        assertNull(new WeakGrugValueMap().get(1L));
    }

    @Test
    void sizeCountsStoredValues() {
        WeakGrugValueMap map = new WeakGrugValueMap();
        assertEquals(0, map.size());
        map.put(1L, new GrugObject(GrugEntityType.Item, "a"));
        map.put(2L, new GrugObject(GrugEntityType.Item, "b"));
        assertEquals(2, map.size());
    }

    @Test
    void putReplacesTheValueForAnExistingId() {
        WeakGrugValueMap map = new WeakGrugValueMap();
        map.put(1L, new GrugObject(GrugEntityType.Item, "a"));
        GrugObject replacement = new GrugObject(GrugEntityType.Item, "b");
        map.put(1L, replacement);
        assertEquals(1, map.size());
        assertSame(replacement, map.get(1L));
    }

    @Test
    void clearDropsEverything() {
        WeakGrugValueMap map = new WeakGrugValueMap();
        map.put(1L, new GrugObject(GrugEntityType.Item, "a"));
        map.clear();
        assertEquals(0, map.size());
        assertNull(map.get(1L));
    }

    @Test
    void cleanupDropsCollectedValues() throws InterruptedException {
        WeakGrugValueMap map = new WeakGrugValueMap();
        map.put(1L, new GrugObject(GrugEntityType.Item, "collect-me"));
        // The only strong reference is inside the map, so a collection makes the entry cleanable.
        for (int attempt = 0; attempt < 50 && map.size() != 0; attempt++) {
            System.gc();
            Thread.sleep(10);
            map.size();
        }
        assertEquals(0, map.size());
    }
}
