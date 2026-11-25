package net.grug.minecraft.ornithe.client;

import net.minecraft.client.render.texture.DynamicTexture;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

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
 * <p>The names are matched as strings rather than classes because Alpha ships neither the clock nor
 * the nether portal sprite, and this one set serves both versions.
 */
public final class GrugFrozenAnimations {
    private static final Set<String> ANIMATED =
            new HashSet<>(
                    Arrays.asList(
                            "WaterSprite",
                            "WaterSideSprite",
                            "LavaSprite",
                            "LavaSideSprite",
                            "FireSprite",
                            "NetherPortalSprite"));

    private GrugFrozenAnimations() {}

    /** Whether {@code texture} is one of the game's own animated sprites. */
    public static boolean isGameAnimation(DynamicTexture texture) {
        return ANIMATED.contains(texture.getClass().getSimpleName());
    }
}
