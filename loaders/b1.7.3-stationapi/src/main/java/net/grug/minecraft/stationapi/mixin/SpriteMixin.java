package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpritePadding;
import net.modificationstation.stationapi.api.client.texture.Sprite;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/**
 * Moves a stitched sprite's content one texel in from the region the packer gave it, so the border
 * {@code TextureStitcherHolderMixin} reserves surrounds the sprite instead of being part of it
 * (#919).
 *
 * <p>{@code Sprite}'s constructor turns the packer's {@code x, y} into the sprite's origin, its
 * {@code minU} to {@code maxU} range, and where its frames are uploaded. All of those have to be
 * the content's, not the padded region's: a face spans {@code minU} to {@code maxU}, so a range
 * that took in the border would stretch the face by two texels, and an upload at the region's
 * origin would write the content over the border instead of inside it. Shifting the two coordinates
 * here is what makes the whole sprite, and every coordinate any renderer derives from it, keep the
 * position it had before the padding existed.
 *
 * <p>The handlers are static because the constructor's head is before its own {@code super()} call,
 * where no instance a handler could be invoked on exists yet.
 */
@GrugGenerated(
        "a mixin into StationAPI's sprite: Mixin copies the two modifiers into Sprite without"
                + " defining this class, so JaCoCo never sees them run, and every face is stretched"
                + " by the padding instead of keeping its sprite's own position without them")
@Mixin(Sprite.class)
public class SpriteMixin {

    /** The x the sprite's content starts at: the region's, plus the left padding. */
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 5)
    private static int grug$contentX(int x) {
        return x + GrugSpritePadding.PAD;
    }

    /** The y the sprite's content starts at: the region's, plus the top padding. */
    @ModifyVariable(method = "<init>", at = @At("HEAD"), argsOnly = true, index = 6)
    private static int grug$contentY(int y) {
        return y + GrugSpritePadding.PAD;
    }
}
