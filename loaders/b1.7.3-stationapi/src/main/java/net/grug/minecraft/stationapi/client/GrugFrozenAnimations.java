package net.grug.minecraft.stationapi.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.render.texture.DynamicTexture;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Which of the texture manager's dynamic textures a capture run holds still, so an animated tile
 * keeps the pixels the texture pack stitched.
 *
 * <p>Beta animates water, lava, fire and the nether portal through a dynamic texture whose every
 * frame StationAPI's renderer binds into its own stitched atlas, so a capture of the tank's column
 * held in water was a different animation frame in every run and the golden one run wrote never
 * matched the next run or the dedicated leg. The compass and the clock are absent, so they keep
 * animating as the other loaders leave them, because no capture shows them. See {@code
 * GrugReference.isAnimationFrozen} and #1771.
 *
 * <p>StationAPI's renderer replaces Beta's six sprite classes with its own binders, and those are
 * what the list holds at run time: a capture run's list is eighteen entries, all {@code Arsenic*}
 * binders, so a set of the game's own class names alone freezes nothing. Both families are listed,
 * because which one a given StationAPI build installs is not this loader's to assume.
 *
 * <p>A sprite is identified by its binary class name, not by its simple name, which two classes in
 * different packages can share and which a lambda's synthetic hidden class makes unstable. An
 * installed game names the class in its intermediary namespace, so the name is unmapped to the
 * named one before it is compared. The identity sits here rather than in the mixin because Mixin
 * refuses a non-private method in a mixin class, and a unit test has to reach it.
 */
public final class GrugFrozenAnimations {

    /**
     * The binary names of the tiles a capture run holds still, with the clock and compass absent.
     */
    private static final Set<String> ANIMATED =
            new HashSet<>(
                    Arrays.asList(
                            "net.minecraft.client.render.texture.WaterSprite",
                            "net.minecraft.client.render.texture.WaterSideSprite",
                            "net.minecraft.client.render.texture.LavaSprite",
                            "net.minecraft.client.render.texture.LavaSideSprite",
                            "net.minecraft.client.render.texture.FireSprite",
                            "net.minecraft.client.render.texture.NetherPortalSprite",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicStillWater",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFlowingWater",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicStillLava",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFlowingLava",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicFire",
                            "net.modificationstation.stationapi.impl.client.arsenic.renderer.render"
                                    + ".binder.ArsenicPortal"));

    private GrugFrozenAnimations() {}

    /** Whether {@code texture} is one of the game's own animated tiles. */
    public static boolean isGameAnimation(DynamicTexture texture) {
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        return isGameAnimationName(
                texture.getClass().getName(), name -> resolver.unmapClassName("named", name));
    }

    /**
     * Whether the class with this binary name is one of the tiles, once {@code toNamed} has
     * unmapped a name the running game's namespace to the named one. Split out so a unit test can
     * pin the identity without a game or a mapping resolver.
     */
    public static boolean isGameAnimationName(String binaryName, Function<String, String> toNamed) {
        return ANIMATED.contains(toNamed.apply(binaryName));
    }
}
