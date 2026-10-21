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
        GrugUnsupportedGameException error =
                assertThrows(
                        GrugUnsupportedGameException.class,
                        () -> new GrugFabricPreLaunch(id -> false).onPreLaunch());
        assertTrue(error.getMessage().startsWith("grug does not run on this game:"));
    }

    @Test
    void asksFabricLoaderWhetherALayerLoaded() {
        // The stand-in Fabric Loader has loaded no mod, as on a game without a layer.
        assertThrows(
                GrugUnsupportedGameException.class, () -> new GrugFabricPreLaunch().onPreLaunch());
    }
}
