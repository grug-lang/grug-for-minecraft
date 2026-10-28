package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Covers {@link GrugTestBox#sweepItems}, which the runner calls in the tick a test ends. */
class GrugTestBoxTest {
    @Test
    void sweepsTheWholeTestAreaWithoutTouchingABlock() {
        List<String> calls = new ArrayList<>();
        ModLoaderAdapter adapter =
                (ModLoaderAdapter)
                        Proxy.newProxyInstance(
                                ModLoaderAdapter.class.getClassLoader(),
                                new Class<?>[] {ModLoaderAdapter.class},
                                (proxy, method, args) -> {
                                    calls.add(method.getName() + java.util.Arrays.asList(args));
                                    return null;
                                });

        GrugTestBox.sweepItems(adapter, "level", 10.5, 64.0, -20.5);

        // The area is 16 blocks around the feet block (10, 64, -21), down to one below the lowest
        // the walls reach and up to 56 above, with every upper bound one past the last block.
        assertEquals(
                List.of("clearItemEntities[level, -6.0, 47.0, -37.0, 27.0, 121.0, -4.0]"), calls);
    }

    @Test
    void theClearedAreaIsSixteenBlocksAroundTheFeetFromJustBelowTheWallsUpToFiftySixAbove() {
        // The feet block is (10, 64, -21), as in the sweep above.
        assertEquals(16, GrugTestBox.areaRadius());
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -21));
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, -6, 47, -37));
        assertTrue(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 26, 120, -5));

        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, -7, 64, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 27, 64, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -38));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 64, -4));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 46, -21));
        assertFalse(GrugTestBox.inClearedArea(10.5, 64.0, -20.5, 10, 121, -21));
    }
}
