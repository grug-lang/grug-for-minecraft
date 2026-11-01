package net.grug.minecraft.stationapi.mixin;

import net.grug.minecraft.grug.GrugGenerated;
import net.grug.minecraft.grug.GrugSpritePadding;
import net.modificationstation.stationapi.api.client.texture.TextureStitcher;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Reserves one texel of padding around every sprite the atlas packer places (#919).
 *
 * <p>{@code TextureStitcher.stitch} lays every sprite into the atlas from the size {@code
 * TextureStitcher$Holder} records for it: the sort, the slot that first fits it, the strip the
 * atlas grows by when no slot does, and the position {@code Sprite} is then built at all read that
 * size. Growing the recorded size by two in each axis is therefore the one place that makes the
 * packer leave the border room, and it leaves every sprite's own content size alone, which is what
 * the renderers and the sprite's own {@code minU} to {@code maxU} range must keep reading.
 *
 * <p>No other point can do this: {@code SpriteLoader.stitch} hands the sprites to the packer and
 * reads back the slots, and the slot positions are the packer's, so padding after the stitch would
 * have nowhere to put the border without moving the sprite, which is the coordinate change #927 and
 * #928 measured as a rescale of every face. The border itself is written by {@code
 * SpriteContentsMixin}, and {@code SpriteMixin} moves the sprite's origin inward by the same texel
 * so its content keeps the position the padding is built around.
 *
 * <p>The handlers are static because the record's one-argument constructor delegates to its
 * canonical one, so these calls are part of the {@code this(...)} argument list and run before the
 * target has a {@code this} that a handler could be invoked on.
 */
@GrugGenerated(
        "a mixin into StationAPI's atlas packer: Mixin copies the two redirects into"
                + " TextureStitcher$Holder without defining this class, so JaCoCo never sees them"
                + " run, and wooden_pipe_straight takes a fire texel on stationapi without them")
@Mixin(targets = "net.modificationstation.stationapi.api.client.texture.TextureStitcher$Holder")
public class TextureStitcherHolderMixin {

    /**
     * The width the packer reserves, which is the sprite's own width plus the padding on both
     * sides.
     */
    @Redirect(
            method =
                    "<init>(Lnet/modificationstation/stationapi/api/client/texture/TextureStitcher$Stitchable;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/modificationstation/stationapi/api/client/texture/TextureStitcher$Stitchable;getWidth()I"))
    private static int grug$paddedWidth(TextureStitcher.Stitchable sprite) {
        return GrugSpritePadding.paddedWidth(sprite.getWidth());
    }

    /** The height the packer reserves, which is the sprite's own height plus both sides. */
    @Redirect(
            method =
                    "<init>(Lnet/modificationstation/stationapi/api/client/texture/TextureStitcher$Stitchable;)V",
            at =
                    @At(
                            value = "INVOKE",
                            target =
                                    "Lnet/modificationstation/stationapi/api/client/texture/TextureStitcher$Stitchable;getHeight()I"))
    private static int grug$paddedHeight(TextureStitcher.Stitchable sprite) {
        return GrugSpritePadding.paddedHeight(sprite.getHeight());
    }
}
