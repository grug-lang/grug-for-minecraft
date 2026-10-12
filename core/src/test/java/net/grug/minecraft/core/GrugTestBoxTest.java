package net.grug.minecraft.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
