package net.grug.minecraft.bootstrap;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class GrugFabricPreLaunchTest {
    @Test
    void doesNothingWhenALayerLoaded() {
        new GrugFabricPreLaunch("grug"::equals).onPreLaunch();
    }

    @Test
    void reportsAGameWhereNoLayerLoaded() {
        IllegalStateException error =
                assertThrows(
                        IllegalStateException.class,
                        () -> new GrugFabricPreLaunch(id -> false).onPreLaunch());
        assertTrue(error.getMessage().startsWith("grug has no layer for this game,"));
    }

    @Test
    void asksFabricLoaderWhetherALayerLoaded() {
        // The stand-in Fabric Loader has loaded no mod, as on a game without a layer.
        assertThrows(IllegalStateException.class, () -> new GrugFabricPreLaunch().onPreLaunch());
    }
}
