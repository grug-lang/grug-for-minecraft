package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

class GrugClientDataCacheTest {
    @Test
    void remembersDataForTheSameBlockAtAPosition() {
        GrugClientDataCache<String> cache = new GrugClientDataCache<>(4);
        Object world = new Object();
        cache.put(world, -20, 72, -60, 299, "data");

        assertEquals("data", cache.get(world, -20, 72, -60, 299));
        assertNull(cache.get(world, -19, 72, -60, 299));
    }

    @Test
    void forgetsDataWhenTheBlockAtThePositionChanged() {
        GrugClientDataCache<String> cache = new GrugClientDataCache<>(4);
        Object world = new Object();
        cache.put(world, 1, 2, 3, 299, "data");

        assertNull(cache.get(world, 1, 2, 3, 300));
        assertNull(cache.get(world, 1, 2, 3, 299), "the entry of a gone block must not come back");
    }

    @Test
    void keepsEachWorldApartAndClearsOne() {
        GrugClientDataCache<String> cache = new GrugClientDataCache<>(4);
        Object a = new Object();
        Object b = new Object();
        cache.put(a, 1, 2, 3, 299, "a");
        cache.put(b, 1, 2, 3, 299, "b");

        assertEquals("a", cache.get(a, 1, 2, 3, 299));
        cache.clear(a);
        assertNull(cache.get(a, 1, 2, 3, 299));
        assertEquals("b", cache.get(b, 1, 2, 3, 299));
        cache.clear(new Object());
    }

    @Test
    void evictsTheLeastRecentlyUsedPositionPastTheCapacity() {
        GrugClientDataCache<String> cache = new GrugClientDataCache<>(2);
        Object world = new Object();
        cache.put(world, 1, 0, 0, 1, "one");
        cache.put(world, 2, 0, 0, 1, "two");
        assertEquals("one", cache.get(world, 1, 0, 0, 1));
        cache.put(world, 3, 0, 0, 1, "three");

        assertNull(cache.get(world, 2, 0, 0, 1));
        assertEquals("one", cache.get(world, 1, 0, 0, 1));
        assertEquals("three", cache.get(world, 3, 0, 0, 1));
    }

    @Test
    void negativeCoordinatesDoNotCollide() {
        GrugClientDataCache<String> cache = new GrugClientDataCache<>(8);
        Object world = new Object();
        cache.put(world, -1, 5, -1, 1, "neg");
        cache.put(world, 1, 5, 1, 1, "pos");

        assertEquals("neg", cache.get(world, -1, 5, -1, 1));
        assertEquals("pos", cache.get(world, 1, 5, 1, 1));
    }
}
