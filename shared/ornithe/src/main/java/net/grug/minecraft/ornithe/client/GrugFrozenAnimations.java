package net.grug.minecraft.ornithe.client;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.client.render.texture.DynamicTexture;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Function;

/**
 * Names the game's own animated sprites, which a capture run leaves on the tile the texture pack
 * drew.
 *
 * <p>Alpha and Beta animate water, lava, fire, the compass, the clock and the nether portal by
 * writing new pixels into their atlas slot every tick. A capture of a scene holding one would
 * otherwise be whichever frame the run happened to reach: no committed golden could match the next
 * run, and the reference job, which compares its live capture with a golden exactly, could not
 * match at all. {@link net.grug.minecraft.ornithe.mixin.TextureManagerMixin} drops these from the
 * manager's list before the first upload, so the slot keeps the pack's own tile, which is the same
 * pixels in every run. The compass and the clock follow the player and the clock rather than the
 * number of frames a run has drawn, so they are left animating. See {@code
 * GrugReference.isAnimationFrozen()} and #1771.
 *
 * <p>A sprite is identified by its binary class name, not by its simple name: two classes in
 * different packages may share a simple name, and a lambda's synthetic hidden class has one that
 * names nothing in the game. A development run sees the named classes and an installed game sees
 * intermediary ones, so the running game's name is unmapped to the named namespace before it is
 * compared, the way {@link net.grug.minecraft.ornithe.GrugVanillaNames} resolves a field. Alpha
 * ships neither the clock nor the nether portal sprite, and naming one costs nothing: it simply
 * never matches.
 */
public final class GrugFrozenAnimations {
    private static final Set<String> ANIMATED =
            new HashSet<>(
                    Arrays.asList(
                            "net.minecraft.client.render.texture.WaterSprite",
                            "net.minecraft.client.render.texture.WaterSideSprite",
                            "net.minecraft.client.render.texture.LavaSprite",
                            "net.minecraft.client.render.texture.LavaSideSprite",
                            "net.minecraft.client.render.texture.FireSprite",
                            "net.minecraft.client.render.texture.NetherPortalSprite"));

    private GrugFrozenAnimations() {}

    /** Whether {@code texture} is one of the game's own animated sprites. */
    public static boolean isGameAnimation(DynamicTexture texture) {
        MappingResolver resolver = FabricLoader.getInstance().getMappingResolver();
        return isGameAnimationName(
                texture.getClass().getName(), name -> resolver.unmapClassName("named", name));
    }

    /**
     * Whether the class with this binary name is one of the sprites, once {@code toNamed} has
     * turned the running game's name into the named one. Split out so a unit test can pin the
     * identity without a game or a mapping resolver.
     */
    static boolean isGameAnimationName(String binaryName, Function<String, String> toNamed) {
        return ANIMATED.contains(toNamed.apply(binaryName));
    }
}
